/*
 * SPDX-FileCopyrightText: 2026 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.backends.lan

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.net.Network
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import kotlinx.serialization.SerializationException
import org.kde.kdeconnect.DeviceInfo
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.NetworkPacket.Companion.unserialize
import org.kde.kdeconnect.backends.BaseLink
import org.kde.kdeconnect.backends.BaseLinkProvider
import org.kde.kdeconnect.extensions.closeSafe
import org.kde.kdeconnect.helpers.DeviceHelper
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.helpers.TrustedDevices
import org.kde.kdeconnect.helpers.TrustedNetworkHelper
import org.kde.kdeconnect.helpers.isPrivateAddress
import org.kde.kdeconnect.helpers.readLineBounded
import org.kde.kdeconnect.helpers.security.SslHelper
import org.kde.kdeconnect.ui.CustomDevicesActivity
import top.miceworld.kdeconnectplus.reconnect.CustomDeviceRetry
import top.miceworld.kdeconnectplus.reconnect.LanLinkPreference // kdeconnect+：回环连接优先升级到 LAN
import top.miceworld.kdeconnectplus.reconnect.LoopbackPayloadAddress // kdeconnect+：本机代理后的载荷地址回退
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.cert.CertificateException
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory
import javax.net.ssl.HandshakeCompletedEvent
import javax.net.ssl.SSLSocket

/**
 * This LanLinkProvider creates [LanLink]s to other devices on the same network.
 */
class LanLinkProvider(private val context: Context) : BaseLinkProvider() {

    private val visibleDevices = ConcurrentHashMap<String, LanLink>() // Links by device id

    private val mdnsDiscovery = MdnsDiscovery(context, this)

    private val lastConnectionTimeByDeviceId = ConcurrentHashMap<String, Long>()
    private val lastConnectionTimeByIp = ConcurrentHashMap<InetAddress, Long>()
    // kdeconnect+：回环与 LAN 分开限速，避免 tailnet 的身份包挡住 LAN 升级。
    private val lastLoopbackConnectionTimeByDeviceId = ConcurrentHashMap<String, Long>()

    private val customDevicePreferences: SharedPreferences =
        context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)

    // kdeconnect+：只读暴露已解析的自定义地址，供本机代理连接的载荷回退使用
    @Volatile
    internal var customDeviceAddresses = emptyList<InetAddress>()
        private set

    // kdeconnect+：断线时重试自定义 IP；已连接但只走回环时，独立探测 LAN，见 plus/reconnect。
    private val customDeviceRetry = CustomDeviceRetry(
        context,
        hasCustomDevices = { customDeviceAddresses.isNotEmpty() },
        sendToCustomDevices = { broadcastUdpIdentityPacket(null, customDevicesOnly = true) },
        lanPreference = LanLinkPreference(
            context,
            isLoopbackLink = { visibleDevices[it]?.isLoopback == true },
            sendIdentity = ::sendUdpIdentityPacket,
        ),
    )

    private val customDeviceListener = OnSharedPreferenceChangeListener { _, key ->
        if (key == CustomDevicesActivity.KEY_CUSTOM_DEVICE_LIST_PREFERENCE) {
            LoopbackPayloadAddress.clear() // kdeconnect+：自定义设备配置变化后忘记旧载荷地址
            updateCustomDevicesList()
        }
    }

    fun updateCustomDevicesList() {
        val addresses = CustomDevicesActivity.getCustomDeviceList(context).flatMap {
            runCatching {
                InetAddress.getAllByName(it.host).toList()
            }.getOrDefault(emptyList())
        }
        // kdeconnect+：解析结果变化（含 DNS 变化）时也忘记旧载荷地址，不因重复广播清空记忆
        if (addresses != customDeviceAddresses) {
            LoopbackPayloadAddress.clear()
            customDeviceAddresses = addresses
        }
    }

    @Volatile
    private var tcpServer: ServerSocket? = null
    @Volatile
    private var udpServer: DatagramSocket? = null

    private var lastBroadcast: Long = 0
    @Volatile
    private var isStopped = true

    fun hasDevice(id: String): Boolean {
        return visibleDevices.containsKey(id)
    }

    override fun onConnectionLost(link: BaseLink) {
        val deviceId = link.deviceId
        // Do not let an old connection remove its replacement.
        if (visibleDevices.remove(deviceId, link)) {
            super.onConnectionLost(link)
            customDeviceRetry.onLinksChanged(lanDowngraded = false) // kdeconnect+：停止或重新评估 LAN 探测
        }
    }

    // kdeconnect+：带入来源地址，区分回环限速与 LAN 升级。
    fun unserializeReceivedIdentityPacket(message: String, address: InetAddress): Pair<NetworkPacket, Boolean>? {
        val identityPacket: NetworkPacket?
        try {
            identityPacket = unserialize(message)
        } catch (e: SerializationException) {
            Log.w("KDE/LanLinkProvider", "Invalid identity packet received: ${e.message}")
            return null
        }

        if (!DeviceInfo.isValidIdentityPacket(identityPacket)) {
            Log.w("KDE/LanLinkProvider", "Invalid identity packet received.")
            return null
        }

        val deviceId = identityPacket.getString("deviceId")
        val myId = DeviceHelper.getDeviceId(context)
        if (deviceId == myId) {
            //Ignore my own broadcast
            return null
        }

        if (rateLimitByDeviceId(deviceId, address)) {
            Log.i("LanLinkProvider", "Discarding second packet from the same device $deviceId received too quickly")
            return null
        }

        val deviceTrusted = TrustedDevices.isTrustedDevice(context, deviceId)
        if (!deviceTrusted && !TrustedNetworkHelper.isTrustedNetwork(context)) {
            Log.i("KDE/LanLinkProvider", "Ignoring identity packet because the device is not trusted and I'm not on a trusted network.")
            return null
        }

        return Pair(identityPacket, deviceTrusted)
    }

    // They received my UDP broadcast and are connecting to me. The first thing they send should be their identity packet.
    @WorkerThread
    @Throws(IOException::class, CertificateException::class)
    private fun tcpPacketReceived(socket: Socket) {
        val address = socket.getInetAddress()

        // kdeconnect+：经本机代理（如 sing-box）进来的连接都来自 127.0.0.1，不按 IP 限速，只按设备 ID 限速
        if (!address.isLoopbackAddress && rateLimitByIp(address)) {
            Log.i("LanLinkProvider", "Discarding second TCP packet from the same ip $address received too quickly" )
            socket.closeSafe()
            return
        }

        if (!isAllowedAddress(address)) {
            Log.i("LanLinkProvider", "Discarding TCP packet from a non-local IP: $address")
            socket.closeSafe()
            return
        }

        val message: String?
        try {
            // We don't use a BufferedInputStream on purpose, since BufferedReader reads ahead and would require
            // us to keep a single BufferedInputStream instance and pass it around to make sure we don't lose data.
            // This means we are readying byte by byte directly from the OS, which is slow, but only for the handshake.
            message = readLineBounded(socket.getInputStream(), MAX_IDENTITY_PACKET_SIZE)
            //Log.e("TcpListener", "Received TCP packet: $message");
        } catch (e: Exception) {
            Log.e("KDE/LanLinkProvider", "Exception while receiving TCP packet", e)
            socket.closeSafe()
            return
        }

        val (identityPacket, deviceTrusted)  = unserializeReceivedIdentityPacket(message, address) ?: run { // kdeconnect+
            socket.closeSafe()
            return
        }

        Log.i("KDE/LanLinkProvider", "identity packet received from a TCP connection from ${identityPacket.getString("deviceName")}")

        val targetDeviceId = identityPacket.getStringOrNull("targetDeviceId")
        val targetProtocolVersion = identityPacket.getIntOrNull("targetProtocolVersion")
        if (targetDeviceId != null && targetDeviceId != DeviceHelper.getDeviceId(context)) {
            Log.e("KDE/LanLinkProvider", "Received a connection request for a device that isn't me: $targetDeviceId")
            socket.closeSafe()
            return
        }
        if (targetProtocolVersion != null && targetProtocolVersion != DeviceHelper.PROTOCOL_VERSION) {
            Log.e("KDE/LanLinkProvider", "Received a connection request for a protocol version that isn't mine: $targetProtocolVersion")
            socket.closeSafe()
            return
        }

        identityPacketReceived(identityPacket, socket, ConnectionStarted.Locally, deviceTrusted)
    }

    private fun isAllowedAddress(address: InetAddress): Boolean {
        return isPrivateAddress(address) || customDeviceAddresses.contains(address)
    }

    fun rateLimitByIp(address: InetAddress): Boolean {
        val now = SystemClock.elapsedRealtime()
        val last = lastConnectionTimeByIp[address]
        if (last != null && (last + MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE > now)) {
            return true
        }
        lastConnectionTimeByIp[address] = now
        if (lastConnectionTimeByIp.size > MAX_RATE_LIMIT_ENTRIES) {
            lastConnectionTimeByIp.entries.removeIf { it.value + MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE < now }
        }
        return false
    }

    // kdeconnect+：保留每条路径的设备 ID 限速，不让回环流量消耗 LAN 的额度。
    fun rateLimitByDeviceId(deviceId: String, address: InetAddress): Boolean {
        val times = if (address.isLoopbackAddress) lastLoopbackConnectionTimeByDeviceId else lastConnectionTimeByDeviceId
        val now = SystemClock.elapsedRealtime()
        val last = times[deviceId]
        if (last != null && (last + MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE > now)) {
            return true
        }
        times[deviceId] = now
        if (times.size > MAX_RATE_LIMIT_ENTRIES) {
            times.entries.removeIf { it.value + MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE < now }
        }
        return false
    }

    //I've received their broadcast and should connect to their TCP socket and send my identity.
    @WorkerThread
    private fun udpPacketReceived(packet: DatagramPacket) {
        val address = packet.address

        if (!address.isLoopbackAddress && rateLimitByIp(address)) { // kdeconnect+：同上
            Log.i("LanLinkProvider", "Discarding second UDP packet from the same ip $address received too quickly")
            return
        }

        if (!isAllowedAddress(address)) {
            Log.i("LanLinkProvider", "Discarding UDP packet from a non-local IP: $address")
            return
        }

        val message = String(packet.data, 0, packet.length, Charsets.UTF_8)

        val (identityPacket, deviceTrusted) = unserializeReceivedIdentityPacket(message, address) // kdeconnect+
            ?: return

        Log.i("KDE/LanLinkProvider", "Broadcast identity packet received from ${identityPacket.getString("deviceName")}")

        // kdeconnect+：来自 127.0.0.1 的包原始地址已丢失，回连只会连到自己；改为让对方来连我们。
        // 这个包没有用来建立连接，撤销它的限速登记，否则对方紧接着发来的 TCP 连接会被当成重复连接丢掉。
        if (address.isLoopbackAddress) {
            val deviceId = identityPacket.getString("deviceId")
            lastLoopbackConnectionTimeByDeviceId.remove(deviceId) // kdeconnect+：只撤销回环包的登记
            customDeviceRetry.onLoopbackIdentity(deviceId)
            return
        }

        val tcpPort = identityPacket.getInt("tcpPort", MIN_PORT)
        if (tcpPort !in MIN_PORT..MAX_PORT) {
            Log.e("LanLinkProvider", "TCP port outside of kdeconnect's range")
            return
        }

        var socket: Socket? = null
        try {
            socket = SocketFactory.getDefault().createSocket(address, tcpPort)
            configureSocket(socket)

            val myDeviceInfo = DeviceHelper.getDeviceInfo(context)
            val myIdentity = myDeviceInfo.toIdentityPacket()
            myIdentity["targetDeviceId"] = identityPacket.getString("deviceId")
            myIdentity["targetProtocolVersion"] = identityPacket.getString("protocolVersion")

            val out = socket.getOutputStream()
            out.write(myIdentity.serialize().toByteArray())
            out.flush()

            identityPacketReceived(identityPacket, socket, ConnectionStarted.Remotely, deviceTrusted)
            socket = null // The SSL socket now owns the underlying socket, or it was rejected and closed.
        } catch (e: IOException) {
            Log.e("LanLinkProvider", "Exception receiving incoming UDP connection", e)
        } catch (e: CertificateException) {
            Log.e("LanLinkProvider", "Exception receiving incoming UDP connection", e)
        } catch (e: SerializationException) {
            Log.e("LanLinkProvider", "Exception receiving incoming UDP connection", e)
        } finally {
            socket?.closeSafe()
        }
    }

    private fun configureSocket(socket: Socket) {
        try {
            socket.setKeepAlive(true)
            socket.setSoTimeout(10 * 1000)
        } catch (e: SocketException) {
            Log.e("LanLink", "Exception", e)
        }
    }

    /**
     * Called when a new 'identity' packet is received. Those are passed here by
     * [.tcpPacketReceived] and [.udpPacketReceived].
     * Should be called on a new thread since it blocks until the handshake is completed.
     * 
     * @param identityPacket    identity of a remote device
     * @param socket            a new Socket, which should be used to receive packets from the remote device
     * @param connectionStarted which side started this connection
     * @param deviceTrusted     whether the packet comes from a trusted device
     */
    @WorkerThread
    @Throws(IOException::class, CertificateException::class)
    private fun identityPacketReceived(
        identityPacket: NetworkPacket,
        socket: Socket,
        connectionStarted: ConnectionStarted,
        deviceTrusted: Boolean
    ) {
        val deviceId = identityPacket.getString("deviceId")
        val protocolVersion = identityPacket.getInt("protocolVersion")

        if (deviceTrusted && isProtocolDowngrade(deviceId, protocolVersion)) {
            Log.w("KDE/LanLinkProvider", "Refusing to connect to a device using an older protocol version:$protocolVersion")
            socket.closeSafe()
            return
        }

        if (deviceTrusted && !TrustedDevices.isCertificateStored(context, deviceId)) {
            Log.e("KDE/LanLinkProvider", "Device trusted but no cert stored. This should not happen.")
            socket.closeSafe()
            return
        }

        if (!deviceTrusted && visibleDevices.size >= MAX_UNPAIRED_CONNECTIONS) {
            Log.w("KDE/LanLinkProvider", "Too many devices on the network. Ignoring $deviceId")
            socket.closeSafe()
            return
        }

        Log.i("KDE/LanLinkProvider", "Starting SSL handshake with $deviceId trusted:$deviceTrusted")

        // If I'm the TCP server I will be the SSL client and vice-versa.
        val clientMode = (connectionStarted == ConnectionStarted.Locally)
        val sslSocket = SslHelper.convertToSslSocket(context, socket, deviceId, deviceTrusted, clientMode)
        sslSocket.addHandshakeCompletedListener { event: HandshakeCompletedEvent ->
            // Start a new thread because some Android versions don't allow calling sslSocket.getOutputStream() from the callback
            ThreadHelper.execute {
                val mode = if (clientMode) "client" else "server"
                try {
                    val secureIdentityPacket: NetworkPacket?
                    if (protocolVersion >= 8) {
                        val myDeviceInfo = DeviceHelper.getDeviceInfo(context)
                        val myIdentity = myDeviceInfo.toIdentityPacket()

                        val writer = sslSocket.getOutputStream()
                        writer.write(myIdentity.serialize().toByteArray(Charsets.UTF_8))
                        writer.flush()
                        val line = readLineBounded(sslSocket.getInputStream(), MAX_IDENTITY_PACKET_SIZE)
                        // Do not trust the identity packet we received unencrypted
                        secureIdentityPacket = unserialize(line)
                        if (!DeviceInfo.isValidIdentityPacket(secureIdentityPacket)) {
                            Log.e("KDE/LanLinkProvider", "Identity packet isn't valid")
                            sslSocket.closeSafe()
                            return@execute
                        }
                        val newProtocolVersion = secureIdentityPacket.getInt("protocolVersion")
                        if (newProtocolVersion != protocolVersion) {
                            Log.e("KDE/LanLinkProvider", "Protocol version changed half-way through the handshake: $protocolVersion -> $newProtocolVersion")
                            sslSocket.closeSafe()
                            return@execute
                        }
                        val newDeviceId = secureIdentityPacket.getString("deviceId")
                        if (newDeviceId != deviceId) {
                            Log.e("KDE/LanLinkProvider", "Device ID changed half-way through the handshake: $deviceId -> $newDeviceId")
                            sslSocket.closeSafe()
                            return@execute
                        }
                    } else {
                        secureIdentityPacket = identityPacket
                    }
                    val certificate = event.peerCertificates[0]
                    val deviceInfo = DeviceInfo.fromIdentityPacketAndCert(secureIdentityPacket, certificate)
                    Log.i("KDE/LanLinkProvider", "Handshake as $mode successful with ${deviceInfo.name} secured with ${event.cipherSuite}")
                    addOrUpdateLink(sslSocket, deviceInfo)
                } catch (e: SerializationException) {
                    Log.e("KDE/LanLinkProvider", "Remote device doesn't correctly implement protocol version 8", e)
                    sslSocket.closeSafe()
                } catch (e: IOException) {
                    Log.e("KDE/LanLinkProvider", "Handshake as $mode failed with $deviceId", e)
                    sslSocket.closeSafe()
                }
            }
        }

        //Handshake is blocking, so do it on another thread and free this thread to keep receiving new connection
        Log.d("LanLinkProvider", "Starting handshake")
        sslSocket.startHandshake()
        Log.d("LanLinkProvider", "Handshake done")
    }

    private fun isProtocolDowngrade(deviceId: String, protocolVersion: Int): Boolean {
        val lastKnownProtocolVersion = DeviceInfo.loadProtocolVersionFromSettings(context, deviceId)
        return lastKnownProtocolVersion > protocolVersion
    }

    /**
     * Add or update a link in the [.visibleDevices] map.
     * 
     * @param socket           a new Socket, which should be used to send and receive packets from the remote device
     * @param deviceInfo       remote device info
     * @throws IOException if an exception is thrown by [LanLink.reset]
     */
    @WorkerThread
    @Synchronized
    private fun addOrUpdateLink(socket: SSLSocket, deviceInfo: DeviceInfo) {
        var linkCreated = false
        var lanDowngraded = false // kdeconnect+：只在已有 LAN 被回环 socket 替换时立即探测
        val link = visibleDevices.computeIfAbsent(deviceInfo.id) {
            linkCreated = true
            LanLink(context, this, deviceInfo, socket)
        }

        if (linkCreated) {
            Log.d("KDE/LanLinkProvider", "Creating a new link for device ${deviceInfo.id}")
            onConnectionReceived(link)
            link.startListening()
        } else {
            if (link.deviceInfo.certificate != deviceInfo.certificate) {
                Log.e("LanLinkProvider", "LanLink was asked to replace a socket but the certificate doesn't match, aborting")
                socket.closeSafe()
                return
            }
            Log.d("KDE/LanLinkProvider", "Reusing same link for device ${deviceInfo.id}")
            lanDowngraded = !link.isLoopback && socket.inetAddress.isLoopbackAddress // kdeconnect+
            link.reset(socket, deviceInfo)
            onDeviceInfoUpdated(deviceInfo)
        }
        customDeviceRetry.onLinksChanged(lanDowngraded) // kdeconnect+：降级立即探测；reset 不改变可达性也要通知
    }

    @Synchronized
    private fun setupUdpListener() {
        if (udpServer != null) {
            return
        }
        var newUdpServer: DatagramSocket? = null
        try {
            newUdpServer = DatagramSocket(null)
            newUdpServer.reuseAddress = true
            newUdpServer.broadcast = true
            newUdpServer.bind(InetSocketAddress(UDP_PORT))
            udpServer = newUdpServer
        } catch (e: SocketException) {
            // We ignore this exception and continue without being able to receive broadcasts instead of crashing the app.
            Log.e("LanLinkProvider", "Error binding udp server. We can send udp broadcasts but not receive them", e)
            newUdpServer?.closeSafe()
            return
        }
        ThreadHelper.execute {
            Log.i("UdpListener", "Starting UDP listener")
            while (!isStopped) {
                try {
                    val packet = DatagramPacket(ByteArray(MAX_UDP_PACKET_SIZE), MAX_UDP_PACKET_SIZE)
                    newUdpServer.receive(packet)
                    ThreadHelper.execute {
                        try {
                            udpPacketReceived(packet)
                        } catch (e: Exception) {
                            Log.e(
                                "LanLinkProvider",
                                "Unhandled exception receiving incoming UDP connection",
                                e
                            )
                        }
                    }
                } catch (e: IOException) {
                    Log.e("LanLinkProvider", "UdpReceive exception", e)
                    onNetworkChange(null) // Trigger a UDP broadcast to try to get them to connect to us instead
                }
            }
            Log.w("UdpListener", "Stopping UDP listener")
        }
    }

    @Synchronized
    private fun setupTcpListener() {
        if (tcpServer != null) {
            return
        }
        val newTcpServer: ServerSocket
        try {
            newTcpServer = openServerSocketOnFreePort(MIN_PORT)
            tcpServer = newTcpServer
        } catch (e: IOException) {
            Log.e("LanLinkProvider", "Error opening tcp server", e)
            return
        }
        ThreadHelper.execute {
            while (!isStopped) {
                try {
                    val socket = newTcpServer.accept()
                    configureSocket(socket)
                    ThreadHelper.execute {
                        try {
                            tcpPacketReceived(socket)
                        } catch (e: IOException) {
                            socket.closeSafe()
                            Log.e("LanLinkProvider", "Exception receiving incoming TCP connection", e)
                        } catch (e: CertificateException) {
                            socket.closeSafe()
                            Log.e("LanLinkProvider", "Exception receiving incoming TCP connection", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("LanLinkProvider", "TcpReceive exception", e)
                }
            }
            Log.w("TcpListener", "Stopping TCP listener")
        }
    }

    // kdeconnect+：所有身份包发送入口共用 Android 17 局域网权限检查，包括绑定 LAN 的探测。
    private fun canSendUdpIdentityPacket(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            Log.w("LanLinkProvider", "Will not UDP broadcast, missing ACCESS_LOCAL_NETWORK permission")
            return false
        }
        return true
    }

    // kdeconnect+：customDevicesOnly 只发给自定义 IP，供定时重试使用
    private fun broadcastUdpIdentityPacket(network: Network?, customDevicesOnly: Boolean = false) {
        if (!canSendUdpIdentityPacket()) return // kdeconnect+：与直接发送入口共用检查
        ThreadHelper.execute {
            val ipList = ArrayList<InetAddress>()
            if (customDevicesOnly) {
                // kdeconnect+：不在局域网里广播
            } else if (TrustedNetworkHelper.isTrustedNetwork(context)) {
                ipList.add(InetAddress.getByName("255.255.255.255")) // Default: broadcast.
            } else {
                Log.i("LanLinkProvider", "Current network isn't trusted, not broadcasting")
            }
            updateCustomDevicesList()
            ipList.addAll(customDeviceAddresses)
            if (ipList.isNotEmpty()) {
                sendUdpIdentityPacket(ipList, network)
            }
        }
    }

    @WorkerThread
    fun sendUdpIdentityPacket(ipList: List<InetAddress>, network: Network?) {
        if (!canSendUdpIdentityPacket()) return // kdeconnect+：LAN 探测同样受局域网权限限制
        val tcpServer = tcpServer
        if (tcpServer?.isBound != true) {
            Log.i("LanLinkProvider", "Won't broadcast UDP packet if TCP socket is not ready yet")
            return
        }

        // TODO: In protocol version 8 this packet doesn't need to contain identity info
        //       since it will be exchanged after the socket is encrypted.
        val myDeviceInfo = DeviceHelper.getDeviceInfo(context)
        val identity = myDeviceInfo.toIdentityPacket()
        identity["tcpPort"] = tcpServer.getLocalPort()

        val bytes: ByteArray
        try {
            bytes = identity.serialize().toByteArray(Charsets.UTF_8)
        } catch (e: SerializationException) {
            Log.e("KDE/LanLinkProvider", "Failed to serialize identity packet", e)
            return
        }

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            if (network != null) {
                try {
                    network.bindSocket(socket)
                } catch (e: IOException) {
                    Log.w("LanLinkProvider", "Couldn't bind socket to the network")
                    e.printStackTrace()
                }
            }
            socket.setReuseAddress(true)
            socket.setBroadcast(true)
        } catch (e: SocketException) {
            Log.e("KDE/LanLinkProvider", "Failed to create DatagramSocket", e)
            socket?.closeSafe()
            return
        }

        for (ip in ipList) {
            try {
                socket.send(DatagramPacket(bytes, bytes.size, ip, MIN_PORT))
                //Log.i("KDE/LanLinkProvider","Udp identity packet sent to address "+client);
            } catch (e: IOException) {
                Log.e("KDE/LanLinkProvider", "Sending udp identity packet failed. Invalid address? ($ip)", e)
            }
        }

        socket.closeSafe()
    }

    override fun onStart() {
        //Log.i("KDE/LanLinkProvider", "onStart");
        isStopped = false

        updateCustomDevicesList()
        customDevicePreferences.registerOnSharedPreferenceChangeListener(customDeviceListener)

        setupUdpListener()
        setupTcpListener()

        synchronized(mdnsDiscovery) {
            mdnsDiscovery.startDiscovering()
            if (TrustedNetworkHelper.isTrustedNetwork(context)) {
                mdnsDiscovery.startAnnouncing()
            }
        }

        broadcastUdpIdentityPacket(null)
        customDeviceRetry.start() // kdeconnect+
    }

    override fun onNetworkChange(network: Network?) {
        if (isStopped) return
        if (network != null) customDeviceRetry.onLanNetworkChange() // kdeconnect+：真实网络事件不受官方广播限速影响
        if (SystemClock.elapsedRealtime() < lastBroadcast + DELAY_BETWEEN_BROADCASTS) {
            Log.i("LanLinkProvider", "onNetworkChange: relax cowboy")
            return
        }
        lastBroadcast = SystemClock.elapsedRealtime()

        setupUdpListener()
        setupTcpListener()

        broadcastUdpIdentityPacket(network)
        customDeviceRetry.onNetworkChange() // kdeconnect+：已发送发现广播，重置自定义 IP 重连
        LoopbackPayloadAddress.clear() // kdeconnect+：网络变化后忘记旧载荷地址
        synchronized(mdnsDiscovery) {
            if (TrustedNetworkHelper.isTrustedNetwork(context)) {
                mdnsDiscovery.startAnnouncing() // noop if already announcing
            } else {
                mdnsDiscovery.stopAnnouncing() // noop if already stopped
            }
            mdnsDiscovery.stopDiscovering()
            mdnsDiscovery.startDiscovering()
        }
    }

    override fun onStop() {
        //Log.i("KDE/LanLinkProvider", "onStop");
        isStopped = true
        customDeviceRetry.stop() // kdeconnect+
        customDevicePreferences.unregisterOnSharedPreferenceChangeListener(customDeviceListener)
        synchronized(mdnsDiscovery) {
            mdnsDiscovery.stopAnnouncing()
            mdnsDiscovery.stopDiscovering()
        }
        tcpServer?.closeSafe()
        udpServer?.closeSafe()
        tcpServer = null
        udpServer = null
    }

    override val name: String = "LanLinkProvider"

    override val priority: Int = 20

    val tcpPort: Int?
        get() = tcpServer?.getLocalPort()

    private enum class ConnectionStarted {
        Locally, Remotely
    }

    companion object {
        private const val UDP_PORT: Int = 1716
        private const val MIN_PORT: Int = 1716
        private const val MAX_PORT: Int = 1764
        const val PAYLOAD_TRANSFER_MIN_PORT: Int = 1739

        private const val MAX_IDENTITY_PACKET_SIZE: Int = 1024 * 512
        private const val MAX_UDP_PACKET_SIZE: Int = 1024 * 512

        const val MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE: Long = 1000L

        private const val MAX_RATE_LIMIT_ENTRIES: Int = 255
        private const val MAX_UNPAIRED_CONNECTIONS: Int = 42
        private const val DELAY_BETWEEN_BROADCASTS: Long = 200

        @Throws(IOException::class)
        fun openServerSocketOnFreePort(minPort: Int): ServerSocket {
            var tcpPort = minPort
            while (tcpPort <= MAX_PORT) {
                try {
                    val candidateServer = ServerSocket(tcpPort)
                    Log.i("KDE/LanLink", "Using port $tcpPort")
                    return candidateServer
                } catch (e: IOException) {
                    tcpPort++
                    if (tcpPort == MAX_PORT) {
                        Log.e("KDE/LanLink", "No ports available")
                        throw e //Propagate exception
                    }
                }
            }
            throw RuntimeException("This should not be reachable")
        }
    }
}

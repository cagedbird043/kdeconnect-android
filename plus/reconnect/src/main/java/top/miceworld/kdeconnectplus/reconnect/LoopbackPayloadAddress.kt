package top.miceworld.kdeconnectplus.reconnect

import android.content.Context
import android.util.Log
import org.kde.kdeconnect.extensions.closeSafe
import org.kde.kdeconnect.helpers.security.SslHelper.convertToSslSocket
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket

/** 控制连接经本机代理丢失源地址时，用自定义 IP 和已配对设备的证书寻找载荷发送端。 */
object LoopbackPayloadAddress {
    private const val TAG = "PlusLoopbackPayload"
    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val HANDSHAKE_TIMEOUT_MS = 3_000

    @Volatile
    private var rememberedAddresses = ConcurrentHashMap<String, InetAddress>()

    fun clear() {
        // 替换整张表：清理前已经开始的连接即使稍后成功，也不能把旧网络的地址写回新表。
        rememberedAddresses = ConcurrentHashMap()
    }

    fun connect(context: Context, deviceId: String, port: Int, addresses: List<InetAddress>): SSLSocket {
        val remembered = rememberedAddresses
        val preferred = remembered[deviceId]?.takeIf { it in addresses }
        if (preferred != null) {
            tryConnect(context, deviceId, port, preferred, remembered)?.let { return it }
        }
        for (address in addresses) {
            if (address == preferred) continue
            tryConnect(context, deviceId, port, address, remembered)?.let { return it }
        }
        Log.w(TAG, "All custom payload addresses failed for $deviceId")
        throw IOException("No custom payload address connected to device $deviceId")
    }

    private fun tryConnect(
        context: Context,
        deviceId: String,
        port: Int,
        address: InetAddress,
        remembered: ConcurrentHashMap<String, InetAddress>,
    ): SSLSocket? {
        var socket: Socket = Socket()
        try {
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            val sslSocket = convertToSslSocket(context, socket, deviceId, isDeviceTrusted = true, clientMode = true)
            socket = sslSocket
            val originalTimeout = sslSocket.soTimeout
            sslSocket.soTimeout = HANDSHAKE_TIMEOUT_MS
            try {
                // convertToSslSocket 只配置 TLS；必须在本次尝试里握手，错误设备或代理 EOF 才能继续下一个地址。
                sslSocket.startHandshake()
            } finally {
                sslSocket.soTimeout = originalTimeout
            }
            remembered[deviceId] = address
            Log.i(TAG, "Payload for $deviceId connected via ${address.hostAddress}")
            return sslSocket
        } catch (_: Exception) {
            socket.closeSafe()
            return null
        }
    }
}

package top.miceworld.kdeconnectplus.reconnect

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.helpers.TrustedNetworkHelper
import java.net.InetAddress

/** 只在已配对设备走回环连接时探测局域网；发送时尝试绑定物理网络。 */
class LanLinkPreference(
    context: Context,
    private val isLoopbackLink: (String) -> Boolean,
    private val sendIdentity: (List<InetAddress>, Network) -> Unit,
) {
    private val context = context.applicationContext
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val broadcast = listOf(InetAddress.getByName("255.255.255.255"))

    fun needsRetry(): Boolean =
        KdeConnect.getInstance().devices.values.any { it.isPaired && isLoopbackLink(it.deviceId) } &&
            TrustedNetworkHelper.isTrustedNetwork(context) && lanNetworks().isNotEmpty()

    fun send() = ThreadHelper.execute {
        if (needsRetry()) {
            // 不附带自定义 IP，免得桌面端的设备 ID 限速让 tailnet 抢在 LAN 前面。
            lanNetworks().forEach { sendIdentity(broadcast, it) }
        }
    }

    private fun lanNetworks(): List<Network> = try {
        connectivity.getAllNetworks().filter { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@filter false
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    } catch (e: Exception) {
        Log.w("PlusLanLinkPreference", "Unable to enumerate LAN networks", e)
        emptyList()
    } catch (e: LinkageError) {
        // SDK 与设备的网络 API 不一致时也只暂停探测，不能让主线程上的设备列表回调崩溃。
        Log.w("PlusLanLinkPreference", "Incompatible LAN network API", e)
        emptyList()
    }
}

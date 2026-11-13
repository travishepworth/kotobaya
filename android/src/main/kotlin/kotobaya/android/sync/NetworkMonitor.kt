package kotobaya.android.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Whether the phone has a usable network ("offline" in the sync status), and a callback when it comes
 * back after having been lost (a sync trigger). Server reachability is a separate question
 * ([kotobaya.android.net.ServerReachability]): a network can be up while the server (behind the VPN)
 * is not, which the status shows as "server unreachable", not "offline".
 */
class NetworkMonitor(context: Context) {
    private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var wasOnline: Boolean = isOnline()

    /** True when the default network has internet capability. Unknown (no service) reads as online. */
    fun isOnline(): Boolean {
        val m = cm ?: return true
        val caps = m.getNetworkCapabilities(m.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Calls [onRegained] each time the default network becomes usable after it was not. */
    fun register(onRegained: () -> Unit) {
        val m = cm ?: return
        m.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val regained = online && !wasOnline
                    wasOnline = online
                    if (regained) onRegained()
                }

                override fun onLost(network: Network) {
                    wasOnline = false
                }
            },
        )
    }
}

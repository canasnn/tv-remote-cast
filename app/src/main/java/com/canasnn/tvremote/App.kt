package com.canasnn.tvremote

import android.app.Application
import android.net.Uri
import android.net.wifi.WifiManager
import com.connectsdk.discovery.DiscoveryManager
import com.connectsdk.discovery.provider.CastDiscoveryProvider
import com.connectsdk.service.CastService
import com.canasnn.tvremote.androidLib.AndroidRemoteTv
import com.canasnn.tvremote.utils.Constant
import com.canasnn.tvremote.utils.WebServer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

class App : Application() {

    private fun lanIPv4Address(): String? = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { iface ->
                val name = iface.name.lowercase(Locale.ROOT)
                when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") -> 1
                    name.startsWith("tun") || name.startsWith("rmnet") || name.startsWith("ppp") -> 9
                    else -> 5
                }
            }
            .flatMap { Collections.list(it.inetAddresses) }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) { null }

    var discoveryManager: DiscoveryManager? = null
    var androidRemoteTv: AndroidRemoteTv? = null

    // IME state (keyboard sync from TV)
    var lastImeText: String = ""
    var lastFieldCounter: Int = -1
    var onImeShowCallback: ((String, Int) -> Unit)? = null

    private var webServer: WebServer? = null
    private var webServerIp: String? = null
    @Volatile var webServerPort: Int = Constant.PORT
        private set

    companion object {
        var app: App? = null
        private const val NANO_HTTP_SOCKET_READ_TIMEOUT_MILLIS = 15_000
    }

    override fun onCreate() {
        super.onCreate()
        app = this

        // No analytics or crash reporting: the app sends nothing anywhere.
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())

        com.canasnn.tvremote.utils.TvAppsRepository.init(this)
        com.canasnn.tvremote.receiver.ReceiverManager.init(this)
        com.canasnn.tvremote.cast.CastHub.init(this)
        // Phone files cast before the process was killed keep their tokens (48 h):
        // bring the server back so the TV can retry or resume them.
        Thread({ if (WebServer.hasLiveMedia(this)) ensureLocalMediaServer() }, "media-server-restore").start()

        // Init must happen on main thread (reads application context only)
        DiscoveryManager.init(applicationContext)
        // Start discovery on a background thread to avoid main-thread network ops
        Thread(::discoveryManagerSetup, "discovery-init").start()
    }

    fun discoveryManagerSetup() {
        discoveryManager = DiscoveryManager.getInstance()
        discoveryManager?.registerDeviceService(
            CastService::class.java,
            CastDiscoveryProvider::class.java
        )
        discoveryManager?.start()
    }

    @Synchronized
    fun getOrCreateAndroidRemoteTv(): AndroidRemoteTv {
        if (androidRemoteTv == null) {
            androidRemoteTv = AndroidRemoteTv()
        }
        return androidRemoteTv!!
    }

    /**
     * Starts the media server once, bound to all interfaces so a Wi-Fi IP
     * change doesn't drop registered media; falls back to the next ports when
     * the default one is taken. Returns the LAN address the TV should use.
     */
    @Synchronized
    private fun ensureLocalMediaServer(): String? {
        val ip = getIPAddress(true) ?: return null
        if (webServer?.isAlive == true) return ip
        runCatching { webServer?.stop() }
        for (port in Constant.PORT until Constant.PORT + 10) {
            val server = WebServer(context = applicationContext, host = null, port = port)
            try {
                server.start(NANO_HTTP_SOCKET_READ_TIMEOUT_MILLIS, false)
                webServer = server
                webServerPort = port
                Timber.d("Local media server started on port $port")
                return ip
            } catch (e: Exception) {
                Timber.w("Media server port $port unavailable: ${e.message}")
            }
        }
        Timber.e("Failed to start local media server")
        return null
    }

    fun buildCastUrl(uri: Uri, mimeType: String, displayName: String): String? {
        val host = ensureLocalMediaServer() ?: return null
        val path = webServer?.registerMedia(uri, mimeType, displayName) ?: return null
        return "http://$host:$webServerPort$path"
    }

    /** Serves generated text (e.g. a normalized subtitle) to the TV. */
    fun buildTextUrl(text: String, mimeType: String, fileName: String): String? {
        val host = ensureLocalMediaServer() ?: return null
        val path = webServer?.registerText(text, mimeType, fileName) ?: return null
        return "http://$host:$webServerPort$path"
    }

    fun stopLocalMediaServer() {
        try { webServer?.stop() } catch (_: Exception) {}
        webServer?.clearRegisteredMedia()
        webServer = null
        webServerIp = null
    }

    private fun getIPAddress(useIPv4: Boolean): String? {
        // The TV must reach us: prefer the Wi-Fi/Ethernet LAN address over
        // VPN, cellular or hotspot interfaces.
        if (useIPv4) lanIPv4Address()?.let { return it }
        return try {
            val interfaces: List<NetworkInterface> =
                Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs: List<InetAddress> = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress ?: continue
                        val isIPv4 = sAddr.indexOf(':') < 0
                        if (useIPv4) {
                            if (isIPv4) return sAddr
                        } else {
                            if (!isIPv4) {
                                val delim = sAddr.indexOf('%')
                                return if (delim < 0) sAddr.uppercase(Locale.getDefault())
                                else sAddr.substring(0, delim).uppercase(Locale.getDefault())
                            }
                        }
                    }
                }
            }
            null
        } catch (_: Exception) { null }
    }
}

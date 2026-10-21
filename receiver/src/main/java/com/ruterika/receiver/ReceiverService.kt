package com.ruterika.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ruterika.protocol.Apps
import com.ruterika.protocol.ErrorMessage
import com.ruterika.protocol.GetApps
import com.ruterika.protocol.GetStatus
import com.ruterika.protocol.Hello
import com.ruterika.protocol.Load
import com.ruterika.protocol.LoadResult
import com.ruterika.protocol.Message
import com.ruterika.protocol.Ping
import com.ruterika.protocol.Pong
import com.ruterika.protocol.Protocol
import com.ruterika.protocol.SetSubtitleStyle
import com.ruterika.protocol.Status
import com.ruterika.protocol.Stop
import com.ruterika.protocol.Welcome
import com.ruterika.protocol.SetVolume
import com.ruterika.protocol.AdjustVolume
import com.ruterika.protocol.SetMuted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import java.util.concurrent.Executors

/**
 * Always-on foreground service: the phone's entry point on the TV.
 *
 * Started on boot, on app update and whenever the process starts (deep link
 * from the phone). It hosts the control WebSocket, answers app list requests
 * and forwards playback commands to [PlayerActivity] through [PlayerHub].
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class ReceiverService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = Executors.newSingleThreadExecutor { Thread(it, "receiver-io") }
    private lateinit var prefs: ReceiverPrefs
    private lateinit var catalog: AppCatalog
    private lateinit var volume: VolumeController
    private var server: ControlServer? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = catalog.invalidate()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = ReceiverPrefs(this)
        catalog = AppCatalog(this)
        volume = VolumeController(this)
        goForeground()

        ContextCompat.registerReceiver(
            this,
            packageChanges,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        io.execute(::startServer)

        // Push player status to the phones (at most every 500 ms).
        scope.launch {
            PlayerHub.status.sample(STATUS_SAMPLE_MS).collect { status ->
                server?.broadcastAuthenticated(Status(withVolume(status)))
            }
        }
        scope.launch {
            PlayerHub.events.collect { event -> server?.broadcastAuthenticated(event) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(packageChanges) }
        unregisterNsd()
        val s = server
        server = null
        io.execute { s?.shutdown() }
        io.shutdown()
        super.onDestroy()
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_MIN)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_receiver)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    // ── Network ─────────────────────────────────────────────────────────────

    private fun startServer() {
        for (port in Protocol.DEFAULT_PORT until Protocol.DEFAULT_PORT + Protocol.PORT_RANGE) {
            val candidate = ControlServer(
                port = port,
                isTokenAllowed = prefs::isTokenAllowed,
                onAuthenticated = ::onAuthenticated,
                onMessage = ::onClientMessage
            )
            try {
                // Probe the port first; WebSocketServer.start() fails asynchronously.
                java.net.ServerSocket().use { probe ->
                    probe.reuseAddress = true
                    probe.bind(java.net.InetSocketAddress(port))
                }
                candidate.start()
                server = candidate
                registerNsd(port)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Port $port unavailable: ${e.message}")
            }
        }
        Log.e(TAG, "No free control port")
    }

    private fun onAuthenticated(conn: WebSocket, hello: Hello) {
        Log.i(TAG, "Phone connected: ${hello.clientName}")
        server?.send(
            conn,
            Welcome(
                deviceName = deviceName(),
                version = Protocol.VERSION,
                appVersion = BuildConfig.VERSION_NAME,
                sdkInt = Build.VERSION.SDK_INT,
                canSelfLaunch = canSelfLaunch()
            )
        )
        server?.send(conn, Status(withVolume(PlayerHub.status.value)))
    }

    private fun withVolume(status: com.ruterika.protocol.PlaybackStatus) =
        runCatching { status.copy(volume = volume.level, muted = volume.muted) }.getOrDefault(status)

    /** Volume changes don't go through the player; report them right away. */
    private fun sendStatusSoon() {
        scope.launch {
            kotlinx.coroutines.delay(150)
            server?.broadcastAuthenticated(Status(withVolume(PlayerHub.status.value)))
        }
    }

    /** Runs on the WebSocket thread; heavy work goes to [io]. */
    private fun onClientMessage(conn: WebSocket, message: Message) {
        when (message) {
            is GetApps -> io.execute {
                val apps = runCatching { catalog.apps(message.withIcons) }.getOrElse { emptyList() }
                server?.send(conn, Apps(apps))
            }
            is Load -> handleLoad(conn, message)
            is GetStatus -> server?.send(conn, Status(withVolume(PlayerHub.status.value)))
            is SetVolume -> { volume.set(message.level); sendStatusSoon() }
            is AdjustVolume -> { volume.adjust(message.direction); sendStatusSoon() }
            is SetMuted -> { volume.setMuted(message.muted); sendStatusSoon() }
            is Ping -> server?.send(conn, Pong(message.time))
            is SetSubtitleStyle -> {
                prefs.subtitleStyle = message.style
                PlayerHub.sendCommand(message)
            }
            is Stop -> PlayerHub.sendCommand(message)
            else -> if (!PlayerHub.sendCommand(message)) {
                server?.send(conn, ErrorMessage("no_player", "Nothing is playing"))
            }
        }
    }

    private fun handleLoad(conn: WebSocket, load: Load) {
        if (load.items.isEmpty()) {
            server?.send(conn, LoadResult(load.sessionId, accepted = false, needsLaunch = false))
            return
        }
        if (PlayerHub.submitLoad(load)) {
            server?.send(conn, LoadResult(load.sessionId, accepted = true, needsLaunch = false))
            return
        }
        // Player is not on screen. Background activity starts are blocked on
        // Android 10+ unless we may draw overlays; otherwise the phone opens
        // the player through the remote (play deep link).
        val launched = canSelfLaunch() && runCatching {
            startActivity(PlayerActivity.intent(this, load.sessionId))
        }.isSuccess
        server?.send(conn, LoadResult(load.sessionId, accepted = true, needsLaunch = !launched))
    }

    private fun canSelfLaunch(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(this)

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    private fun registerNsd(port: Int) {
        val nsd = getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = deviceName()
            serviceType = Protocol.NSD_SERVICE_TYPE
            setPort(port)
            setAttribute("v", Protocol.VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        runCatching {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdListener = listener
        }
    }

    private fun unregisterNsd() {
        val listener = nsdListener ?: return
        nsdListener = null
        runCatching { getSystemService(NsdManager::class.java)?.unregisterService(listener) }
    }

    companion object {
        private const val TAG = "ReceiverService"
        private const val CHANNEL_ID = "receiver"
        private const val NOTIFICATION_ID = 1
        private const val STATUS_SAMPLE_MS = 500L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java))
            } catch (e: Exception) {
                // Background start not allowed right now (e.g. process restarted
                // by the system); START_STICKY or the next deep link brings it up.
                Log.w(TAG, "Could not start service: ${e.message}")
            }
        }
    }
}

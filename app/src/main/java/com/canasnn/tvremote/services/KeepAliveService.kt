package com.canasnn.tvremote.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.canasnn.tvremote.App
import com.canasnn.tvremote.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class KeepAliveService : Service() {

    private var deviceName: String = "TV"
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopHeartbeat()
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        intent?.getStringExtra(EXTRA_DEVICE_NAME)?.let {
            if (it.isNotBlank()) deviceName = it
        }

        val notification = buildNotification(deviceName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireLocks()
        startHeartbeat()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AndroidTVRemote:KeepAliveWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(12 * 60 * 60 * 1000L) // 12 hours safe timeout
                }
                Timber.d("[KeepAliveService] Partial wake lock acquired")
            }
        } catch (e: Exception) {
            Timber.e(e, "[KeepAliveService] Failed to acquire wake lock")
        }

        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wifiManager?.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                        "AndroidTVRemote:KeepAliveWifiLock"
                    )
                } else {
                    @Suppress("DEPRECATION")
                    wifiManager?.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "AndroidTVRemote:KeepAliveWifiLock"
                    )
                }?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Timber.d("[KeepAliveService] Wi-Fi lock acquired")
            }
        } catch (e: Exception) {
            Timber.e(e, "[KeepAliveService] Failed to acquire Wi-Fi lock")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Timber.d("[KeepAliveService] Wake lock released")
            }
            wakeLock = null
        } catch (e: Exception) {
            Timber.w(e, "[KeepAliveService] Exception releasing wake lock")
        }

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Timber.d("[KeepAliveService] Wi-Fi lock released")
            }
            wifiLock = null
        } catch (e: Exception) {
            Timber.w(e, "[KeepAliveService] Exception releasing Wi-Fi lock")
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            Timber.d("[KeepAliveService] Background heartbeat loop active (interval ${HEARTBEAT_INTERVAL_MS}ms)")
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                try {
                    val app = App.app
                    val remote = app?.androidRemoteTv
                    if (remote != null && remote.isSocketAlive) {
                        val ok = remote.sendPing()
                        if (ok) {
                            Timber.d("[KeepAliveService] Heartbeat ping sent successfully")
                        } else {
                            Timber.w("[KeepAliveService] Heartbeat ping failed — socket closed")
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "[KeepAliveService] Heartbeat loop encountered error")
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.i("[KeepAliveService] App task terminated by user — stopping service")
        stopHeartbeat()
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHeartbeat()
        serviceScope.cancel()
        releaseLocks()
        Timber.i("[KeepAliveService] Service destroyed cleanly")
    }

    private fun buildNotification(deviceName: String): Notification {
        createChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("TV Remote & Cast")
            .setContentText("Connected to $deviceName")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Device Connection Standby",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.canasnn.tvremote.action.START_KEEPALIVE"
        const val ACTION_STOP = "com.canasnn.tvremote.action.STOP_KEEPALIVE"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        private const val CHANNEL_ID = "connection_keep_alive"
        private const val NOTIFICATION_ID = 2002
        private const val HEARTBEAT_INTERVAL_MS = 15_000L

        fun start(context: Context, deviceName: String) {
            val intent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_NAME, deviceName)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Ignore background start restriction errors if process restrictions apply
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }
}

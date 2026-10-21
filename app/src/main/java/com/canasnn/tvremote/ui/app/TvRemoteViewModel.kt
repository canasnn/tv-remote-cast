package com.canasnn.tvremote.ui.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.connectsdk.core.MediaInfo
import com.connectsdk.device.ConnectableDevice
import com.connectsdk.device.ConnectableDeviceListener
import com.connectsdk.discovery.DiscoveryManager
import com.connectsdk.discovery.DiscoveryManagerListener
import com.connectsdk.service.CastService as ConnectSdkCastService
import com.connectsdk.service.DeviceService
import com.connectsdk.service.capability.MediaControl
import com.connectsdk.service.capability.MediaPlayer
import com.connectsdk.service.capability.VolumeControl
import com.connectsdk.service.capability.listeners.ResponseListener
import com.connectsdk.service.command.ServiceCommandError
import com.connectsdk.service.sessions.LaunchSession
import com.canasnn.tvremote.App
import com.canasnn.tvremote.receiver.ReceiverClient
import com.canasnn.tvremote.receiver.ReceiverManager
import com.canasnn.tvremote.services.KeepAliveService
import com.canasnn.tvremote.utils.Constant
import com.canasnn.tvremote.utils.AtvNsdDiscovery
import com.canasnn.tvremote.utils.TvApp
import com.canasnn.tvremote.utils.TvAppsRepository
import com.canasnn.tvremote.androidLib.AndroidRemoteTv
import com.canasnn.tvremote.androidLib.AndroidTvListener
import com.canasnn.tvremote.androidLib.remote.Remotemessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class CastPlaybackUiState(
    val activeMedia: MediaItemUi? = null,
    val isCasting: Boolean = false,
    val isBusy: Boolean = false,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val progressFraction: Float = 0f
)

data class TvRemoteUiState(
    val discoveredDevices: List<DeviceUiModel> = emptyList(),
    val connectedDevice: DeviceUiModel? = null,
    val isConnecting: Boolean = false,
    val pairingRequired: Boolean = false,
    val pairingError: String? = null,
    val pairingRequestId: Int = 0,
    val statusMessage: String? = null,
    val isError: Boolean = false,
    val volumeFraction: Float = 0.5f,
    val volumeLevel: Int? = null,
    val isMuted: Boolean = false,
    /** True once the TV's real volume is known (until then the level is a guess). */
    val volumeKnown: Boolean = false,
    val isVoiceActive: Boolean = false,
    val cast: CastPlaybackUiState = CastPlaybackUiState(),
    /** Text of the TV's focused text box, as the TV reports it. */
    val tvText: String = "",
    /** The TV's last text-box report counter (-1 = no box reported yet). */
    val tvTextField: Int = -1,
    /** Goes up when the TV's text box changes by itself (another box, or edited on the TV). */
    val tvTextRevision: Int = 0,

)

class TvRemoteViewModel(application: Application) : AndroidViewModel(application),
    DiscoveryManagerListener {

    private val app = getApplication<App>()
    private val prefs = application.getSharedPreferences(Constant.PREFS_NAME, Context.MODE_PRIVATE)
    private val discoveryManager = DiscoveryManager.getInstance().also { manager ->
        if (app.discoveryManager == null) {
            app.discoveryManager = manager
        }
    }
    private val discoveredDevices = linkedMapOf<String, ConnectableDevice>()
    private val reachableHosts = ConcurrentHashMap.newKeySet<String>()
    private val validatingHosts = ConcurrentHashMap.newKeySet<String>()

    // ── Direct Android TV discovery (no Chromecast needed) ──────────────────
    // TVs found via the remote-control mDNS record (_androidtvremote2._tcp),
    // keyed by IP. These have no ConnectSDK services; the remote protocol
    // (port 6466) is all we need for the remote.
    private val nsdDevices = ConcurrentHashMap<String, ConnectableDevice>()
    private val atvDiscovery = AtvNsdDiscovery(application) { host, name ->
        viewModelScope.launch(Dispatchers.Main) { addNsdDevice(host, name) }
    }

    private fun addNsdDevice(host: String, name: String) {
        val alreadyKnown = synchronized(discoveredDevices) {
            discoveredDevices.values.any { it.ipAddress == host && it.id != nsdId(host) }
        }
        if (alreadyKnown) return  // Cast discovery already reported this TV
        val device = nsdDevices.getOrPut(host) {
            ConnectableDevice(host, name, "Android TV", null).apply { id = nsdId(host) }
        }
        if (!name.isBlank()) device.friendlyName = name
        synchronized(discoveredDevices) { discoveredDevices[device.id] = device }
        validateReachability(device)
        publishDeviceSnapshot()
        scheduleAutoReconnect(delayMs = 300L)
    }

    private fun nsdId(host: String) = "atv-nsd-$host"

    private val _uiState = MutableStateFlow(TvRemoteUiState())
    val uiState: StateFlow<TvRemoteUiState> = _uiState.asStateFlow()

    private val _navigateToDiscoveryTrigger = MutableStateFlow(false)
    val navigateToDiscoveryTrigger: StateFlow<Boolean> = _navigateToDiscoveryTrigger.asStateFlow()

    fun resetDiscoveryTrigger() {
        _navigateToDiscoveryTrigger.value = false
    }

    fun checkConnectionGuard(): Boolean {
        val connected = uiState.value.connectedDevice != null
        if (!connected) {
            Timber.w("[ConnectionGuard] Action attempted but device is disconnected. Triggering discovery.")
            _navigateToDiscoveryTrigger.value = true
        }
        return connected
    }

    private var pendingDeviceId: String? = null
    private var activeDeviceId: String? = null
    private var currentConnectableListener: ConnectableDeviceListener? = null
    private var playbackPollJob: Job? = null
    private var heartbeatJob: Job? = null
    private var mediaControl: MediaControl? = null
    // Casting through our own TV receiver (instead of ConnectSDK/Cast).
    @Volatile private var receiverCasting = false
    private var receiverSawPlayer = false
    private var launchSession: LaunchSession? = null
    private var autoReconnectJob: Job? = null
    private var autoReconnectScheduleJob: Job? = null
    private var autoReconnectAttempts = 0
    /** Watchdog that cancels a stuck auto-reconnect after AUTO_RECONNECT_TIMEOUT_MS. */
    private var connectionWatchdogJob: Job? = null
    private var connectionGeneration = 0

    // Thread-safe reconnect guard — was a plain Boolean before (race condition)
    private val autoReconnectInFlight = AtomicBoolean(false)

    private var manualDisconnectUntilMs = 0L
    private var imeFieldCounter = 0
    private var imeCounter = 0

    /**
     * Keyboard edits for the TV, sent one after another on a single worker so
     * fast typing can't reorder them. Each edit carries the whole text of the
     * TV's text box (that's what the protocol expects); queued edits collapse
     * into the newest one, Enter keeps its place after them.
     */
    private sealed interface ImeCommand {
        data class SetText(val text: String) : ImeCommand
        data object Enter : ImeCommand
    }
    private val imeCommands = kotlinx.coroutines.channels.Channel<ImeCommand>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    /** What we believe the TV's text box holds (TV reports reset it). */
    @Volatile private var tvTypedText = ""
    /** When the phone last typed; TV reports right after that are echoes of our own typing. */
    @Volatile private var lastImeSendAt = 0L

    // ── Network callback — detects Wi-Fi reconnect and triggers auto-reconnect ──
    private val connectivityManager =
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Timber.i("[Connection] Network available — scheduling auto-reconnect")
            scheduleAutoReconnect(delayMs = 1000L)
        }
        override fun onLost(network: Network) {
            Timber.w("[Connection] Network lost")
        }
    }

    private var lastBackgroundTime = 0L

    init {
        startImeWorker()
        discoveryManager?.addListener(this)
        discoveryManager?.start()
        atvDiscovery.start()
        TvAppsRepository.init(application)
        // Seed the last paired TV right away so auto-reconnect can start
        // without waiting for any network discovery.
        val savedHost = prefs.getString(Constant.HOST, null)
        if (!savedHost.isNullOrBlank() && prefs.getBoolean(Constant.PIN, false)) {
            nsdDevices.getOrPut(savedHost) {
                ConnectableDevice(savedHost, prefs.getString(PREF_SAVED_NAME, null) ?: "Android TV", "Android TV", null)
                    .apply { id = nsdId(savedHost) }
            }
            refreshTvApps(savedHost)
        }
        observeReceiverStatus()
        refreshDiscoveredDevices()
        if (isWifiConnected()) {
            scheduleAutoReconnect(delayMs = 800L)
        }
        registerNetworkCallback()

        // Register process lifecycle observer for background/foreground socket stale checks
        viewModelScope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        lastBackgroundTime = System.currentTimeMillis()
                        Timber.i("[Lifecycle] App entered background at $lastBackgroundTime")
                    }
                    Lifecycle.Event.ON_START -> {
                        onAppForegrounded()
                    }
                    else -> {}
                }
            })
        }
    }

    /** Returns true if the device has an active Wi-Fi connection. */
    private fun isWifiConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun registerNetworkCallback() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Timber.e(e, "[Connection] Failed to register network callback")
        }
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────────
    /**
     * Starts a coroutine that pings the TV every [HEARTBEAT_INTERVAL_MS] ms.
     * If the ping fails the socket is dead — triggers disconnect handling.
     * This is the primary mechanism that detects silent disconnects (TV sleep,
     * idle TCP timeout, Wi-Fi glitch) before the next button press.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch(Dispatchers.IO) {
            Timber.d("[Heartbeat] Started (interval ${HEARTBEAT_INTERVAL_MS}ms)")
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                val remote = app.androidRemoteTv ?: break
                val alive = remote.isSocketAlive && remote.sendPing()
                if (!alive) {
                    Timber.w("[Heartbeat] Ping failed or socket not alive — ensuring connected")
                    remote.ensureConnected()
                }
            }
            Timber.d("[Heartbeat] Stopped")
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun refreshDiscoveredDevices() {
        synchronized(discoveredDevices) {
            discoveredDevices.clear()
            discoveryManager?.allDevices?.values?.forEach { device ->
                if (device.isChromecastServiceDevice()) {
                    discoveredDevices[device.id] = device
                    validateReachability(device)
                }
            }
            nsdDevices.values.forEach { device ->
                if (discoveredDevices.values.none { it.ipAddress == device.ipAddress }) {
                    discoveredDevices[device.id] = device
                    validateReachability(device)
                }
            }
        }
        atvDiscovery.restart()
        publishDeviceSnapshot()
        scheduleAutoReconnect(delayMs = 500L)
    }

    fun clearStatus() {
        _uiState.update { it.copy(statusMessage = null, isError = false) }
    }

    fun connectToDevice(deviceId: String) {
        connectToDeviceInternal(deviceId, userInitiated = true)
    }

    private fun connectToDeviceInternal(deviceId: String, userInitiated: Boolean) {
        val device = synchronized(discoveredDevices) { discoveredDevices[deviceId] } ?: return
        if (userInitiated) {
            autoReconnectScheduleJob?.cancel()
            autoReconnectJob?.cancel()
            connectionWatchdogJob?.cancel()
            autoReconnectAttempts = 0
            manualDisconnectUntilMs = 0L
            prefs.edit { putBoolean(Constant.PREF_MANUAL_DISCONNECT, false) }
        }
        if (!device.isChromecastServiceDevice()) {
            synchronized(discoveredDevices) { discoveredDevices.remove(deviceId) }
            publishDeviceSnapshot()
            showStatus("Only Chromecast service devices can be connected.", isError = true)
            return
        }
        if (_uiState.value.isConnecting && pendingDeviceId != deviceId) return

        if (activeDeviceId != null && activeDeviceId != deviceId) {
            disconnectCurrentDeviceInternal(manual = false)
        }

        val host = device.ipAddress
        if (host.isNullOrBlank()) {
            showStatus("This TV does not expose a valid IP address.", isError = true)
            return
        }

        pendingDeviceId = deviceId
        _uiState.update {
            it.copy(
                isConnecting = true,
                pairingError = null,
                statusMessage = "Connecting to ${device.friendlyName}...",
                isError = false
            )
        }
        publishDeviceSnapshot()

        val isSavedPairing = prefs.getBoolean(Constant.PIN, false)
        val savedHost = prefs.getString(Constant.HOST, null)
        if (isSavedPairing && savedHost == host) {
            Log.d(TAG, "Attempting to reconnect to saved device")
            reconnectRemote(device, host, allowFreshFallback = true)
        } else {
            Log.d(TAG, "Attempting to connect to new device")
            connectRemote(device, host)
        }
    }

    fun disconnectCurrentDevice() {
        disconnectCurrentDeviceInternal(manual = true)
    }

    private fun disconnectCurrentDeviceInternal(manual: Boolean) {
        connectionGeneration += 1
        prefs.edit { putBoolean(Constant.PREF_MANUAL_DISCONNECT, manual) }
        if (manual) {
            autoReconnectScheduleJob?.cancel()
            autoReconnectJob?.cancel()
            connectionWatchdogJob?.cancel()
            connectionWatchdogJob = null
            autoReconnectInFlight.set(false)
            autoReconnectAttempts = 0
            manualDisconnectUntilMs = System.currentTimeMillis() + MANUAL_DISCONNECT_COOLDOWN_MS
        }

        stopHeartbeat()
        KeepAliveService.stop(app)
        ReceiverManager.onTvDisconnected()
        receiverCasting = false
        app.androidRemoteTv?.abort()
        app.androidRemoteTv = null
        playbackPollJob?.cancel()
        playbackPollJob = null
        mediaControl = null
        launchSession = null
        currentConnectableDevice()?.disconnect()
        Constant.connectableDevice = null
        activeDeviceId = null
        pendingDeviceId = null
        imeFieldCounter = 0
        imeCounter = 0
        app.lastImeText = ""
        app.lastFieldCounter = -1
        _uiState.update {
            it.copy(
                connectedDevice = null,
                isConnecting = false,
                pairingRequired = false,
                pairingError = null,
                isVoiceActive = false,
                cast = CastPlaybackUiState(),
                statusMessage = "Disconnected",
                isError = false
            )
        }
        Constant.isConnected.value = false
        publishDeviceSnapshot()
        Timber.i("[Connection] Disconnected (manual=$manual)")
    }

    fun submitPairingCode(code: String) {
        if (code.length != 6) {
            showPairingError("Enter the 6-digit code shown on your TV.")
            return
        }
        try {
            app.androidRemoteTv?.sendSecret(code.uppercase())
            _uiState.update {
                it.copy(
                    pairingRequired = false,
                    pairingError = null,
                    statusMessage = "Pairing with your TV...",
                    isError = false
                )
            }
        } catch (error: Exception) {
            handleWrongPairingCode()
        }
    }

    fun cancelPairing() {
        connectionGeneration += 1
        app.androidRemoteTv?.abort()
        pendingDeviceId = null
        _uiState.update {
            it.copy(
                pairingRequired = false,
                pairingError = null,
                isConnecting = false,
                statusMessage = "Pairing cancelled",
                isError = false
            )
        }
        publishDeviceSnapshot()
    }

    fun sendKey(keyCode: Remotemessage.RemoteKeyCode) {
        if (!checkConnectionGuard()) return
        when (keyCode) {
            Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_MUTE -> {
                _uiState.update { it.copy(isMuted = !it.isMuted) }
            }
            Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_UP -> {
                _uiState.update {
                    val current = it.volumeLevel ?: 50
                    it.copy(volumeLevel = (current + 5).coerceAtMost(100), isMuted = false)
                }
            }
            Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_DOWN -> {
                _uiState.update {
                    val current = it.volumeLevel ?: 50
                    it.copy(volumeLevel = (current - 5).coerceAtLeast(0))
                }
            }
            else -> Unit
        }
        viewModelScope.launch(Dispatchers.IO) {
            app.androidRemoteTv?.sendCommand(keyCode, Remotemessage.RemoteDirection.SHORT)
        }
    }

    fun sendText(text: String, submit: Boolean) {
        if (!checkConnectionGuard()) return
        if (text.isBlank() && !submit) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sendImeTextUpdate(text)
                if (submit) {
                    app.androidRemoteTv?.sendImeEnter()
                }
            } catch (error: Exception) {
                Timber.e(error, "[Keyboard] Failed to send text")
            }
        }
    }

    /** The phone keyboard's whole text: the TV's text box becomes exactly this. */
    fun setKeyboardText(text: String) {
        if (!checkConnectionGuard()) return
        app.lastImeText = text
        lastImeSendAt = android.os.SystemClock.elapsedRealtime()
        _uiState.update { it.copy(tvText = text) }
        imeCommands.trySend(ImeCommand.SetText(text))
    }

    fun sendKeyboardEnter() {
        if (!checkConnectionGuard()) return
        imeCommands.trySend(ImeCommand.Enter)
    }

    /**
     * The TV turns Backspaces and digits into key events, which reach the text box after
     * plain characters sent right behind them. Waiting after those keeps the order.
     */
    private suspend fun sendTypedDelta(remote: com.canasnn.tvremote.androidLib.AndroidRemoteTv, deletes: Int, insert: String) {
        lastImeSendAt = android.os.SystemClock.elapsedRealtime()
        if (deletes > 0) {
            remote.sendImeDirectEdit(deletes, "")
            keyEventsSettledAt(deletes)
        }
        var start = 0
        while (start < insert.length) {
            val digits = insert[start].isDigit()
            var end = start + 1
            while (end < insert.length && insert[end].isDigit() == digits) end++
            // Plain characters overtake key events still on their way: let those land first.
            val wait = keyEventsSettleAt - android.os.SystemClock.elapsedRealtime()
            if (wait > 0) delay(wait)
            remote.sendImeDirectEdit(0, insert.substring(start, end))
            if (digits) keyEventsSettledAt(end - start)
            start = end
        }
    }

    /** When Backspaces and digits sent so far have reached the TV's text box (estimate). */
    @Volatile private var keyEventsSettleAt = 0L

    private fun keyEventsSettledAt(keys: Int) {
        // TVs take ~30-50 ms per key event; measured on a MediaTek Google TV.
        keyEventsSettleAt = android.os.SystemClock.elapsedRealtime() + (150L + 50L * keys).coerceAtMost(2500L)
    }

    private fun startImeWorker() {
        viewModelScope.launch(Dispatchers.IO) {
            var pending: ImeCommand? = null
            while (isActive) {
                val command = pending ?: imeCommands.receive()
                pending = null
                try {
                    when (command) {
                        is ImeCommand.SetText -> {
                            var latest = command
                            while (true) {
                                val next = imeCommands.tryReceive().getOrNull() ?: break
                                if (next is ImeCommand.SetText) latest = next else { pending = next; break }
                            }
                            val remote = app.androidRemoteTv ?: continue
                            // Send only the difference: Backspaces for what changed at the end, then the new characters.
                            val before = tvTypedText
                            val common = before.commonPrefixWith(latest.text).length
                            val deletes = before.length - common
                            val insert = latest.text.substring(common)
                            sendTypedDelta(remote, deletes, insert)
                            tvTypedText = latest.text
                            lastImeSendAt = android.os.SystemClock.elapsedRealtime()
                            Timber.d("[Keyboard] -$deletes +${insert.length} character(s)")
                        }
                        ImeCommand.Enter -> app.androidRemoteTv?.sendImeEnter()
                    }
                } catch (error: Exception) {
                    Timber.e(error, "[Keyboard] Failed to send keyboard input")
                }
            }
        }
    }

    /**
     * Launch a built-in quick app by name, or fall back to a custom shortcut
     * with a matching label (case-insensitive).
     */
    /** Ask our TV receiver for the installed apps (no ADB). */
    fun refreshTvApps(host: String? = prefs.getString(Constant.HOST, null)) {
        if (host.isNullOrBlank()) return
        ReceiverClient.send(com.ruterika.protocol.GetApps(withIcons = false))
    }

    fun launchQuickApp(appName: String) {
        // TV apps (from the receiver's list): opened by the receiver.
        if (appName.startsWith(TvApp.URI_PREFIX)) {
            if (!checkConnectionGuard()) return
            val component = appName.removePrefix(TvApp.URI_PREFIX)
            if (!ReceiverManager.launchApp(component)) {
                showStatus("Install the receiver app on your TV to open TV apps.", isError = true)
            }
            return
        }
        if (!checkConnectionGuard()) return
        // If appName is already a direct URL or Intent URI (e.g. from custom shortcut)
        if (appName.startsWith("https://", ignoreCase = true) ||
            appName.startsWith("http://", ignoreCase = true) ||
            appName.startsWith("intent://", ignoreCase = true) ||
            appName.startsWith("market://", ignoreCase = true)
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                Timber.i("[AppLink] Launching direct URI: $appName")
                app.androidRemoteTv?.sendAppLink(appName)
            }
            return
        }

        val appUrl = builtInAppUrl(appName)
        if (appUrl != null) {
            viewModelScope.launch(Dispatchers.IO) {
                Timber.i("[AppLink] Launching built-in app: $appName -> $appUrl")
                app.androidRemoteTv?.sendAppLink(appUrl)
            }
            return
        }
        // Fall back to custom shortcuts stored in DataStore
        viewModelScope.launch(Dispatchers.IO) {
            val custom = ShortcutsDataStore.loadShortcuts(app)
            val match = custom.firstOrNull {
                it.id == appName ||
                it.label.equals(appName, ignoreCase = true) ||
                it.intentUri.equals(appName, ignoreCase = true)
            }
            if (match != null) {
                Timber.i("[AppLink] Launching custom shortcut: ${match.label} -> ${match.intentUri}")
                app.androidRemoteTv?.sendAppLink(match.intentUri)
            } else {
                Timber.w("[AppLink] No shortcut found for: $appName")
                showStatus("This shortcut is not mapped yet.", isError = true)
            }
        }
    }

    /**
     * Launch a custom shortcut directly by its Intent URI.
     * Used from the ManageShortcutsScreen test button.
     */
    fun launchCustomShortcut(intentUri: String) {
        if (!checkConnectionGuard()) return
        viewModelScope.launch(Dispatchers.IO) {
            Timber.i("[AppLink] Launching custom shortcut URI: $intentUri")
            app.androidRemoteTv?.sendAppLink(intentUri)
        }
    }

    fun toggleVoice() {
        if (!checkConnectionGuard()) return
        val remote = app.androidRemoteTv ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val isActive = remote.isVoiceActive
                if (isActive) {
                    remote.stopVoice()
                } else {
                    remote.setVoiceAutoStopCallback {
                        _uiState.update { it.copy(isVoiceActive = false) }
                    }
                    remote.startVoice()
                }
                _uiState.update { it.copy(isVoiceActive = remote.isVoiceActive) }
            } catch (error: Exception) {
                showStatus(error.message ?: "Voice search is not available right now.", isError = true)
            }
        }
    }

    fun volumeUp() {
        if (!checkConnectionGuard()) return
        sendKey(Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_UP)
        _uiState.update {
            val next = (it.volumeFraction + 0.05f).coerceAtMost(1f)
            it.copy(volumeFraction = next)
        }
    }

    fun volumeDown() {
        if (!checkConnectionGuard()) return
        sendKey(Remotemessage.RemoteKeyCode.KEYCODE_VOLUME_DOWN)
        _uiState.update {
            val next = (it.volumeFraction - 0.05f).coerceAtLeast(0f)
            it.copy(volumeFraction = next)
        }
    }

    fun setVolume(targetFraction: Float) {
        if (!checkConnectionGuard()) return
        val connectableDevice = currentConnectableDevice() ?: return
        val clamped = targetFraction.coerceIn(0f, 1f)
        _uiState.update { it.copy(volumeFraction = clamped) }
        connectableDevice.volumeControl?.setVolume(
            clamped,
            object : ResponseListener<Any> {
                override fun onSuccess(response: Any?) = Unit
                override fun onError(error: ServiceCommandError?) {
                    Timber.w("[Volume] setVolume failed: ${error?.message}")
                }
            }
        )
    }

    fun castMedia(mediaItem: MediaItemUi) {
        // Immediately populate activeMedia in state so CastPlayerScreen displays media details instantly
        _uiState.update {
            it.copy(cast = it.cast.copy(activeMedia = mediaItem, isBusy = true))
        }
        viewModelScope.launch {
            val isRemoteUrl = mediaItem.uri.startsWith("http://", ignoreCase = true) ||
                mediaItem.uri.startsWith("https://", ignoreCase = true)
            // Phone files keep their content:// uri so Recent media stays playable;
            // CastHub serves them through the local server when casting.
            val castUrl = mediaItem.uri
            val item = com.ruterika.castcore.library.SavedMedia(
                url = castUrl,
                title = mediaItem.title,
                mimeType = mediaItem.mimeType.takeUnless { it.endsWith("/*") }
                    ?: when (mediaItem.kind) {
                        MediaKind.Audio -> "audio/*"
                        MediaKind.Photo -> "image/jpeg"
                        else -> null
                    },
                posterUrl = mediaItem.thumbnailUri?.takeIf { it.startsWith("http") },
                durationMs = mediaItem.durationMs
            )
            when (com.canasnn.tvremote.cast.CastHub.play(listOf(item), startPositionMs = if (isRemoteUrl) -1L else 0L)) {
                ReceiverManager.PlayOutcome.Started -> onReceiverCastStarted(mediaItem)
                ReceiverManager.PlayOutcome.NotInstalled -> {
                    if (currentConnectableDevice()?.getCapability(MediaPlayer::class.java) != null) {
                        castViaConnectSdk(mediaItem)
                    } else {
                        showStatus("TV receiver not found. Install the receiver app on your TV.", isError = true)
                        _uiState.update { it.copy(cast = it.cast.copy(isBusy = false, isCasting = false)) }
                    }
                }
                ReceiverManager.PlayOutcome.NoRemote -> {
                    showStatus("Connect to a TV before casting.", isError = true)
                    _uiState.update { it.copy(cast = it.cast.copy(isBusy = false, isCasting = false)) }
                }
                ReceiverManager.PlayOutcome.Failed -> {
                    showStatus("Could not start playback on the TV.", isError = true)
                    _uiState.update { it.copy(cast = it.cast.copy(isBusy = false, isCasting = false)) }
                }
            }
        }
    }

    /** Cast web, IPTV or library items (already playable URLs) to the TV receiver. */
    fun castSaved(items: List<com.ruterika.castcore.library.SavedMedia>, startIndex: Int = 0, startPositionMs: Long = -1L) {
        val first = items.getOrNull(startIndex) ?: return
        val ui = MediaItemUi(
            id = first.url.hashCode().toLong(),
            title = first.title,
            subtitle = first.pageUrl ?: first.url,
            uri = first.url,
            thumbnailUri = first.posterUrl,
            mimeType = first.mimeType ?: "video/*",
            kind = if (first.mimeType?.startsWith("audio") == true) MediaKind.Audio else MediaKind.Video,
            durationMs = first.durationMs
        )
        _uiState.update { it.copy(cast = it.cast.copy(activeMedia = ui, isBusy = true)) }
        viewModelScope.launch {
            when (com.canasnn.tvremote.cast.CastHub.play(items, startIndex, startPositionMs)) {
                ReceiverManager.PlayOutcome.Started -> onReceiverCastStarted(ui)
                ReceiverManager.PlayOutcome.NotInstalled -> {
                    showStatus("TV receiver not found. Install the receiver app on your TV.", isError = true)
                    _uiState.update { it.copy(cast = it.cast.copy(isBusy = false, isCasting = false)) }
                }
                ReceiverManager.PlayOutcome.NoRemote -> {
                    showStatus("Connect to a TV before casting.", isError = true)
                    _uiState.update { it.copy(cast = it.cast.copy(isBusy = false, isCasting = false)) }
                }
                ReceiverManager.PlayOutcome.Failed -> {
                    showStatus("Could not start playback on the TV.", isError = true)
                    _uiState.update { it.copy(cast = it.cast.copy(isBusy = false, isCasting = false)) }
                }
            }
        }
    }

    val isReceiverCasting: Boolean get() = receiverCasting

    /** "Add to queue" from the already-playing prompt. */
    fun enqueueSaved(items: List<com.ruterika.castcore.library.SavedMedia>) {
        viewModelScope.launch {
            val outcome = com.canasnn.tvremote.cast.CastHub.enqueue(items)
            showStatus(
                if (outcome == ReceiverManager.PlayOutcome.Started) "Added to queue" else "Could not add to queue",
                isError = outcome != ReceiverManager.PlayOutcome.Started
            )
        }
    }

    private fun onReceiverCastStarted(mediaItem: MediaItemUi) {
        receiverCasting = true
        receiverSawPlayer = false
        _uiState.update {
            it.copy(
                cast = CastPlaybackUiState(
                    activeMedia = mediaItem,
                    isCasting = true,
                    isBusy = false,
                    isPlaying = true,
                    durationMs = mediaItem.durationMs
                ),
                statusMessage = "Casting ${mediaItem.title}",
                isError = false
            )
        }
    }

    /** Mirrors the receiver's pushed playback status into the cast UI state. */
    private fun observeReceiverStatus() {
        viewModelScope.launch {
            ReceiverClient.status.collect { status ->
                if (!receiverCasting) return@collect
                if (status.playerVisible) receiverSawPlayer = true
                if (receiverSawPlayer && !status.playerVisible) {
                    // Player closed on the TV (Back/Home or stop).
                    receiverCasting = false
                    _uiState.update {
                        it.copy(cast = it.cast.copy(isCasting = false, isPlaying = false, isBusy = false))
                    }
                    return@collect
                }
                val duration = status.durationMs.takeIf { it > 0 } ?: _uiState.value.cast.durationMs
                _uiState.update {
                    it.copy(
                        cast = it.cast.copy(
                            isPlaying = status.state == com.ruterika.protocol.PlayerState.PLAYING ||
                                status.state == com.ruterika.protocol.PlayerState.BUFFERING,
                            positionMs = status.positionMs,
                            durationMs = duration,
                            progressFraction = if (duration > 0) (status.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
                        )
                    )
                }
            }
        }
    }

    private fun castViaConnectSdk(mediaItem: MediaItemUi) {
        _uiState.update {
            it.copy(cast = it.cast.copy(activeMedia = mediaItem, isBusy = true))
        }

        val connectableDevice = currentConnectableDevice()
        if (connectableDevice == null) {
            Timber.e("[Cast] Failed: No connectable device active")
            showStatus("Connect to a TV before casting.", isError = true)
            _uiState.update { it.copy(cast = it.cast.copy(isBusy = false, isCasting = false)) }
            return
        }

        if (!connectableDevice.isConnected) {
            Timber.i("[Cast] Connecting ConnectableDevice for casting: ${connectableDevice.friendlyName}")
            try { connectableDevice.connect() } catch (_: Exception) {}
        }

        val mediaPlayer = connectableDevice.getCapability(MediaPlayer::class.java)
            ?: connectableDevice.services?.firstNotNullOfOrNull { it.getAPI(MediaPlayer::class.java) }

        if (mediaPlayer == null) {
            Timber.e("[Cast] Device ${connectableDevice.friendlyName} does not expose a MediaPlayer capability")
            showStatus("Connected device does not support media casting.", isError = true)
            _uiState.update { it.copy(cast = it.cast.copy(isBusy = false, isCasting = false)) }
            return
        }

        val castUrl = app.buildCastUrl(
            uri = Uri.parse(mediaItem.uri),
            mimeType = mediaItem.mimeType,
            displayName = mediaItem.title
        )
        if (castUrl == null) {
            Timber.e("[Cast] Failed to build local cast URL for ${mediaItem.title} (${mediaItem.uri})")
            showStatus("Could not prepare media for casting.", isError = true)
            _uiState.update { it.copy(cast = it.cast.copy(isBusy = false, isCasting = false)) }
            return
        }

        Timber.i("[Cast] Starting cast for ${mediaItem.title} on ${connectableDevice.friendlyName} via ${mediaPlayer.javaClass.simpleName} (URL=$castUrl)")

        // Warn early for HEVC/4K content on potentially unsupported receivers
        val isHevc = mediaItem.mimeType.contains("hevc", ignoreCase = true) ||
            mediaItem.mimeType.contains("hev1", ignoreCase = true) ||
            mediaItem.mimeType.contains("h265", ignoreCase = true)
        if (isHevc) {
            Timber.w("[Cast] HEVC content detected (${mediaItem.mimeType}) — may not play on all receivers")
        }

        val mediaInfo = MediaInfo.Builder(castUrl, mediaItem.mimeType)
            .setTitle(mediaItem.title)
            .setDescription(mediaItem.subtitle)
            .build()

        val listener = object : MediaPlayer.LaunchListener {
            override fun onSuccess(mediaLaunchObject: MediaPlayer.MediaLaunchObject) {
                launchSession = mediaLaunchObject.launchSession
                mediaControl = mediaLaunchObject.mediaControl
                _uiState.update {
                    it.copy(
                        cast = CastPlaybackUiState(
                            activeMedia = mediaItem,
                            isCasting = true,
                            isBusy = false,
                            isPlaying = true,
                            durationMs = mediaItem.durationMs
                        ),
                        statusMessage = "Casting ${mediaItem.title}",
                        isError = false
                    )
                }
                if (mediaItem.kind != MediaKind.Photo) {
                    startPlaybackPolling()
                }
                Timber.i("[Cast] SUCCESS: Media casting started for ${mediaItem.title} (launchSession=${launchSession != null}, mediaControl=${mediaControl != null})")
            }

            override fun onError(error: ServiceCommandError) {
                val msg = buildCastErrorMessage(error, mediaItem)
                showStatus(msg, isError = true)
                _uiState.update { it.copy(cast = it.cast.copy(isBusy = false, isCasting = false)) }
                Timber.e("[Cast] ERROR: Media casting failed for ${mediaItem.title}: code=${error.code}, message=${error.message}")
            }
        }

        if (mediaItem.kind == MediaKind.Photo) {
            mediaPlayer.displayImage(mediaInfo, listener)
        } else {
            mediaPlayer.playMedia(mediaInfo, false, listener)
        }
    }

    /** Build a user-friendly error message, with codec hints for HEVC/4K content. */
    private fun buildCastErrorMessage(error: ServiceCommandError, mediaItem: MediaItemUi): String {
        val base = error.message ?: "Media casting failed."
        val isHevc = mediaItem.mimeType.contains("hevc", ignoreCase = true) ||
            mediaItem.mimeType.contains("hev1", ignoreCase = true)
        return if (isHevc) {
            "$base\n\nThis looks like HEVC/H.265 content. Your TV may not support this codec via casting. Try converting to H.264 for better compatibility."
        } else base
    }

    fun togglePlayback() {
        if (receiverCasting) {
            val playing = _uiState.value.cast.isPlaying
            ReceiverClient.send(if (playing) com.ruterika.protocol.Pause else com.ruterika.protocol.Play)
            _uiState.update { it.copy(cast = it.cast.copy(isPlaying = !playing)) }
            return
        }
        val control = mediaControl ?: return
        if (_uiState.value.cast.isPlaying) {
            control.pause(emptyResponseListener())
            _uiState.update { it.copy(cast = it.cast.copy(isPlaying = false)) }
        } else {
            control.play(emptyResponseListener())
            _uiState.update { it.copy(cast = it.cast.copy(isPlaying = true)) }
        }
    }

    fun seekTo(progressFraction: Float) {
        val control = mediaControl
        if (control == null && !receiverCasting) return
        val duration = _uiState.value.cast.durationMs
        if (duration <= 0L) return
        val position = (duration * progressFraction.coerceIn(0f, 1f)).toLong()
        if (receiverCasting) ReceiverClient.send(com.ruterika.protocol.Seek(position))
        else control?.seek(position, emptyResponseListener())
        _uiState.update {
            it.copy(
                cast = it.cast.copy(
                    positionMs = position,
                    progressFraction = progressFraction.coerceIn(0f, 1f)
                )
            )
        }
    }

    fun stopCasting() {
        if (receiverCasting) {
            com.canasnn.tvremote.cast.CastHub.stop()
            receiverCasting = false
        }
        Timber.i("[Cast] stopCasting called by user — stopping media session")
        playbackPollJob?.cancel()
        playbackPollJob = null
        try {
            mediaControl?.stop(object : ResponseListener<Any> {
                override fun onSuccess(response: Any?) {
                    Timber.i("[Cast] mediaControl.stop SUCCESS")
                }
                override fun onError(error: ServiceCommandError?) {
                    Timber.w("[Cast] mediaControl.stop ERROR: ${error?.message}")
                }
            })
        } catch (e: Exception) {
            Timber.w(e, "[Cast] Exception stopping mediaControl")
        }
        launchSession = null
        mediaControl = null
        _uiState.update {
            it.copy(
                cast = it.cast.copy(
                    isCasting = false,
                    isBusy = false,
                    isPlaying = false,
                    progressFraction = 0f,
                    positionMs = 0L
                ),
                statusMessage = "Casting stopped",
                isError = false
            )
        }
    }

    fun clearCastMedia() {
        _uiState.update { it.copy(cast = CastPlaybackUiState()) }
    }

    // ── DiscoveryManagerListener ──────────────────────────────────────────────

    override fun onDeviceAdded(manager: DiscoveryManager?, device: ConnectableDevice?) {
        device ?: return
        if (!device.isChromecastServiceDevice()) {
            synchronized(discoveredDevices) { discoveredDevices.remove(device.id) }
            publishDeviceSnapshot()
            return
        }
        synchronized(discoveredDevices) {
            val ip = device.ipAddress
            if (!ip.isNullOrBlank()) {
                val duplicateKeys = discoveredDevices.filterValues { it.ipAddress == ip && it.id != device.id }.keys.toList()
                duplicateKeys.forEach { discoveredDevices.remove(it) }
            }
            discoveredDevices[device.id] = device
        }
        validateReachability(device)
        publishDeviceSnapshot()
        scheduleAutoReconnect(delayMs = 500L)
    }

    override fun onDeviceUpdated(manager: DiscoveryManager?, device: ConnectableDevice?) {
        device ?: return
        synchronized(discoveredDevices) {
            if (device.isChromecastServiceDevice()) {
                val ip = device.ipAddress
                if (!ip.isNullOrBlank()) {
                    val duplicateKeys = discoveredDevices.filterValues { it.ipAddress == ip && it.id != device.id }.keys.toList()
                    duplicateKeys.forEach { discoveredDevices.remove(it) }
                }
                discoveredDevices[device.id] = device
                validateReachability(device)
            } else {
                discoveredDevices.remove(device.id)
            }
        }
        publishDeviceSnapshot()
        scheduleAutoReconnect(delayMs = 500L)
    }

    override fun onDeviceRemoved(manager: DiscoveryManager?, device: ConnectableDevice?) {
        device ?: return
        synchronized(discoveredDevices) { discoveredDevices.remove(device.id) }
        device.ipAddress?.let { reachableHosts.remove(it) }
        if (activeDeviceId == device.id) {
            disconnectCurrentDevice()
        } else {
            publishDeviceSnapshot()
        }
    }

    override fun onDiscoveryFailed(manager: DiscoveryManager?, error: ServiceCommandError?) {
        showStatus(error?.message ?: "Device discovery failed.", isError = true)
    }

    override fun onCleared() {
        super.onCleared()
        autoReconnectScheduleJob?.cancel()
        autoReconnectJob?.cancel()
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = null
        stopHeartbeat()
        KeepAliveService.stop(app)
        playbackPollJob?.cancel()
        discoveryManager?.removeListener(this)
        atvDiscovery.stop()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }


    // ── Connection internals ─────────────────────────────────────────────────

    private fun connectRemote(
        device: ConnectableDevice,
        host: String,
        onSecretFallbackMessage: String? = null
    ) {
        val connectionToken = ++connectionGeneration
        app.androidRemoteTv?.abort()
        app.androidRemoteTv = null
        val remote = app.getOrCreateAndroidRemoteTv()
        remote.addListener(volumeListener)
        app.androidRemoteTv = remote

        Timber.i("[Connection] Starting fresh connect to $host (token=$connectionToken)")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                remote.connect(host, createRemoteListener(device, host, onSecretFallbackMessage, connectionToken))
            } catch (error: Exception) {
                if (isActiveConnection(connectionToken)) {
                    handleConnectError(device, error.message ?: "Connection failed.")
                }
            }
        }
    }

    private fun reconnectRemote(
        device: ConnectableDevice,
        host: String,
        allowFreshFallback: Boolean
    ) {
        Log.d(TAG, "reconnectRemote: ")
        val connectionToken = ++connectionGeneration
        app.androidRemoteTv?.abort()
        app.androidRemoteTv = null
        val remote = app.getOrCreateAndroidRemoteTv()
        remote.addListener(volumeListener)
        remote.setVolumeChangedCallback { level, maxLevel, muted ->
            updateVolumeState(level, maxLevel, muted)
        }
        app.androidRemoteTv = remote

        Timber.i("[Connection] Attempting reconnect to $host (token=$connectionToken)")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                remote.reconnect(host, object : AndroidTvListener {
                    override fun onSessionCreated() = Unit
                    override fun onSecretRequested() {

                        if (!isActiveConnection(connectionToken)) return
                        prefs.edit {
                            remove(Constant.HOST)
                            putBoolean(Constant.PIN, false)
                        }
                        if (allowFreshFallback) {
                            connectRemote(
                                device = device, host = host,
                                onSecretFallbackMessage = "Pairing code needed. Enter it to finish reconnecting."
                            )
                        } else {
                            handleConnectError(device, "Pairing code needed to reconnect.")
                        }
                    }
                    override fun onPaired() = Unit
                    override fun onConnectingToRemote() = Unit
                    override fun onConnected() {
                        if (!isActiveConnection(connectionToken)) return
                        app.androidRemoteTv = remote
                        attachConnectableDevice(device, host, connectionToken)
                    }
                    override fun onDisconnect() {
                        if (!isActiveConnection(connectionToken)) return
                        Timber.w("[Connection] onDisconnect fired during reconnect")
                        handleDeviceDisconnected()
                    }
                    override fun onImeShow(text: String, fieldCounter: Int) {
                        if (!isActiveConnection(connectionToken)) return
                        syncImeState(text, fieldCounter)
                    }
                    override fun onError(error: String) {
                        if (!isActiveConnection(connectionToken)) return
                        handleConnectError(device, error)
                    }
                })
            } catch (error: Exception) {
                if (isActiveConnection(connectionToken)) {
                    handleConnectError(device, error.message ?: "Reconnect failed.")
                }
            }
        }
    }

    private fun createRemoteListener(
        device: ConnectableDevice,
        host: String,
        pairingMessage: String?,
        connectionToken: Int
    ): AndroidTvListener {
        return object : AndroidTvListener {
            override fun onSessionCreated() = Unit
            override fun onSecretRequested() {
                if (!isActiveConnection(connectionToken)) return
                _uiState.update {
                    it.copy(
                        pairingRequired = true, pairingError = null,
                        pairingRequestId = it.pairingRequestId + 1,
                        statusMessage = pairingMessage ?: "Enter the pairing code shown on your TV.",
                        isError = false
                    )
                }
            }
            override fun onPaired() = Unit
            override fun onConnectingToRemote() = Unit
            override fun onConnected() {
                if (!isActiveConnection(connectionToken)) return
                app.androidRemoteTv = app.getOrCreateAndroidRemoteTv()
                attachConnectableDevice(device, host, connectionToken)
            }
            override fun onDisconnect() {
                if (!isActiveConnection(connectionToken)) return
                Timber.w("[Connection] onDisconnect fired on listener (token=$connectionToken)")
                handleDeviceDisconnected()
            }
            override fun onImeShow(text: String, fieldCounter: Int) {
                if (!isActiveConnection(connectionToken)) return
                syncImeState(text, fieldCounter)
            }
            override fun onError(error: String) {
                if (!isActiveConnection(connectionToken)) return
                handleConnectError(device, error)
            }
        }
    }

    private fun attachConnectableDevice(device: ConnectableDevice, host: String, connectionToken: Int) {
        currentConnectableListener?.let { previous ->
            try { device.removeListener(previous) } catch (_: Exception) {}
        }

        val listener = object : ConnectableDeviceListener {
            override fun onDeviceReady(connectableDevice: ConnectableDevice) {
                if (!isActiveConnection(connectionToken)) return
                Constant.connectableDevice = connectableDevice
                Constant.isConnected.value = true
                activeDeviceId = connectableDevice.id
                pendingDeviceId = null
                val remote = app.androidRemoteTv
                if (remote != null) {
                    remote.setVolumeChangedCallback { level, maxLevel, muted ->
                        updateVolumeState(level, maxLevel, muted)
                    }
                    if (remote.currentVolume >= 0) {
                        updateVolumeState(remote.currentVolume, remote.maxVolume, remote.isMuted)
                    }
                }
                val currentConnections = prefs.getInt("successful_connections", 0) + 1

                prefs.edit {
                    putString(Constant.HOST, host)
                    putBoolean(Constant.PIN, true)
                    putBoolean(Constant.PREF_MANUAL_DISCONNECT, false)
                    putInt("successful_connections", currentConnections)
                }
                imeFieldCounter = app.lastFieldCounter.coerceAtLeast(0)
                observeRealVolume()
                // ── Cancel the timeout watchdog — connection succeeded in time.
                connectionWatchdogJob?.cancel()
                connectionWatchdogJob = null
                autoReconnectScheduleJob?.cancel()
                autoReconnectAttempts = 0
                autoReconnectInFlight.set(false)
                startHeartbeat()
                KeepAliveService.start(app, connectableDevice.friendlyName ?: "TV")
                ReceiverManager.onTvConnected(host)
                refreshTvApps(host)

                _uiState.update {
                    it.copy(
                        connectedDevice = connectableDevice.toUiModel(
                            isPaired = true, isConnected = true, isConnecting = false
                        ),
                        isConnecting = false, pairingRequired = false,
                        pairingError = null,
                        statusMessage = "${connectableDevice.friendlyName} connected",
                        isError = false
                    )
                }
                publishDeviceSnapshot()
                Timber.i("[Connection] Connected to ${connectableDevice.friendlyName} at $host")
            }

            override fun onDeviceDisconnected(connectableDevice: ConnectableDevice) {
                if (!isActiveConnection(connectionToken)) return
                Timber.w("[Connection] ConnectableDevice disconnected: ${connectableDevice.friendlyName}")
                handleDeviceDisconnected()
            }

            override fun onPairingRequired(
                connectableDevice: ConnectableDevice,
                service: DeviceService,
                pairingType: DeviceService.PairingType
            ) = Unit

            override fun onCapabilityUpdated(
                connectableDevice: ConnectableDevice,
                added: List<String>,
                removed: List<String>
            ) = Unit

            override fun onConnectionFailed(
                connectableDevice: ConnectableDevice,
                error: ServiceCommandError
            ) {
                if (!isActiveConnection(connectionToken)) return
                handleConnectError(device, error.message ?: "ConnectSDK connection failed.")
            }
        }

        currentConnectableListener = listener
        device.addListener(listener)
        if (device.services.isEmpty()) {
            prefs.edit { putString(PREF_SAVED_NAME, device.friendlyName) }
            viewModelScope.launch(Dispatchers.Main) { listener.onDeviceReady(device) }
        } else {
            device.connect()
        }
    }

    private fun updateVolumeState(level: Int, maxLevel: Int, muted: Boolean) {
        if (level < 0) return // the TV hasn't reported its volume yet
        tvReportedVolume = true
        val fraction = if (maxLevel <= 0) 0f else (level.toFloat() / maxLevel.toFloat()).coerceIn(0f, 1f)
        val percent = if (maxLevel <= 0) 0 else Math.round(fraction * 100).coerceIn(0, 100)
        _uiState.update {
            it.copy(
                volumeFraction = fraction,
                volumeLevel = percent,
                isMuted = muted,
                volumeKnown = true
            )
        }
    }

    /** True once the TV sent its volume over the remote protocol; other sources are ignored then. */
    @Volatile private var tvReportedVolume = false

    private fun observeRealVolume() {
        app.androidRemoteTv?.setVolumeChangedCallback { level, maxLevel, muted ->
            updateVolumeState(level, maxLevel, muted)
        }
        val dev = currentConnectableDevice()
        dev?.volumeControl?.getVolume(
            object : VolumeControl.VolumeListener {
                override fun onSuccess(volume: Float?) {
                    // Other renderers on the TV (DLNA) report their own volume, often 0: only a
                    // fallback until the remote protocol reports the real one.
                    if (volume == null || volume <= 0f || tvReportedVolume) return
                    val levelInt = (volume * 100).toInt()
                    _uiState.update {
                        it.copy(
                            volumeFraction = volume.coerceIn(0f, 1f),
                            volumeLevel = levelInt,
                            volumeKnown = true
                        )
                    }
                }
                override fun onError(error: ServiceCommandError?) {
                    Timber.w("[Volume] Initial volume fetch failed: ${error?.message}")
                }
            }
        )
        try {
            dev?.volumeControl?.subscribeVolume(
                object : VolumeControl.VolumeListener {
                    override fun onSuccess(volume: Float?) {
                        if (volume == null || volume <= 0f || tvReportedVolume) return
                        val levelInt = (volume * 100).toInt()
                        _uiState.update {
                            it.copy(
                                volumeFraction = volume.coerceIn(0f, 1f),
                                volumeLevel = levelInt,
                                volumeKnown = true
                            )
                        }
                    }
                    override fun onError(error: ServiceCommandError?) {}
                }
            )
        } catch (e: Exception) {
            Timber.w(e, "[Volume] subscribeVolume not supported on this device")
        }
    }

    private fun startPlaybackPolling() {
        playbackPollJob?.cancel()
        playbackPollJob = viewModelScope.launch {
            while (true) {
                mediaControl?.getPosition(object : MediaControl.PositionListener {
                    override fun onSuccess(position: Long?) {
                        val safePosition = position ?: 0L
                        _uiState.update { state ->
                            val duration = state.cast.durationMs
                            state.copy(
                                cast = state.cast.copy(
                                    positionMs = safePosition,
                                    progressFraction = if (duration > 0L) {
                                        (safePosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                                    } else 0f
                                )
                            )
                        }
                    }
                    override fun onError(error: ServiceCommandError?) = Unit
                })
                mediaControl?.getDuration(object : MediaControl.DurationListener {
                    override fun onSuccess(duration: Long?) {
                        val safeDuration = duration ?: return
                        _uiState.update { state ->
                            state.copy(
                                cast = state.cast.copy(
                                    durationMs = safeDuration,
                                    progressFraction = if (safeDuration > 0L) {
                                        (state.cast.positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
                                    } else 0f
                                )
                            )
                        }
                    }
                    override fun onError(error: ServiceCommandError?) = Unit
                })
                mediaControl?.getPlayState(object : MediaControl.PlayStateListener {
                    override fun onSuccess(playState: MediaControl.PlayStateStatus?) {
                        val isPlaying = playState == MediaControl.PlayStateStatus.Playing ||
                            playState == MediaControl.PlayStateStatus.Buffering
                        _uiState.update { it.copy(cast = it.cast.copy(isPlaying = isPlaying)) }
                    }
                    override fun onError(error: ServiceCommandError?) = Unit
                })
                delay(1_000)
            }
        }
    }

    private fun onAppForegrounded() {
        val lastBg = lastBackgroundTime
        lastBackgroundTime = 0L
        Timber.i("[Lifecycle] App entered foreground. Connected: ${_uiState.value.connectedDevice != null}, Stale duration: ${if (lastBg > 0L) (System.currentTimeMillis() - lastBg) / 1000 else 0}s")

        val remote = app.androidRemoteTv
        if (remote != null && _uiState.value.connectedDevice != null) {
            // Verify existing session is alive, proactively reconnect seamlessly if needed
            viewModelScope.launch(Dispatchers.IO) {
                if (!remote.isSocketAlive || !remote.sendPing()) {
                    Timber.w("[Lifecycle] Active ping failed on foreground wake-up — ensuring connected")
                    remote.ensureConnected()
                } else {
                    Timber.i("[Lifecycle] Active connection verified alive after foreground resume")
                }
            }
        } else if (_uiState.value.connectedDevice == null && !_uiState.value.isConnecting) {
            // No active connection at all — cancel any stuck reconnect and retry
            viewModelScope.launch(Dispatchers.Main) {
                connectionWatchdogJob?.cancel()
                connectionWatchdogJob = null
                autoReconnectScheduleJob?.cancel()
                autoReconnectJob?.cancel()
                autoReconnectInFlight.set(false)
                if (isWifiConnected()) {
                    scheduleAutoReconnect(delayMs = 300L)
                }
            }
        }
    }

    private fun scheduleAutoReconnect(delayMs: Long = 500L) {
        if (_uiState.value.connectedDevice != null || Constant.isConnected.value == true) {
            autoReconnectAttempts = 0
            return
        }
        if (prefs.getBoolean(Constant.PREF_MANUAL_DISCONNECT, false)) return
        if (System.currentTimeMillis() < manualDisconnectUntilMs) return
        if (!prefs.getBoolean(Constant.PREF_AUTO_RECONNECT, true) || !prefs.getBoolean(Constant.PIN, false)) return
        if (autoReconnectAttempts >= MAX_AUTO_RECONNECT_ATTEMPTS) {
            Timber.w("[AutoReconnect] Max auto-reconnect attempts ($MAX_AUTO_RECONNECT_ATTEMPTS) reached; awaiting user action")
            return
        }

        autoReconnectScheduleJob?.cancel()
        autoReconnectScheduleJob = viewModelScope.launch(Dispatchers.Main) {
            delay(delayMs)
            if (_uiState.value.connectedDevice == null && !autoReconnectInFlight.get() && !_uiState.value.isConnecting) {
                val host = prefs.getString(Constant.HOST, null)
                val deviceFound = synchronized(discoveredDevices) {
                    host != null && discoveredDevices.values.any { it.ipAddress == host }
                }
                if (deviceFound) {
                    autoReconnectAttempts++
                    Timber.d("[AutoReconnect] Debounced trigger firing attempt $autoReconnectAttempts/$MAX_AUTO_RECONNECT_ATTEMPTS")
                    attemptAutoReconnect()
                } else {
                    Timber.d("[AutoReconnect] Scheduled reconnect triggered, but device $host not yet discovered")
                }
            }
        }
    }

    private fun attemptAutoReconnect() {
        if (autoReconnectInFlight.get() || _uiState.value.isConnecting || _uiState.value.connectedDevice != null) {
            return
        }
        if (!prefs.getBoolean(Constant.PREF_AUTO_RECONNECT, true) || !prefs.getBoolean(Constant.PIN, false)) {
            return
        }
        if (prefs.getBoolean(Constant.PREF_MANUAL_DISCONNECT, false)) return
        if (System.currentTimeMillis() < manualDisconnectUntilMs) return

        val host = prefs.getString(Constant.HOST, null) ?: return

        // ── Active registry check: match last connected IP against background mDNS
        // discovered devices.  Will be re-triggered by onDeviceAdded/onDeviceUpdated
        // as soon as discovery finds the device on the network.
        val device = synchronized(discoveredDevices) {
            discoveredDevices.values.firstOrNull { it.ipAddress == host }
        }
        if (device == null) {
            Timber.d("[AutoReconnect] Target device at $host not yet discovered in registry, awaiting mDNS...")
            return
        }

        Timber.i("[AutoReconnect] Network target $host matched registry. Starting silent reconnect.")
        autoReconnectInFlight.set(true)

        // ── Timeout guard: launch a watchdog coroutine that fires after
        // AUTO_RECONNECT_TIMEOUT_MS.  If the connection handshake hasn't
        // completed within this window, we are in the stuck-connecting state.
        // The watchdog tears down stale state and navigates to Discovery so the
        // user can select the device manually.
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = viewModelScope.launch(Dispatchers.Main) {
            delay(AUTO_RECONNECT_TIMEOUT_MS)
            // If we're still connecting after the timeout window, abort.
            if (autoReconnectInFlight.get() || _uiState.value.isConnecting) {
                Timber.w("[AutoReconnect] Watchdog fired — reconnect timed out after ${AUTO_RECONNECT_TIMEOUT_MS}ms. Clearing state.")
                // Tear down any stale socket
                app.androidRemoteTv?.abort()
                app.androidRemoteTv = null
                autoReconnectInFlight.set(false)
                pendingDeviceId = null
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        pairingRequired = false,
                        pairingError = null,
                        statusMessage = "Could not reconnect automatically. Select your TV from the list.",
                        isError = true
                    )
                }
                publishDeviceSnapshot()
                // Navigate to Discovery so the user can manually select the device
                _navigateToDiscoveryTrigger.value = true
            }
        }

        autoReconnectJob = viewModelScope.launch(Dispatchers.IO) {
            connectToDeviceInternal(device.id, userInitiated = false)
        }
    }

    private fun publishDeviceSnapshot() {
        val pendingId = pendingDeviceId
        val connectedId = activeDeviceId
        val pairedHost = prefs.getString(Constant.HOST, null)
        val isDeviceConnected = Constant.isConnected.value == true

        val snapshot = synchronized(discoveredDevices) {
            discoveredDevices.values
                .filter { it.isChromecastServiceDevice() }
                .filter { device -> activeDeviceId == device.id || isDeviceReachable(device) }
                .distinctBy { device -> device.ipAddress?.takeIf { it.isNotBlank() } ?: device.id }
                .map { device ->
                    val isConnected = isDeviceConnected && (
                        connectedId == device.id ||
                        (!pairedHost.isNullOrBlank() && device.ipAddress == pairedHost)
                    )
                    device.toUiModel(
                        isPaired = device.ipAddress == pairedHost && prefs.getBoolean(Constant.PIN, false),
                        isConnected = isConnected,
                        isConnecting = pendingId == device.id && !isConnected
                    )
                }.sortedWith(
                    compareByDescending<DeviceUiModel> { it.isConnected }
                        .thenByDescending { it.isPaired }
                        .thenBy { it.name.lowercase() }
                )
        }

        _uiState.update { state ->
            state.copy(
                discoveredDevices = snapshot,
                connectedDevice = snapshot.firstOrNull { it.isConnected }
                    ?: snapshot.firstOrNull { it.id == connectedId }
                    ?: state.connectedDevice,
                isConnecting = pendingId != null && snapshot.none { it.isConnected }
            )
        }
    }

    private fun currentConnectableDevice(): ConnectableDevice? {
        val currentId = activeDeviceId
        val fromMap = if (currentId != null) {
            synchronized(discoveredDevices) {
                discoveredDevices[currentId]
                    ?: discoveredDevices.values.firstOrNull { it.id == currentId || it.ipAddress == currentId || it.isConnected }
            }
        } else null

        return fromMap
            ?: Constant.connectableDevice
            ?: synchronized(discoveredDevices) {
                discoveredDevices.values.firstOrNull { it.isConnected }
                    ?: discoveredDevices.values.firstOrNull()
            }
    }

    private fun handleConnectError(device: ConnectableDevice, message: String) {
        Timber.e("[Connection] Connect error for ${device.friendlyName}: $message")
        if (isWrongPairingCodeError(message) && pendingDeviceId == device.id) {
            handleWrongPairingCode()
            return
        }
        autoReconnectInFlight.set(false)
        pendingDeviceId = null
        publishDeviceSnapshot()
        showStatus(message, isError = true)
        if (activeDeviceId == device.id) {
            handleDeviceDisconnected()
        }
    }

    private fun handleDeviceDisconnected() {
        // Guard against being called multiple times for the same disconnect
        // (e.g. both the heartbeat and the reader thread firing simultaneously)
        val wasConnected = Constant.isConnected.value
        autoReconnectInFlight.set(false)
        pendingDeviceId = null
        activeDeviceId = null
        stopHeartbeat()
        KeepAliveService.stop(app)
        playbackPollJob?.cancel()
        playbackPollJob = null
        mediaControl = null
        launchSession = null
        Constant.connectableDevice = null
        Constant.isConnected.value = false
        val manuallyDisconnected = prefs.getBoolean(Constant.PREF_MANUAL_DISCONNECT, false)
        imeFieldCounter = 0
        imeCounter = 0
        app.lastImeText = ""
        app.lastFieldCounter = -1
        _uiState.update {
            it.copy(
                connectedDevice = null, isConnecting = false,
                pairingRequired = false, pairingError = null,
                isVoiceActive = false, cast = CastPlaybackUiState(),
                statusMessage = if (manuallyDisconnected) "Disconnected" else "Connection lost",
                isError = !manuallyDisconnected
            )
        }
        publishDeviceSnapshot()
        Timber.w("[Connection] Device disconnected (wasConnected=$wasConnected, manual=$manuallyDisconnected)")
        if (!manuallyDisconnected) {
            scheduleAutoReconnect(delayMs = 2500L)
        }
    }

    private fun handleWrongPairingCode() {
        val deviceId = pendingDeviceId ?: return showPairingError("Wrong pairing code. Requesting a new code...")
        val device = synchronized(discoveredDevices) { discoveredDevices[deviceId] }
            ?: return showPairingError("Wrong pairing code. Select your TV again to retry.")
        val host = device.ipAddress
        if (host.isNullOrBlank()) {
            showPairingError("Wrong pairing code. Select your TV again to retry.")
            return
        }
        showPairingError("Wrong pairing code. Requesting a new code...")
        app.androidRemoteTv?.abort()
        connectRemote(
            device = device, host = host,
            onSecretFallbackMessage = "Wrong pairing code. Enter the new code shown on your TV."
        )
    }

    private fun showPairingError(message: String) {
        _uiState.update {
            it.copy(
                pairingRequired = true, pairingError = message,
                pairingRequestId = it.pairingRequestId + 1,
                statusMessage = message, isError = true, isConnecting = true
            )
        }
    }

    private fun isWrongPairingCodeError(message: String): Boolean {
        return message.contains("STATUS_BAD_SECRET", ignoreCase = true) ||
            message.contains("bad secret", ignoreCase = true) ||
            message.contains("wrong", ignoreCase = true) ||
            message.contains("invalid", ignoreCase = true)
    }

    private fun isActiveConnection(connectionToken: Int): Boolean =
        connectionToken == connectionGeneration

    private fun validateReachability(device: ConnectableDevice) {
        val host = device.ipAddress?.takeIf { it.isNotBlank() } ?: return
        if (!validatingHosts.add(host)) return

        viewModelScope.launch(Dispatchers.IO) {
            val reachable = isHostPortOpen(host, CAST_PORT) || isHostPortOpen(host, REMOTE_PORT) ||
                isHostPortOpen(host, WAKE_PORT)  // TV in standby: only the wake port stays open
            if (reachable) reachableHosts.add(host) else reachableHosts.remove(host)
            validatingHosts.remove(host)
            publishDeviceSnapshot()
            if (reachable) scheduleAutoReconnect(delayMs = 500L)
        }
    }

    private fun isDeviceReachable(device: ConnectableDevice): Boolean {
        val host = device.ipAddress ?: return false
        return reachableHosts.contains(host)
    }

    private fun isHostPortOpen(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), REACHABILITY_TIMEOUT_MS)
                true
            }
        } catch (_: Exception) { false }
    }

    private fun syncImeState(text: String, fieldCounter: Int) {
        app.lastImeText = text
        app.lastFieldCounter = fieldCounter
        // KEYBOARD FIX: accept fieldCounter=0 (NVIDIA Shield sends 0 for all fields)
        // Previous code only updated when fieldCounter > 0, ignoring Shield's valid 0
        if (fieldCounter >= 0) {
            imeFieldCounter = fieldCounter
        }
        imeCounter = 0
        // The TV reports after every change; while the phone is typing those reports lag
        // behind it, so only a report with the phone quiet means the TV box really changed.
        val echo = android.os.SystemClock.elapsedRealtime() - lastImeSendAt < IME_ECHO_WINDOW_MS
        if (!echo && text != tvTypedText) {
            tvTypedText = text
            _uiState.update { it.copy(tvText = text, tvTextField = fieldCounter, tvTextRevision = it.tvTextRevision + 1) }
        } else {
            _uiState.update { it.copy(tvTextField = fieldCounter) }
        }
        Timber.d("[Keyboard] IME state synced: text='$text' fieldCounter=$fieldCounter")
    }

    private fun sendImeTextUpdate(text: String) {
        val remote = app.androidRemoteTv ?: return
        val currentCounter = ++imeCounter
        val currentFieldCounter = resolvedImeFieldCounter()
        remote.sendText(text, currentCounter, currentFieldCounter)
        app.lastImeText = text
    }

    /**
     * KEYBOARD FIX: Return the field counter as reported by the TV.
     *
     * Previous bug: the fallback returned `1` even when the TV had sent
     * `fieldCounter=0` (NVIDIA Shield always sends 0).  Shield would then
     * discard the keystroke because the field counter didn't match.
     *
     * Fix: use `0` as the fallback. The TV's actual reported value is always
     * preferred; only when the TV has never sent anything do we use 1 (which
     * is the default for non-Shield devices that start counting at 1).
     */
    private fun resolvedImeFieldCounter(): Int {
        return when {
            imeFieldCounter > 0 -> imeFieldCounter  // TV explicitly sent > 0
            app.lastFieldCounter >= 0 -> app.lastFieldCounter  // 0 is valid (Shield)
            else -> 1  // No IME event received yet; default for standard ATV
        }
    }

    private fun showStatus(message: String, isError: Boolean, isConnecting: Boolean = false) {
        _uiState.update {
            it.copy(statusMessage = message, isError = isError, isConnecting = isConnecting)
        }
    }

    private fun emptyResponseListener(): ResponseListener<Any> {
        return object : ResponseListener<Any> {
            override fun onSuccess(response: Any?) = Unit
            override fun onError(error: ServiceCommandError?) = Unit
        }
    }

    private fun ConnectableDevice.toUiModel(
        isPaired: Boolean,
        isConnected: Boolean,
        isConnecting: Boolean
    ): DeviceUiModel {
        val typeName = when {
            friendlyName.contains("Chromecast", ignoreCase = true) -> "Chromecast"
            friendlyName.contains("Google", ignoreCase = true) -> "Google TV"
            else -> "Android TV"
        }
        val accent = when {
            typeName == "Chromecast" -> Color(0xFFF4B400)
            typeName == "Google TV" -> Color(0xFF0F9D58)
            else -> Color(0xFF4285F4)
        }
        return DeviceUiModel(
            id = id,
            name = friendlyName ?: "Android TV",
            type = typeName,
            ipAddress = ipAddress ?: "",
            isPaired = isPaired,
            supportsCast = true,
            accent = accent,
            isConnected = isConnected,
            isConnecting = isConnecting
        )
    }

    private fun ConnectableDevice.isChromecastServiceDevice(): Boolean {
        val pairedHost = prefs.getString(Constant.HOST, null)
        if (ipAddress != null && ipAddress == pairedHost && prefs.getBoolean(Constant.PIN, false)) {
            return true
        }
        if (getServiceByName(ConnectSdkCastService.ID) != null) return true
        if (id.startsWith("atv-nsd-")) return true
        val primaryServiceId = serviceDescription?.serviceID
        if (primaryServiceId != null && primaryServiceId.equals(ConnectSdkCastService.ID, ignoreCase = true)) {
            return true
        }
        val connectedServices = connectedServiceNames ?: return false
        return connectedServices
            .split(",")
            .any { it.trim().equals(ConnectSdkCastService.ID, ignoreCase = true) }
    }

    private val volumeListener = object : AndroidTvListener {
        override fun onSessionCreated() = Unit
        override fun onSecretRequested() = Unit
        override fun onPaired() = Unit
        override fun onConnectingToRemote() = Unit
        override fun onConnected() = Unit
        override fun onDisconnect() = Unit
        override fun onImeShow(text: String, fieldCounter: Int) = Unit
        override fun onError(error: String) = Unit
    }

    private companion object {
        /** TV text-box reports this soon after the phone typed are echoes of that typing. */
        const val IME_ECHO_WINDOW_MS = 1500L
        const val TAG = "TvRemoteViewModel"
        const val CAST_PORT = 8009
        const val REMOTE_PORT = 6466
        const val WAKE_PORT = 6465
        const val PREF_SAVED_NAME = "atv_saved_device_name"
        const val REACHABILITY_TIMEOUT_MS = 700
        const val MANUAL_DISCONNECT_COOLDOWN_MS = 15_000L

        /** Heartbeat ping interval. Keep well below typical TV idle timeout (~60s). */
        const val HEARTBEAT_INTERVAL_MS = 25_000L

        /**
         * Maximum time (ms) to wait for a background auto-reconnect to resolve.
         * Accommodates TCP connect (6s) + TLS/RemoteConfigure handshake (8s).
         */
        const val AUTO_RECONNECT_TIMEOUT_MS = 15_000L

        /** Maximum consecutive auto-reconnect retry attempts before pausing. */
        const val MAX_AUTO_RECONNECT_ATTEMPTS = 4


        /** Maps known streaming app names to their Android TV app-link URIs. */
        fun builtInAppUrl(appName: String): String? = when (appName) {
            "Netflix"                      -> "https://www.netflix.com/title.*"
            "YouTube"                      -> "https://www.youtube.com"
            "Prime", "Prime Video"         -> "https://www.primevideo.com/"
            "Disney+"                      -> "https://www.disneyplus.com/"
            "JioHotstar", "Hotstar",
            "Jio Hotstar"                  -> "https://www.hotstar.com/"
            "SonyLIV"                      -> "https://www.sonyliv.com/"
            "Hulu"                         -> "https://www.hulu.com/"
            "Apple TV"                     -> "https://tv.apple.com/"
            "HBO Max", "Max"               -> "https://max.com/"
            "Spotify"                      -> "https://open.spotify.com/"
            else                           -> null
        }
    }
}

/**
 * Extension to safely get the underlying RemoteSession from AndroidRemoteTv.
 * Used by the heartbeat to call sendPing() without reflection.
 */
private fun AndroidRemoteTv.remoteSessionOrNull(): com.canasnn.tvremote.androidLib.remote.RemoteSession? {
    return try {
        val field = this.javaClass.superclass?.getDeclaredField("mRemoteSession")
            ?: this.javaClass.getDeclaredField("mRemoteSession")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        field.get(this) as? com.canasnn.tvremote.androidLib.remote.RemoteSession
    } catch (_: Exception) {
        // Field access unavailable — fall back to sending a key command as a heartbeat
        null
    }
}

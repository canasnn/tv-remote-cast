package com.canasnn.tvremote.receiver

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.ruterika.protocol.Hello
import com.ruterika.protocol.Message
import com.ruterika.protocol.PlaybackStatus
import com.ruterika.protocol.Protocol
import com.ruterika.protocol.ProtocolJson
import com.ruterika.protocol.Status
import com.ruterika.protocol.Welcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * WebSocket link to our receiver app on the TV.
 *
 * Keeps one connection to the TV the remote is connected to and reconnects
 * with backoff. It never wakes the receiver by itself; [ReceiverManager]
 * decides when to send the start link through the remote.
 */
object ReceiverClient {

    sealed interface State {
        data object Idle : State
        data class Connecting(val host: String) : State
        data class Connected(val host: String, val port: Int, val welcome: Welcome) : State
        /** The receiver answered but does not know our token yet. */
        data class Unauthorized(val host: String) : State
        /** Nothing listening: receiver not installed, or its service is not running. */
        data class Unreachable(val host: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: SharedPreferences

    private val http = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _status = MutableStateFlow(PlaybackStatus())
    val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 32)
    /** Every frame from the TV except status updates. */
    val messages: SharedFlow<Message> = _messages.asSharedFlow()

    @Volatile private var targetHost: String? = null
    @Volatile private var socket: WebSocket? = null
    private var loopJob: Job? = null

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Per-install secret; handed to the TV only through the remote's app link. */
    val token: String
        get() = prefs.getString(KEY_TOKEN, null) ?: newToken().also { prefs.edit().putString(KEY_TOKEN, it).apply() }

    val isConnected: Boolean get() = _state.value is State.Connected

    /** Keep a connection to [host] (the TV the remote is connected to). */
    @Synchronized
    fun connect(host: String) {
        if (targetHost == host && loopJob?.isActive == true) return
        disconnect()
        targetHost = host
        loopJob = scope.launch { connectionLoop(host) }
    }

    @Synchronized
    fun disconnect() {
        targetHost = null
        loopJob?.cancel()
        loopJob = null
        socket?.close(1000, null)
        socket = null
        _state.value = State.Idle
        _status.value = PlaybackStatus()
    }

    /** Try again right now (e.g. after the start link woke the receiver). */
    fun retryNow() {
        val host = targetHost ?: return
        synchronized(this) {
            loopJob?.cancel()
            socket?.cancel()
            socket = null
            loopJob = scope.launch { connectionLoop(host) }
        }
    }

    suspend fun awaitConnected(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { state.first { it is State.Connected } } != null

    fun send(message: Message): Boolean {
        val ws = socket ?: return false
        if (_state.value !is State.Connected) return false
        return ws.send(ProtocolJson.encode(message))
    }

    // ── Connection loop ─────────────────────────────────────────────────────

    private suspend fun connectionLoop(host: String) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (targetHost == host) {
            _state.value = State.Connecting(host)
            val outcome = openOnce(host)
            if (targetHost != host) return
            when (outcome) {
                Outcome.Closed -> backoffMs = INITIAL_BACKOFF_MS // was connected; retry soon
                Outcome.Unauthorized -> _state.value = State.Unauthorized(host)
                Outcome.Unreachable -> _state.value = State.Unreachable(host)
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private enum class Outcome { Closed, Unauthorized, Unreachable }

    /** Tries the remembered port first, then the receiver's port range. */
    private suspend fun openOnce(host: String): Outcome {
        val remembered = prefs.getInt(KEY_PORT_PREFIX + host, Protocol.DEFAULT_PORT)
        val ports = listOf(remembered) +
            (Protocol.DEFAULT_PORT until Protocol.DEFAULT_PORT + Protocol.PORT_RANGE).filter { it != remembered }
        for (port in ports) {
            when (val result = session(host, port)) {
                Outcome.Unreachable -> continue
                else -> return result
            }
        }
        return Outcome.Unreachable
    }

    /** One WebSocket session; suspends until it ends. */
    private suspend fun session(host: String, port: Int): Outcome = suspendCancellableCoroutine { cont ->
        var opened = false
        var authorized = false
        val request = Request.Builder().url("ws://$host:$port/").build()
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                webSocket.send(ProtocolJson.encode(Hello(token, clientName())))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val message = ProtocolJson.decode(text) ?: return) {
                    is Welcome -> {
                        authorized = true
                        socket = webSocket
                        prefs.edit().putInt(KEY_PORT_PREFIX + host, port).apply()
                        _state.value = State.Connected(host, port, message)
                        Timber.i("[Receiver] Connected to ${message.deviceName} ($host:$port)")
                    }
                    is Status -> _status.value = message.status
                    else -> _messages.tryEmit(message)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                finish(webSocket, if (code == Protocol.CLOSE_UNAUTHORIZED && !authorized) Outcome.Unauthorized else Outcome.Closed)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                finish(webSocket, if (opened) Outcome.Closed else Outcome.Unreachable)

            private fun finish(webSocket: WebSocket, outcome: Outcome) {
                if (socket === webSocket) {
                    socket = null
                    _status.value = PlaybackStatus()
                }
                if (cont.isActive) cont.resume(outcome)
            }
        })
        cont.invokeOnCancellation { ws.cancel() }
    }

    private fun clientName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private fun newToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private const val PREFS = "receiver_client"
    private const val KEY_TOKEN = "token"
    private const val KEY_PORT_PREFIX = "port_"
    private const val INITIAL_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 15_000L
}

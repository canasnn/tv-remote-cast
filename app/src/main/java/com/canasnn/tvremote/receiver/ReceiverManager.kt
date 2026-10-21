package com.canasnn.tvremote.receiver

import android.content.Context
import android.content.SharedPreferences
import com.canasnn.tvremote.App
import com.canasnn.tvremote.utils.TvAppsRepository
import com.ruterika.protocol.Apps
import com.ruterika.protocol.GetApps
import com.ruterika.protocol.Load
import com.ruterika.protocol.LoadResult
import com.ruterika.protocol.MediaItem
import com.ruterika.protocol.Message
import com.ruterika.protocol.Protocol
import com.ruterika.protocol.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID

/**
 * Glue between the remote-control session and our TV receiver.
 *
 * The receiver can't bring its own screens to the front (Android blocks
 * background activity starts), but the TV's system remote service can: every
 * deep link we send with the remote's app-link launch opens in the
 * foreground. So this class sends the start / play / launch links through
 * the paired remote and everything else over [ReceiverClient].
 */
object ReceiverManager {

    enum class PlayOutcome { Started, NotInstalled, NoRemote, Failed }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: SharedPreferences
    @Volatile private var currentHost: String? = null

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("receiver_manager", Context.MODE_PRIVATE)
        ReceiverClient.init(context)

        scope.launch {
            ReceiverClient.state.collect { state ->
                if (state is ReceiverClient.State.Connected) {
                    prefs.edit().putBoolean(KEY_SEEN_PREFIX + state.host, true).apply()
                    ReceiverClient.send(GetApps(withIcons = false))
                }
            }
        }
        scope.launch {
            ReceiverClient.messages.collect(::onMessage)
        }
    }

    private fun onMessage(message: Message) {
        if (message is Apps) TvAppsRepository.setFromReceiver(message.apps)
    }

    /** The remote connected to [host]: keep a receiver link to the same TV. */
    fun onTvConnected(host: String) {
        currentHost = host
        ReceiverClient.connect(host)
    }

    fun onTvDisconnected() {
        currentHost = null
        ReceiverClient.disconnect()
    }

    /** True once our receiver has answered on this TV, i.e. it is installed. */
    fun isInstalledOn(host: String?): Boolean =
        host != null && prefs.getBoolean(KEY_SEEN_PREFIX + host, false)

    /**
     * Make sure the receiver is reachable. If it isn't, wake it (and hand it
     * our token) with the start link through the remote. This briefly brings
     * an invisible activity up on the TV, so it is only done on user actions.
     */
    suspend fun ensureConnected(timeoutMs: Long = WAKE_TIMEOUT_MS): Boolean {
        if (ReceiverClient.isConnected) return true
        val remote = App.app?.androidRemoteTv ?: return false
        if (currentHost == null) return false
        Timber.i("[Receiver] Not reachable, sending start link through the remote")
        withContext(Dispatchers.IO) { remote.sendAppLink(Protocol.startLink(ReceiverClient.token)) }
        ReceiverClient.retryNow()
        return ReceiverClient.awaitConnected(timeoutMs)
    }

    /** Play [items] on the TV, opening the player through the remote if needed. */
    suspend fun play(
        items: List<MediaItem>,
        startIndex: Int = 0,
        startPositionMs: Long? = 0L,
        autoAdvance: Boolean = true,
        repeatMode: RepeatMode = RepeatMode.OFF,
        preferredAudioLanguage: String? = null,
        preferredTextLanguage: String? = null,
        subtitleOffsetMs: Long = 0L
    ): PlayOutcome {
        if (App.app?.androidRemoteTv == null || currentHost == null) return PlayOutcome.NoRemote
        if (!ensureConnected()) {
            return if (isInstalledOn(currentHost)) PlayOutcome.Failed else PlayOutcome.NotInstalled
        }
        val sessionId = UUID.randomUUID().toString()
        val load = Load(
            sessionId = sessionId,
            items = items,
            startIndex = startIndex,
            startPositionMs = startPositionMs,
            autoAdvance = autoAdvance,
            repeatMode = repeatMode,
            preferredAudioLanguage = preferredAudioLanguage,
            preferredTextLanguage = preferredTextLanguage,
            subtitleOffsetMs = subtitleOffsetMs
        )
        val result = coroutineScope {
            // Subscribe before sending: the answer is not replayed.
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                    ReceiverClient.messages.filterIsInstance<LoadResult>().first { it.sessionId == sessionId }
                }
            }
            if (ReceiverClient.send(load)) waiter.await() else { waiter.cancel(); null }
        } ?: return PlayOutcome.Failed

        if (!result.accepted) return PlayOutcome.Failed
        if (result.needsLaunch) {
            val remote = App.app?.androidRemoteTv ?: return PlayOutcome.NoRemote
            withContext(Dispatchers.IO) { remote.sendAppLink(Protocol.playLink(sessionId)) }
        }
        return PlayOutcome.Started
    }

    /**
     * Open a TV app through the receiver's launch trampoline. Returns false
     * when the receiver isn't installed on this TV (caller falls back).
     */
    fun launchApp(component: String): Boolean {
        val host = currentHost ?: return false
        if (!ReceiverClient.isConnected && !isInstalledOn(host)) return false
        val remote = App.app?.androidRemoteTv ?: return false
        scope.launch { remote.sendAppLink(Protocol.launchLink(component)) }
        return true
    }

    /** Opens the receiver's Play Store page on the TV. */
    fun openStorePage() {
        val remote = App.app?.androidRemoteTv ?: return
        scope.launch { remote.sendAppLink(Protocol.STORE_LINK) }
    }

    private const val KEY_SEEN_PREFIX = "seen_"
    private const val WAKE_TIMEOUT_MS = 8_000L
    private const val LOAD_TIMEOUT_MS = 6_000L
}

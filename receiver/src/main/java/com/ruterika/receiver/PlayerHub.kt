package com.ruterika.receiver

import android.os.SystemClock
import com.ruterika.protocol.Load
import com.ruterika.protocol.Message
import com.ruterika.protocol.PlaybackStatus
import com.ruterika.protocol.PlayerEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process bridge between [ReceiverService] (network side) and
 * [PlayerActivity] (playback side). Both live in the same process.
 */
object PlayerHub {

    private val _commands = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    /** Commands for the visible player. */
    val commands: SharedFlow<Message> = _commands.asSharedFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 16)
    /** One-off player events (intro skipped, queue ended, …) for the phone. */
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    fun emit(event: PlayerEvent) {
        _events.tryEmit(event)
    }

    private val _status = MutableStateFlow(PlaybackStatus())
    val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    @Volatile
    var playerVisible: Boolean = false
        private set

    private var pendingLoad: Load? = null
    private var pendingSince = 0L

    /**
     * Hand a load to the player. Returns true when a visible player took it;
     * otherwise it is kept until the player opens (see [takePendingLoad]).
     */
    @Synchronized
    fun submitLoad(load: Load): Boolean {
        if (playerVisible && _commands.tryEmit(load)) {
            pendingLoad = null
            return true
        }
        pendingLoad = load
        pendingSince = SystemClock.elapsedRealtime()
        return false
    }

    /** Called by a freshly opened player. Stale loads are dropped. */
    @Synchronized
    fun takePendingLoad(): Load? {
        val load = pendingLoad ?: return null
        pendingLoad = null
        return load.takeIf { SystemClock.elapsedRealtime() - pendingSince < PENDING_TTL_MS }
    }

    fun sendCommand(message: Message): Boolean = playerVisible && _commands.tryEmit(message)

    fun onPlayerVisible(visible: Boolean) {
        playerVisible = visible
        _status.value = if (visible) _status.value.copy(playerVisible = true) else PlaybackStatus()
    }

    fun publish(status: PlaybackStatus) {
        _status.value = status.copy(playerVisible = playerVisible)
    }

    private const val PENDING_TTL_MS = 60_000L
}

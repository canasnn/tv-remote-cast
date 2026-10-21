package com.ruterika.receiver

import android.util.Log
import com.ruterika.protocol.Hello
import com.ruterika.protocol.Message
import com.ruterika.protocol.Protocol
import com.ruterika.protocol.ProtocolJson
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WebSocket endpoint the phone talks to. Only LAN peers are accepted, and a
 * peer must send a [Hello] with an allowed token within a few seconds.
 */
class ControlServer(
    port: Int,
    private val isTokenAllowed: (String) -> Boolean,
    private val onAuthenticated: (WebSocket, Hello) -> Unit,
    private val onMessage: (WebSocket, Message) -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    private val authenticated = ConcurrentHashMap.newKeySet<WebSocket>()
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "control-auth-timer") }

    init {
        isReuseAddr = true
        // Built-in ping/pong; drops dead phones after ~2 intervals.
        connectionLostTimeout = 20
    }

    override fun onStart() {
        Log.i(TAG, "Control server listening on $port")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val address = conn.remoteSocketAddress?.address
        if (address == null || !isLanAddress(address)) {
            Log.w(TAG, "Rejected non-LAN peer $address")
            conn.close(Protocol.CLOSE_UNAUTHORIZED, "lan only")
            return
        }
        timer.schedule({
            if (conn.isOpen && conn !in authenticated) conn.close(Protocol.CLOSE_UNAUTHORIZED, "no hello")
        }, AUTH_TIMEOUT_S, TimeUnit.SECONDS)
    }

    override fun onMessage(conn: WebSocket, text: String) {
        val message = ProtocolJson.decode(text) ?: return
        if (conn !in authenticated) {
            if (message is Hello && isTokenAllowed(message.token)) {
                authenticated += conn
                onAuthenticated(conn, message)
            } else {
                conn.close(Protocol.CLOSE_UNAUTHORIZED, "unauthorized")
            }
            return
        }
        onMessage(conn, message)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        authenticated -= conn
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.w(TAG, "WebSocket error", ex)
    }

    fun send(conn: WebSocket, message: Message) {
        if (conn.isOpen) runCatching { conn.send(ProtocolJson.encode(message)) }
    }

    fun broadcastAuthenticated(message: Message) {
        if (authenticated.isEmpty()) return
        val text = ProtocolJson.encode(message)
        authenticated.forEach { if (it.isOpen) runCatching { it.send(text) } }
    }

    fun shutdown() {
        timer.shutdownNow()
        runCatching { stop(1000) }
    }

    companion object {
        private const val TAG = "ControlServer"
        private const val AUTH_TIMEOUT_S = 5L

        fun isLanAddress(address: InetAddress): Boolean = when (address) {
            is Inet4Address -> address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress
            // Unique local (fc00::/7) and link-local IPv6.
            is Inet6Address -> address.isLinkLocalAddress || address.isLoopbackAddress ||
                (address.address[0].toInt() and 0xFE) == 0xFC
            else -> false
        }
    }
}

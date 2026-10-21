package com.ruterika.protocol

/**
 * Constants shared by the phone app and the TV receiver.
 *
 * The receiver runs a WebSocket server on the TV. The phone already knows the
 * TV's IP from the remote-control connection, so it connects directly; the
 * NSD record only helps when the port had to move.
 */
object Protocol {
    const val VERSION = 2

    /** Package of the TV receiver app. */
    const val RECEIVER_PACKAGE = "com.ruterika.receiver"

    const val DEFAULT_PORT = 47800
    /** The receiver tries DEFAULT_PORT..DEFAULT_PORT + PORT_RANGE - 1. */
    const val PORT_RANGE = 10
    const val NSD_SERVICE_TYPE = "_ruterika._tcp."

    /** WebSocket close code sent when the client's token is not accepted. */
    const val CLOSE_UNAUTHORIZED = 4001
    /** WebSocket close code sent when a newer client took over. */
    const val CLOSE_REPLACED = 4002

    // ── Deep links handled by the receiver ──────────────────────────────────
    // Sent by the phone through the Android TV remote protocol (app link
    // launch). The TV's system remote service starts them in the foreground,
    // which is how we open activities without ADB or overlay permissions.
    const val SCHEME = "ruterika-receiver"
    const val HOST_START = "start"
    const val HOST_PLAY = "play"
    const val HOST_LAUNCH = "launch"
    const val PARAM_TOKEN = "t"
    const val PARAM_SESSION = "s"
    const val PARAM_COMPONENT = "c"

    fun startLink(token: String) = "$SCHEME://$HOST_START?$PARAM_TOKEN=${encode(token)}"
    fun playLink(sessionId: String) = "$SCHEME://$HOST_PLAY?$PARAM_SESSION=${encode(sessionId)}"
    fun launchLink(component: String) = "$SCHEME://$HOST_LAUNCH?$PARAM_COMPONENT=${encode(component)}"

    /** Play Store page of the receiver; opened on the TV through the remote. */
    const val STORE_LINK = "market://details?id=$RECEIVER_PACKAGE"

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}

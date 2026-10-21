package com.ruterika.castcore.iptv

import java.net.URLDecoder
import java.net.URLEncoder

/** Builds and reads Xtream Codes `get.php` playlist links. */
object XtreamCodes {

    /** Stream container requested from the panel: MPEG-TS or HLS. */
    enum class Output(val value: String, val label: String) { TS("ts", "TS"), M3U8("m3u8", "M3U8 (HLS)") }

    data class Login(val server: String, val username: String, val password: String, val output: Output = Output.TS)

    /**
     * Returns `<server>/get.php?username=…&password=…&type=m3u_plus&output=…`.
     * [server] may omit the scheme (`http://` is assumed) and may be a pasted panel link
     * ending in `.php`, which is cut off.
     */
    fun buildUrl(server: String, username: String, password: String, output: Output = Output.TS): String =
        "${normalizeServer(server)}/get.php?username=${encode(username.trim())}&password=${encode(password.trim())}" +
            "&type=m3u_plus&output=${output.value}"

    /** Reads the login back from a `get.php` link, or null when [url] is not one. */
    fun parse(url: String): Login? {
        val base = url.trim().substringBefore('#')
        val path = base.substringBefore('?')
        if (!path.lowercase().endsWith("/get.php")) return null
        val query = base.substringAfter('?', "").split('&').associate {
            decode(it.substringBefore('=')).lowercase() to decode(it.substringAfter('=', ""))
        }
        val username = query["username"] ?: return null
        val password = query["password"] ?: return null
        val output = if (query["output"].equals("m3u8", ignoreCase = true) || query["output"].equals("hls", ignoreCase = true)) Output.M3U8 else Output.TS
        return Login(path.substringBeforeLast('/'), username, password, output)
    }

    private fun normalizeServer(server: String): String {
        var base = server.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        if (!base.contains("://")) base = "http://$base"
        val pathStart = base.indexOf('/', base.indexOf("://") + 3)
        if (pathStart >= 0 && base.lowercase().endsWith(".php")) base = base.substringBeforeLast('/')
        return base.trimEnd('/')
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}

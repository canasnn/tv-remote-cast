package com.ruterika.castcore.web

/**
 * Access to the injected page script `web/detector.js`.
 *
 * The phone app registers a JavaScript interface named [BRIDGE_NAME] with a `post(String)`
 * method, injects [source] into every frame (e.g. via `WebViewCompat.addDocumentStartJavaScript`
 * or `evaluateJavascript` on page start) and forwards every posted string to
 * [PageMediaCollector.onJsMessage].
 */
object DetectorScript {
    /** Name of the JavaScript interface object the script posts to (`window.RuterikaBridge`). */
    const val BRIDGE_NAME: String = "RuterikaBridge"

    /** Classpath location of the script. */
    const val RESOURCE: String = "web/detector.js"

    /** JavaScript snippet that pauses every video/audio on the page (call before casting). */
    const val PAUSE_ALL_JS: String = "window.__ruterikaPauseAll&&window.__ruterikaPauseAll();"

    /** JavaScript snippet that forces an immediate re-scan of the page (and its frames). */
    const val RESCAN_JS: String = "window.__ruterikaScan&&window.__ruterikaScan();"

    /** Ad skip of every page video (seek to the end so the real video loads): "Manual 1". */
    const val SKIP_ALL_ADS_JS: String = "window.__ruterikaSkipAds&&window.__ruterikaSkipAds(1);"

    /** Smart ad skip: press skip buttons and fast-forward only videos that look like ads. */
    const val SKIP_LIKELY_ADS_JS: String = "window.__ruterikaSkipAds&&window.__ruterikaSkipAds(2);"

    /**
     * Document-start snippet for "Disable DASH": `MediaSource.isTypeSupported` answers false, so
     * players fall back to plain HLS/MP4 URLs the detector can see. Not applied on YouTube.
     */
    const val DISABLE_DASH_JS: String =
        "(()=>{try{if(/(^|\\.)youtube\\.com$/.test(location.hostname))return;" +
            "[window.MediaSource,window.ManagedMediaSource,window.WebKitMediaSource].forEach(function(M){" +
            "if(M)try{Object.defineProperty(M,'isTypeSupported',{value:function(){return false},configurable:true})}catch(e){}})}catch(e){}})();"

    /**
     * Document-start snippet of the sandbox fix: on top pages whose URL contains one of [pages]
     * (lower case), iframes lose their `sandbox` attribute so embedded players can run.
     */
    fun sandboxFixJs(pages: List<String>): String {
        val list = pages.filter { it.isNotBlank() }.joinToString(",") { jsString(it.lowercase()) }
        return "(()=>{try{var p=[$list],h=location.href.toLowerCase();" +
            "if(window.top!==window||!p.some(function(s){return h.indexOf(s)>=0}))return;" +
            "var f=function(n){if(n&&n.tagName==='IFRAME'&&n.hasAttribute('sandbox'))n.removeAttribute('sandbox')};" +
            "new MutationObserver(function(ms){ms.forEach(function(m){if(m.type==='attributes')f(m.target);" +
            "m.addedNodes.forEach(function(n){if(n.nodeType===1){f(n);if(n.querySelectorAll)n.querySelectorAll('iframe[sandbox]').forEach(f)}})})})" +
            ".observe(document,{childList:true,subtree:true,attributes:true,attributeFilter:['sandbox']})}catch(e){}})();"
    }

    private fun jsString(s: String): String = buildString {
        append('\'')
        s.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '<' -> append("\\x3c")
                else -> append(c)
            }
        }
        append('\'')
    }

    /** The script source (loaded once from the classpath). */
    val source: String by lazy { load() }

    /** Loads the script text from the classpath. */
    fun load(): String {
        val cl = DetectorScript::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
        val stream = cl.getResourceAsStream(RESOURCE) ?: error("Missing resource $RESOURCE")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

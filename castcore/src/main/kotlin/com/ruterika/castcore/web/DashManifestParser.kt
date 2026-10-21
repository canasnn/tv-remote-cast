package com.ruterika.castcore.web

import kotlinx.serialization.Serializable

/** One `<Representation>` of an MPEG-DASH manifest (attributes inherited from its AdaptationSet). */
@Serializable
data class DashRepresentation(
    val id: String? = null,
    val mimeType: String? = null,
    val codecs: String? = null,
    /** Bits per second (0 if missing). */
    val bandwidth: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    /** `video`, `audio`, `text` or `null` when it cannot be told. */
    val contentType: String? = null,
    val language: String? = null,
) {
    val isVideo: Boolean get() = contentType == "video"
    val isAudio: Boolean get() = contentType == "audio"
}

/** What we learn from an MPD: live/VOD, duration, qualities and protection. */
@Serializable
data class DashManifest(
    /** `type="dynamic"`. */
    val isLive: Boolean = false,
    /** `mediaPresentationDuration` in ms (null for live or when missing). */
    val durationMs: Long? = null,
    val representations: List<DashRepresentation> = emptyList(),
    /** Any `<ContentProtection>` present. */
    val isDrmProtected: Boolean = false,
    /** Readable DRM system names (`Widevine`, `PlayReady`, `FairPlay`, `ClearKey`, `CENC`). */
    val drmSystems: List<String> = emptyList(),
) {
    /** Video representations, best first, one per height/bandwidth. */
    val videoRepresentations: List<DashRepresentation>
        get() = representations.filter { it.isVideo }
            .distinctBy { (it.height ?: 0) to it.bandwidth }
            .sortedWith(compareByDescending<DashRepresentation> { it.height ?: 0 }.thenByDescending { it.bandwidth })

    val audioLanguages: List<String>
        get() = representations.filter { it.isAudio }.mapNotNull { it.language }.distinct()

    /** Video qualities as [HlsVariant]s pointing to [manifestUrl], for the quality chooser. */
    fun qualities(manifestUrl: String): List<HlsVariant> = videoRepresentations.map { r ->
        HlsVariant(
            url = manifestUrl,
            bandwidth = r.bandwidth,
            width = r.width,
            height = r.height,
            codecs = r.codecs,
            frameRate = r.frameRate,
        )
    }
}

/**
 * A small, tolerant MPD reader (no XML parser: it scans tags), enough for the video list:
 * live/VOD, duration, representation sizes and bitrates, and DRM.
 */
object DashManifestParser {

    private val TAG = Regex("<(/?)(?:[A-Za-z_][\\w.-]*:)?(MPD|Period|AdaptationSet|Representation|ContentProtection|ContentComponent)\\b([^>]*?)(/?)>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("([A-Za-z_][\\w:.-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
    private val DURATION = Regex("^P(?:(\\d+(?:\\.\\d+)?)Y)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)D)?(?:T(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?$")

    private val DRM_SYSTEMS = mapOf(
        "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" to "Widevine",
        "9a04f079-9840-4286-ab92-e65be0885f95" to "PlayReady",
        "79f0049a-4098-8642-ab92-e65be0885f95" to "PlayReady",
        "94ce86fb-07ff-4f43-adb8-93d2fa968ca2" to "FairPlay",
        "e2719d58-a985-b3c9-781a-b030af78d30e" to "ClearKey",
        "1077efec-c0b2-4d02-ace3-3c1e52e2fb4b" to "ClearKey",
    )

    /** True when [text] looks like an MPD document. */
    fun isDashManifest(text: String): Boolean {
        val head = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n').take(4096)
        return Regex("<(?:[A-Za-z_][\\w.-]*:)?MPD\\b", RegexOption.IGNORE_CASE).containsMatchIn(head)
    }

    /** Parses [text]; `null` when it is not an MPD. */
    fun parseOrNull(text: String): DashManifest? = if (isDashManifest(text)) runCatching { parse(text) }.getOrNull() else null

    /** Parses an MPD document. */
    fun parse(text: String): DashManifest {
        var live = false
        var duration: Long? = null
        val reps = ArrayList<DashRepresentation>()
        val drm = LinkedHashSet<String>()
        var protected = false
        var set: Map<String, String>? = null
        var setContentType: String? = null
        for (m in TAG.findAll(text)) {
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val selfClosing = m.groupValues[4] == "/"
            if (closing) {
                if (name == "adaptationset") {
                    set = null
                    setContentType = null
                }
                continue
            }
            val attrs = attributes(m.groupValues[3])
            when (name) {
                "mpd" -> {
                    live = attrs["type"].equals("dynamic", ignoreCase = true)
                    duration = attrs["mediapresentationduration"]?.let(::isoDurationMs)
                }
                "adaptationset" -> if (!selfClosing) {
                    set = attrs
                    setContentType = contentTypeOf(attrs["contenttype"], attrs["mimetype"], attrs["codecs"])
                }
                "contentcomponent" -> if (setContentType == null) setContentType = attrs["contenttype"]?.lowercase()
                "contentprotection" -> {
                    protected = true
                    val scheme = attrs["schemeiduri"].orEmpty().lowercase()
                    val uuid = scheme.removePrefix("urn:uuid:")
                    drm += DRM_SYSTEMS[uuid] ?: if (scheme.contains("mp4protection")) "CENC" else "DRM"
                }
                "representation" -> {
                    val s = set.orEmpty()
                    val mime = attrs["mimetype"] ?: s["mimetype"]
                    val codecs = attrs["codecs"] ?: s["codecs"]
                    val width = (attrs["width"] ?: s["width"])?.toIntOrNull()
                    val height = (attrs["height"] ?: s["height"])?.toIntOrNull()
                    val type = contentTypeOf(attrs["contenttype"], mime, codecs) ?: setContentType
                        ?: if (width != null || height != null) "video" else null
                    reps += DashRepresentation(
                        id = attrs["id"],
                        mimeType = mime,
                        codecs = codecs,
                        bandwidth = attrs["bandwidth"]?.toLongOrNull() ?: 0L,
                        width = width,
                        height = height,
                        frameRate = (attrs["framerate"] ?: s["framerate"])?.let(::frameRate),
                        contentType = type,
                        language = attrs["lang"] ?: s["lang"],
                    )
                }
            }
        }
        return DashManifest(
            isLive = live,
            durationMs = if (live) null else duration,
            representations = reps,
            isDrmProtected = protected,
            drmSystems = drm.toList(),
        )
    }

    /** `PT1H2M3.5S` → 3723500. `null` when malformed. */
    fun isoDurationMs(value: String): Long? {
        val m = DURATION.matchEntire(value.trim().uppercase()) ?: return null
        val g = m.groupValues.drop(1).map { it.toDoubleOrNull() ?: 0.0 }
        val seconds = g[0] * 365 * 86400 + g[1] * 30 * 86400 + g[2] * 86400 + g[3] * 3600 + g[4] * 60 + g[5]
        return (seconds * 1000).toLong().takeIf { it > 0 }
    }

    private fun attributes(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        ATTR.findAll(s).forEach { a ->
            val key = a.groupValues[1].substringAfterLast(':').lowercase()
            out[key] = xmlUnescape(a.groupValues[2].ifEmpty { a.groupValues[3] })
        }
        return out
    }

    private fun contentTypeOf(contentType: String?, mime: String?, codecs: String?): String? {
        contentType?.lowercase()?.let { if (it == "video" || it == "audio" || it == "text") return it }
        val m = mime?.lowercase().orEmpty()
        return when {
            m.startsWith("video/") -> "video"
            m.startsWith("audio/") -> "audio"
            m.startsWith("text/") || m.contains("ttml") || m.contains("vtt") -> "text"
            else -> when (HlsPlaylistParser.codecsContainVideo(codecs)) {
                true -> "video"
                false -> if (codecs.isNullOrBlank()) null else "audio"
                null -> null
            }
        }
    }

    private fun frameRate(v: String): Double? {
        val parts = v.split('/')
        val n = parts[0].toDoubleOrNull() ?: return null
        val d = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
        return if (d > 0) n / d else null
    }

    private fun xmlUnescape(s: String): String =
        if (!s.contains('&')) s else s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
}

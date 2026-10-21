package com.ruterika.castcore.subtitles

import java.text.Normalizer
import java.util.Locale

/**
 * A search query derived from a video title or file name by [SubtitleNaming.cleanTitle].
 *
 * @property query cleaned title suitable for a subtitle search (may be empty if nothing usable remained).
 * @property year release year, if found.
 * @property season season number for series, if found.
 * @property episode episode number for series, if found.
 */
data class TitleQuery(
    val query: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    /** True when a season or episode number was found. */
    val isEpisode: Boolean get() = season != null || episode != null
}

/** Language and title helpers for subtitle files and searches. */
object SubtitleNaming {

    private class Language(val code: String, val english: String, val native: String, val aliases: List<String>)

    private val LANGUAGES = listOf(
        Language("tr", "Turkish", "Türkçe", listOf("tur", "turkish", "turkce", "türkçe", "turk")),
        Language("en", "English", "English", listOf("eng", "english", "ingilizce")),
        Language("de", "German", "Deutsch", listOf("ger", "deu", "german", "deutsch", "almanca")),
        Language("fr", "French", "Français", listOf("fre", "fra", "french", "francais", "français", "fransizca")),
        Language("es", "Spanish", "Español", listOf("spa", "spanish", "espanol", "español", "castellano", "ispanyolca")),
        Language("it", "Italian", "Italiano", listOf("ita", "italian", "italiano")),
        Language("ru", "Russian", "Русский", listOf("rus", "russian", "русский")),
        Language("ar", "Arabic", "العربية", listOf("ara", "arabic", "arapca", "arapça")),
        Language("pt", "Portuguese", "Português", listOf("por", "portuguese", "portugues", "português", "pt-pt")),
        Language("pt-BR", "Portuguese (Brazil)", "Português (Brasil)", listOf("pob", "ptbr", "pt-br", "pb", "brazilian", "brazilian portuguese")),
        Language("nl", "Dutch", "Nederlands", listOf("dut", "nld", "dutch", "nederlands")),
        Language("pl", "Polish", "Polski", listOf("pol", "polish", "polski")),
        Language("sv", "Swedish", "Svenska", listOf("swe", "swedish", "svenska")),
        Language("no", "Norwegian", "Norsk", listOf("nor", "nob", "nb", "norwegian", "norsk")),
        Language("da", "Danish", "Dansk", listOf("dan", "danish", "dansk")),
        Language("fi", "Finnish", "Suomi", listOf("fin", "finnish", "suomi")),
        Language("el", "Greek", "Ελληνικά", listOf("gre", "ell", "greek")),
        Language("he", "Hebrew", "עברית", listOf("heb", "hebrew", "iw")),
        Language("fa", "Persian", "فارسی", listOf("per", "fas", "persian", "farsi")),
        Language("ja", "Japanese", "日本語", listOf("jpn", "japanese")),
        Language("ko", "Korean", "한국어", listOf("kor", "korean")),
        Language("zh-CN", "Chinese (Simplified)", "简体中文", listOf("chi", "zho", "chs", "zh", "zh-hans", "chinese", "zh-cn")),
        Language("zh-TW", "Chinese (Traditional)", "繁體中文", listOf("cht", "zht", "zh-hant", "zh-tw")),
        Language("hi", "Hindi", "हिन\u094Dदी", listOf("hin", "hindi")),
        Language("ro", "Romanian", "Română", listOf("rum", "ron", "romanian")),
        Language("hu", "Hungarian", "Magyar", listOf("hun", "hungarian", "magyar")),
        Language("cs", "Czech", "Čeština", listOf("cze", "ces", "czech")),
        Language("sk", "Slovak", "Slovenčina", listOf("slo", "slk", "slovak")),
        Language("bg", "Bulgarian", "Български", listOf("bul", "bulgarian")),
        Language("uk", "Ukrainian", "Українська", listOf("ukr", "ukrainian")),
        Language("sr", "Serbian", "Srpski", listOf("srp", "scc", "serbian")),
        Language("hr", "Croatian", "Hrvatski", listOf("hrv", "scr", "croatian")),
        Language("bs", "Bosnian", "Bosanski", listOf("bos", "bosnian")),
        Language("sl", "Slovenian", "Slovenščina", listOf("slv", "slovenian")),
        Language("az", "Azerbaijani", "Azərbaycanca", listOf("aze", "azerbaijani")),
        Language("id", "Indonesian", "Bahasa Indonesia", listOf("ind", "indonesian")),
        Language("ms", "Malay", "Bahasa Melayu", listOf("may", "msa", "malay")),
        Language("th", "Thai", "ไทย", listOf("tha", "thai")),
        Language("vi", "Vietnamese", "Tiếng Việt", listOf("vie", "vietnamese")),
        Language("ku", "Kurdish", "Kurdî", listOf("kur", "kurdish", "kurtce", "kürtçe")),
        Language("ka", "Georgian", "ქართული", listOf("geo", "kat", "georgian")),
        Language("sq", "Albanian", "Shqip", listOf("alb", "sqi", "albanian")),
        Language("mk", "Macedonian", "Македонски", listOf("mac", "mkd", "macedonian")),
        Language("et", "Estonian", "Eesti", listOf("est", "estonian")),
        Language("lv", "Latvian", "Latviešu", listOf("lav", "latvian")),
        Language("lt", "Lithuanian", "Lietuvių", listOf("lit", "lithuanian")),
        Language("ca", "Catalan", "Català", listOf("cat", "catalan")),
        Language("eu", "Basque", "Euskara", listOf("baq", "eus", "basque")),
    )

    private val BY_KEY: Map<String, Language> = buildMap {
        for (lang in LANGUAGES) {
            put(fold(lang.code), lang)
            put(fold(lang.english), lang)
            lang.aliases.forEach { put(fold(it), lang) }
        }
    }

    /**
     * Normalizes a language code, ISO 639-2 code or language name (`TUR`, `turkish`,
     * `pt_br`, `Türkçe`) to the canonical code used by OpenSubtitles (`tr`, `pt-BR`), or null.
     */
    fun normalizeLanguage(value: String): String? = BY_KEY[fold(value.replace('_', '-'))]?.code

    /**
     * True when [value] (a code, ISO 639-2 code or language name, e.g. a web page track label)
     * means the same language as [code] (`en`, `tr`…). Regional variants match their base
     * language (`pt-BR` matches `pt`).
     */
    fun sameLanguage(value: String?, code: String?): Boolean {
        if (value.isNullOrBlank() || code.isNullOrBlank()) return false
        val a = normalizeLanguage(value.trim()) ?: value.trim().substringBefore('-').substringBefore('_').lowercase()
        val b = normalizeLanguage(code.trim()) ?: code.trim().substringBefore('-').substringBefore('_').lowercase()
        return a.equals(b, ignoreCase = true) || a.substringBefore('-').equals(b.substringBefore('-'), ignoreCase = true)
    }

    /**
     * English display name for a language code (`tr` → `Turkish`, `pt-BR` →
     * `Portuguese (Brazil)`). Unknown codes fall back to [Locale]; failing that the code itself.
     */
    fun displayName(code: String): String =
        BY_KEY[fold(code.replace('_', '-'))]?.english ?: localeName(code, Locale.ENGLISH) ?: code

    /** Native display name for a language code (`tr` → `Türkçe`, `de` → `Deutsch`). */
    fun nativeName(code: String): String =
        BY_KEY[fold(code.replace('_', '-'))]?.native ?: localeName(code, null) ?: code

    private fun localeName(code: String, inLocale: Locale?): String? {
        val locale = Locale.forLanguageTag(code.replace('_', '-'))
        val name = if (inLocale != null) locale.getDisplayName(inLocale) else locale.getDisplayName(locale)
        return name.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
            ?.replaceFirstChar { it.titlecase(inLocale ?: locale) }
    }

    private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub", "ttml", "dfxp", "xml", "txt", "smi", "idx")
    private val FLAG_TOKENS = setOf("forced", "sdh", "hi", "cc", "default", "full", "hearing", "impaired", "hearingimpaired", "sign", "signs")

    /**
     * Guesses the subtitle language from a file name such as `movie.tr.srt`,
     * `Movie.Turkish.srt`, `movie.pt-BR.forced.srt` or `movie_eng.srt`. Only the last
     * name component (ignoring flags like `forced`/`sdh`) is considered, to avoid matching
     * words in the title. Returns the canonical code or null.
     */
    fun guessLanguage(fileName: String): String? {
        var name = fileName.substringBefore('?').substringAfterLast('/').substringAfterLast('\\')
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in SUBTITLE_EXTENSIONS) name = name.substringBeforeLast('.')
        val tokens = name.split(Regex("""[\s._\[\]()\-]+""")).filter { it.isNotEmpty() }.toMutableList()
        while (tokens.isNotEmpty() && fold(tokens.last()) in FLAG_TOKENS) tokens.removeAt(tokens.size - 1)
        if (tokens.isEmpty()) return null
        if (tokens.size >= 2) {
            BY_KEY[fold(tokens[tokens.size - 2] + "-" + tokens.last())]?.let { return it.code }
        }
        return BY_KEY[fold(tokens.last())]?.code
    }

    // ---- title cleaning ----------------------------------------------------------------------

    private val VIDEO_EXTENSION = Regex(
        """\.(mkv|mp4|m4v|avi|mov|wmv|flv|webm|ts|m2ts|mpg|mpeg|3gp|srt|vtt|ass|ssa|sub)$""",
        RegexOption.IGNORE_CASE,
    )
    private val SITE_SEGMENT = Regex(
        """(?iu)\.(com|net|org|tv|io|co|me|xyz)\b|youtube|netflix|vimeo|dailymotion|twitch|prime video|disney|hbo|blutv|exxen|puhutv|gain|hdfilm|filmizle|dizibox|dizipal|\bizle\b""",
    )
    private val SXXEYY = Regex("""(?iu)(?<![a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,3})(?![0-9])""")
    private val NXNN = Regex("""(?iu)(?<![a-z0-9])(\d{1,2})x(\d{2,3})(?![0-9])""")
    private val SEASON_EPISODE_WORDS = Regex("""(?iu)\bseason\s*(\d{1,2})\s*[,\-]?\s*episode\s*(\d{1,3})""")
    private val TR_SEASON_EPISODE = Regex("""(?iu)(\d{1,2})\s*\.?\s*sezon\s*(\d{1,3})\s*\.?\s*b[öo]l[üu]m""")
    private val TR_SEASON_EPISODE2 = Regex("""(?iu)sezon\s*(\d{1,2})\s*b[öo]l[üu]m\s*(\d{1,3})""")
    private val SEASON_ONLY = Regex("""(?iu)(?<![a-z0-9])(?:s(\d{1,2})(?![0-9a-z])|season\s*(\d{1,2})\b|(\d{1,2})\s*\.?\s*sezon\b)""")
    private val YEAR = Regex("""(?<![0-9])(19[2-9]\d|20[0-4]\d)(?![0-9])""")
    private val RELEASE_TAG = Regex(
        """(?iu)(?<=^|[\s._\-\[(])(?:2160p|1080p|1080i|720p|576p|480p|4k|uhd|hdr10\+?|hdr|dolby[\s._-]?vision|x26[45]|h[\s.]?26[45]|hevc|avc|aac(?:\d(?:[.\s]\d)?)?|ac3|eac3|dts(?:-?hd)?|truehd|ddp?\d(?:[.\s]\d)?|atmos|web-?dl|web-?rip|bluray|blu-ray|brrip|bdrip|bd-?rip|dvdrip|dvdscr|hdtv|hdrip|hdcam|camrip|hc|remux|repack|10bit|8bit|amzn|dsnp|hmax|atvp|nf|xvid|divx|türkçe|turkce|altyaz[ıi]l[ıi]|dublaj(?:l[ıi])?|full[\s._-]?hd|tek[\s._-]?par[çc]a|hd[\s._-]?izle|izle)(?=$|[\s._\-\])])""",
    )
    private val JUNK_WORDS = Regex(
        """(?iu)(?<=^|\s)(?:extended|unrated|remastered|proper|internal|imax|director'?s\s+cut|izle|online|full\s+hd|hd|türkçe|turkce|altyaz[ıi]l[ıi]|dublaj(?:l[ıi])?|tek\s+par[çc]a|official\s+trailer|trailer|fragman)(?=$|\s)""",
    )

    /**
     * Turns a web video title or file name into a subtitle search query. Strips the file
     * extension, site suffixes (`- YouTube`, `| Netflix`), bracketed release groups, resolution,
     * codec and release tags (and common Turkish streaming-site words like "Türkçe Altyazılı
     * İzle"), and extracts the year and `SxxEyy` / `1x02` / `Season 1 Episode 2` /
     * `1. Sezon 2. Bölüm` numbering.
     */
    fun cleanTitle(raw: String): TitleQuery {
        var s = raw.trim()
        if (VIDEO_EXTENSION.containsMatchIn(s)) {
            s = s.substringAfterLast('/').substringAfterLast('\\').replace(VIDEO_EXTENSION, "")
        }
        // "Title | Season 4 Episode 1 | Site": the first part is the title, the rest may hold numbering.
        val pipeParts = s.split(" | ", " · ").filter { it.isNotBlank() }
        s = (pipeParts.firstOrNull() ?: s).trim()
        val extra = pipeParts.drop(1).joinToString(" ")
        val segments = s.split(Regex("""\s+[-–—]\s+"""))
        if (segments.size > 1) {
            val kept = segments.toMutableList()
            while (kept.size > 1 && SITE_SEGMENT.containsMatchIn(kept.last())) kept.removeAt(kept.size - 1)
            s = kept.joinToString(" - ")
        }
        // Bracketed groups are release groups/sites unless they hold just a year.
        s = s.replace(Regex("""\[\s*((?:19|20)\d{2})\s*\]"""), " ($1) ").replace(Regex("""\[[^\]]*\]|\{[^}]*\}"""), " ")

        var season: Int? = null
        var episode: Int? = null
        var cut = s.length
        for (re in listOf(SXXEYY, NXNN, SEASON_EPISODE_WORDS, TR_SEASON_EPISODE, TR_SEASON_EPISODE2)) {
            val m = re.find(s) ?: continue
            if (m.range.first < cut) {
                cut = m.range.first
                season = m.groupValues[1].toInt()
                episode = m.groupValues[2].toInt()
            }
        }
        if (season == null) {
            SEASON_ONLY.find(s)?.takeIf { it.range.first > 0 }?.let { m ->
                season = (1..3).firstNotNullOfOrNull { m.groupValues[it].toIntOrNull() }
                cut = m.range.first
            }
        }
        RELEASE_TAG.find(s)?.takeIf { it.range.first in 1 until cut }?.let { cut = it.range.first }

        var year: Int? = null
        YEAR.findAll(s.substring(0, cut)).lastOrNull { it.range.first > 0 }?.let { m ->
            year = m.value.toInt()
            cut = m.range.first
        }
        if (year == null) {
            // Year after the episode marker or after tags, e.g. "Show S01E01 (2019)".
            YEAR.findAll(s).firstOrNull { it.range.first > cut }?.let { year = it.value.toInt() }
        }

        if (season == null && extra.isNotEmpty()) {
            for (re in listOf(SXXEYY, NXNN, SEASON_EPISODE_WORDS, TR_SEASON_EPISODE, TR_SEASON_EPISODE2)) {
                val m = re.find(extra) ?: continue
                season = m.groupValues[1].toInt()
                episode = m.groupValues[2].toInt()
                break
            }
        }
        if (year == null && extra.isNotEmpty()) YEAR.find(extra)?.let { year = it.value.toInt() }

        val query = tidy(s.substring(0, cut)).ifEmpty { tidy(s) }
        return TitleQuery(query, year, season, episode)
    }

    private fun tidy(value: String): String {
        var t = value.replace('.', ' ').replace('_', ' ')
        t = t.replace(Regex("""\(\s*\)"""), " ")
        t = JUNK_WORDS.replace(t, " ")
        t = t.replace(Regex("""\s+"""), " ").trim()
        t = t.trim(' ', '-', '–', '—', ':', '|', ',', '(', '[', '·', '/')
        return t.replace(Regex("""\(\s*$"""), "").trim()
    }

    private fun fold(value: String): String {
        val lower = value.trim().lowercase(Locale.ROOT).replace('ı', 'i')
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
    }
}

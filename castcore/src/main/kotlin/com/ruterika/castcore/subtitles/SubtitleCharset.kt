package com.ruterika.castcore.subtitles

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Result of [SubtitleCharset.decode]: the decoded [text] (never starts with a BOM) and the
 * [charset] that was used to decode it.
 */
data class DecodedText(val text: String, val charset: Charset)

/**
 * Charset detection for subtitle files.
 *
 * Subtitles found in the wild are frequently not UTF-8: Turkish files are usually
 * windows-1254 (or ISO-8859-9), Russian ones windows-1251, Arabic ones windows-1256 and so on.
 * Decoding them with the wrong charset produces mojibake on the TV, so the phone normalizes
 * everything to UTF-8 before serving it.
 *
 * Detection order:
 * 1. Byte order mark (UTF-8, UTF-16LE, UTF-16BE) - always authoritative.
 * 2. BOM-less UTF-16 (detected from the distribution of zero bytes).
 * 3. Strict UTF-8: if the bytes contain non-ASCII data and are valid UTF-8, it is UTF-8.
 *    Legacy 8-bit text is practically never valid multi-byte UTF-8 by accident, so this check
 *    deliberately runs before the preferred charset (otherwise a user preference of
 *    windows-1254 would turn every UTF-8 file into mojibake).
 * 4. The `preferred` charset, if the bytes decode cleanly in it (no unmappable bytes and no
 *    C1 control characters).
 * 5. Pure ASCII: UTF-8.
 * 6. A letter-frequency heuristic over windows-1254, windows-1252, windows-1251,
 *    windows-1256, ISO-8859-9 and windows-1250. Each code page is scored against the
 *    languages that use it (e.g. Turkish for 1254; French, German, Spanish... for 1252):
 *    letters of the language score positively, letters foreign to every language of the code
 *    page, undefined bytes, C1 controls, words mixing scripts and words with random case
 *    changes score negatively. Ties go to the earlier code page in the list above.
 */
object SubtitleCharset {

    /** Turkish Windows code page. */
    val WINDOWS_1254: Charset get() = Charset.forName("windows-1254")

    /** Western European Windows code page. */
    val WINDOWS_1252: Charset get() = Charset.forName("windows-1252")

    /** Cyrillic Windows code page. */
    val WINDOWS_1251: Charset get() = Charset.forName("windows-1251")

    /** Arabic Windows code page. */
    val WINDOWS_1256: Charset get() = Charset.forName("windows-1256")

    /** Central European Windows code page. */
    val WINDOWS_1250: Charset get() = Charset.forName("windows-1250")

    /** Latin-5 (Turkish) ISO code page. */
    val ISO_8859_9: Charset get() = Charset.forName("ISO-8859-9")

    /**
     * Decodes subtitle [bytes] to text, detecting the charset as described on [SubtitleCharset].
     *
     * @param preferred a charset hint (e.g. from user settings or an HTTP `Content-Type`).
     *   It is used when the bytes are not UTF-8 and decode cleanly in it.
     */
    fun decode(bytes: ByteArray, preferred: Charset? = null): DecodedText {
        detectBom(bytes)?.let { (charset, bomLength) ->
            val text = String(bytes, bomLength, bytes.size - bomLength, charset)
            return DecodedText(text.removePrefix(BOM_CHAR), charset)
        }
        detectBomlessUtf16(bytes)?.let { charset ->
            return DecodedText(String(bytes, charset).removePrefix(BOM_CHAR), charset)
        }

        val hasNonAscii = bytes.any { it < 0 }
        val utf8 = strictDecode(bytes, Charsets.UTF_8)
        if (utf8 != null && hasNonAscii) return DecodedText(utf8.removePrefix(BOM_CHAR), Charsets.UTF_8)

        if (preferred != null && !preferred.isUtf16or32()) {
            val text = strictDecode(bytes, preferred)
            if (text != null && text.none { it.code in 0x80..0x9F }) {
                return DecodedText(text.removePrefix(BOM_CHAR), preferred)
            }
        }
        if (utf8 != null) return DecodedText(utf8, Charsets.UTF_8)

        var best: DecodedText? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (candidate in candidates) {
            val text = String(bytes, candidate.charset) // undefined bytes become U+FFFD
            val score = score(text, candidate.languages)
            if (score > bestScore) {
                bestScore = score
                best = DecodedText(text, candidate.charset)
            }
        }
        return best ?: DecodedText(String(bytes, Charsets.ISO_8859_1), Charsets.ISO_8859_1)
    }

    /**
     * Returns the plausibility score of [bytes] decoded with [charset] (higher is better), or
     * NaN if [charset] is not one of the heuristic candidates. Exposed for diagnostics and tests.
     */
    fun scoreFor(bytes: ByteArray, charset: Charset): Double {
        val candidate = candidates.firstOrNull { it.charset == charset } ?: return Double.NaN
        return score(String(bytes, charset), candidate.languages)
    }

    // ---------------------------------------------------------------------------------------

    private const val BOM_CHAR = "\uFEFF"

    /** Only this many chars are scored; plenty to decide and keeps big files cheap. */
    private const val SCORE_SAMPLE = 200_000

    private fun Charset.isUtf16or32(): Boolean =
        name().uppercase().let { it.startsWith("UTF-16") || it.startsWith("UTF-32") }

    private fun detectBom(b: ByteArray): Pair<Charset, Int>? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
        else -> null
    }

    private fun detectBomlessUtf16(b: ByteArray): Charset? {
        val n = minOf(b.size, 4096) and 1.inv()
        if (n < 8) return null
        var zeroEven = 0
        var zeroOdd = 0
        for (i in 0 until n) {
            if (b[i].toInt() == 0) if (i % 2 == 0) zeroEven++ else zeroOdd++
        }
        val half = n / 2.0
        return when {
            zeroOdd / half > 0.4 && zeroEven / half < 0.05 -> Charsets.UTF_16LE
            zeroEven / half > 0.4 && zeroOdd / half < 0.05 -> Charsets.UTF_16BE
            else -> null
        }
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }

    // ---- heuristic ---------------------------------------------------------------------------

    /** A language model: weight of each expected non-ASCII char, null when unexpected. */
    private fun interface Language {
        fun weight(c: Char): Double?
    }

    private class Candidate(val charset: Charset, val languages: List<Language>)

    /** Builds a language from lower-case letters; upper-case forms are added automatically. */
    private fun letters(common: String, rare: String = ""): Language {
        val map = HashMap<Char, Double>()
        for (c in common) {
            map[c] = 1.0
            map[c.uppercaseChar()] = 1.0
        }
        for (c in rare) {
            map[c] = 0.5
            map[c.uppercaseChar()] = 0.5
        }
        return Language { map[it] }
    }

    private val TURKISH = Language { c ->
        when (c) {
            '\u015F', '\u015E', '\u011F', '\u011E', '\u0131', '\u0130' -> 1.5 // s-cedilla, g-breve, dotless i, dotted I
            '\u00E7', '\u00C7', '\u00F6', '\u00D6', '\u00FC', '\u00DC' -> 1.0 // c-cedilla, o/u-umlaut
            '\u00E2', '\u00C2', '\u00EE', '\u00CE', '\u00FB', '\u00DB' -> 0.5 // circumflex vowels
            else -> null
        }
    }

    // windows-1252 languages
    private val FRENCH = letters("\u00E9\u00E8\u00E0\u00E7\u00EA\u00E2\u00F4\u00EE\u00FB\u00F9\u00EB\u00EF\u00FC\u0153", "\u00FF\u00E6")
    private val GERMAN = letters("\u00E4\u00F6\u00FC\u00DF")
    private val SPANISH = letters("\u00E1\u00E9\u00ED\u00F3\u00FA\u00F1\u00FC")
    private val PORTUGUESE = letters("\u00E1\u00E2\u00E3\u00E0\u00E7\u00E9\u00EA\u00ED\u00F3\u00F4\u00F5\u00FA", "\u00FC")
    private val ITALIAN = letters("\u00E0\u00E8\u00E9\u00EC\u00F2\u00F9", "\u00ED\u00F3\u00FA\u00EE")
    private val NORDIC = letters("\u00E5\u00E4\u00F6\u00E6\u00F8", "\u00E9")
    private val DUTCH = letters("\u00EB\u00EF\u00E9\u00E8", "\u00F6\u00FC\u00E1\u00F3")

    // windows-1250 languages
    private val POLISH = letters("\u0105\u0107\u0119\u0142\u0144\u00F3\u015B\u017A\u017C")
    private val CZECH = letters("\u00E1\u010D\u010F\u00E9\u011B\u00ED\u0148\u00F3\u0159\u0161\u0165\u00FA\u016F\u00FD\u017E")
    private val SLOVAK = letters("\u00E1\u00E4\u010D\u010F\u00E9\u00ED\u0148\u00F3\u00F4\u0161\u0165\u00FA\u00FD\u017E", "\u013A\u013E\u0155")
    private val HUNGARIAN = letters("\u00E1\u00E9\u00ED\u00F3\u00F6\u0151\u00FA\u00FC\u0171")
    private val ROMANIAN = letters("\u0103\u00E2\u00EE\u015F\u0163")
    private val CROATIAN = letters("\u010D\u0107\u0111\u0161\u017E")

    private val CYRILLIC = Language { c -> if (c in '\u0400'..'\u04FF') 1.5 else null }

    private val ARABIC = Language { c ->
        when (c) {
            in '\u0600'..'\u06FF' -> 1.5
            '\u200C', '\u200D', '\u200E', '\u200F' -> 0.0 // ZWNJ, ZWJ, LRM, RLM
            else -> null
        }
    }

    private val candidates: List<Candidate> by lazy {
        listOf(
            "windows-1254" to listOf(TURKISH),
            "windows-1252" to listOf(FRENCH, GERMAN, SPANISH, PORTUGUESE, ITALIAN, NORDIC, DUTCH),
            "windows-1251" to listOf(CYRILLIC),
            "windows-1256" to listOf(ARABIC),
            "ISO-8859-9" to listOf(TURKISH),
            "windows-1250" to listOf(POLISH, CZECH, SLOVAK, HUNGARIAN, ROMANIAN, CROATIAN),
        ).mapNotNull { (name, languages) ->
            runCatching { Candidate(Charset.forName(name), languages) }.getOrNull()
        }
    }

    /** Non-letter, non-ASCII characters that are normal in any subtitle (no score impact). */
    private const val NEUTRAL =
        "\u00A0\u201C\u201D\u2018\u2019\u201E\u201A\u2013\u2014\u2026\u00AB\u00BB\u2039\u203A\u2022\u00B0\u00B7" +
            "\u20AC\u00A3\u00BF\u00A1\u266A\u266B\u2122\u00A9\u00AE\u200B\u200E\u200F"

    private fun score(text: String, languages: List<Language>): Double =
        languages.maxOf { score(text, it) }

    private fun score(text: String, language: Language): Double {
        var total = 0.0
        val word = StringBuilder()
        fun flush() {
            if (word.isNotEmpty()) {
                total += scoreWord(word, language)
                word.setLength(0)
            }
        }
        val end = minOf(text.length, SCORE_SAMPLE)
        for (i in 0 until end) {
            val c = text[i]
            when {
                c.code < 0x80 -> if (c.isLetter()) word.append(c) else flush()
                c == '\uFFFD' || c.code in 0x80..0x9F -> {
                    flush()
                    total -= 5.0
                }
                language.weight(c) != null -> word.append(c)
                Character.isLetter(c) -> word.append(c)
                NEUTRAL.indexOf(c) >= 0 -> flush()
                else -> {
                    flush()
                    total -= 1.0
                }
            }
        }
        flush()
        return total
    }

    private fun scoreWord(word: CharSequence, language: Language): Double {
        var bonus = 0.0
        var penalty = 0.0
        var latin = 0
        var nonLatin = 0
        var caseFlips = 0
        for (i in word.indices) {
            val c = word[i]
            if (c.code < 0x80) {
                latin++
            } else {
                val w = language.weight(c)
                if (w == null) penalty += 1.0 else bonus += w
                if (c in '\u0370'..'\u06FF') nonLatin++ else latin++
            }
            if (i > 0 && c.isUpperCase() && word[i - 1].isLowerCase()) caseFlips++
        }
        if (latin > 0 && nonLatin > 0) {
            // Mixed scripts inside one word: almost certainly the wrong code page.
            bonus = 0.0
            penalty += 3.0
        }
        penalty += 3.0 * caseFlips
        return bonus - penalty
    }
}

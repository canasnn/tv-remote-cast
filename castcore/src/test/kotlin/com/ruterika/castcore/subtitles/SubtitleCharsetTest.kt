package com.ruterika.castcore.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class SubtitleCharsetTest {

    private val turkish = "Şimdi gitmemiz gerekiyor, çünkü hava çok soğuk ve ışıklar söndü.\n" +
        "İstanbul'a yarın döneceğiz; ağabeyim de bizimle geliyor mu?"

    private val turkishSrt = """
        1
        00:00:01,000 --> 00:00:03,500
        Neden buradasın? Işıkları kapat.

        2
        00:00:04,000 --> 00:00:06,000
        Gözlerini aç, şimdi değil!
        Öğretmen geliyor.
    """.trimIndent()

    private val cp1254 = Charset.forName("windows-1254")
    private val cp1252 = Charset.forName("windows-1252")
    private val cp1251 = Charset.forName("windows-1251")
    private val cp1256 = Charset.forName("windows-1256")
    private val cp1250 = Charset.forName("windows-1250")
    private val iso88599 = Charset.forName("ISO-8859-9")

    @Test
    fun turkishWindows1254IsDetected() {
        val decoded = SubtitleCharset.decode(turkish.toByteArray(cp1254))
        assertEquals(cp1254, decoded.charset)
        assertEquals(turkish, decoded.text)
    }

    @Test
    fun turkishSrtInWindows1254IsDetected() {
        val decoded = SubtitleCharset.decode(turkishSrt.toByteArray(cp1254))
        assertEquals(cp1254, decoded.charset)
        assertEquals(turkishSrt, decoded.text)
    }

    @Test
    fun turkishScoresHigherAs1254ThanOtherCandidates() {
        val bytes = turkish.toByteArray(cp1254)
        val s1254 = SubtitleCharset.scoreFor(bytes, cp1254)
        for (other in listOf(cp1252, cp1251, cp1256, cp1250)) {
            assertTrue("1254 should beat $other", s1254 > SubtitleCharset.scoreFor(bytes, other))
        }
    }

    @Test
    fun turkishInUtf8IsDetected() {
        val decoded = SubtitleCharset.decode(turkish.toByteArray(Charsets.UTF_8))
        assertEquals(Charsets.UTF_8, decoded.charset)
        assertEquals(turkish, decoded.text)
    }

    @Test
    fun utf8WinsOverSingleBytePreference() {
        val decoded = SubtitleCharset.decode(turkish.toByteArray(Charsets.UTF_8), preferred = cp1254)
        assertEquals(Charsets.UTF_8, decoded.charset)
        assertEquals(turkish, decoded.text)
    }

    @Test
    fun frenchWindows1252IsDetected() {
        val french = "Le café était déjà fermé, ça m'a énervé à côté de l'hôtel. Où êtes-vous ?"
        val decoded = SubtitleCharset.decode(french.toByteArray(cp1252))
        assertEquals(cp1252, decoded.charset)
        assertEquals(french, decoded.text)
    }

    @Test
    fun germanWindows1252IsDetected() {
        val german = "Die Straße ist schön, aber wir müssen früh gehen. Größer als ich dachte!"
        val decoded = SubtitleCharset.decode(german.toByteArray(cp1252))
        assertEquals(cp1252, decoded.charset)
        assertEquals(german, decoded.text)
    }

    @Test
    fun russianWindows1251IsDetected() {
        val russian = "Привет, как дела? Мы должны идти домой сейчас.\nЯ не знаю, что делать."
        val decoded = SubtitleCharset.decode(russian.toByteArray(cp1251))
        assertEquals(cp1251, decoded.charset)
        assertEquals(russian, decoded.text)
    }

    @Test
    fun arabicWindows1256IsDetected() {
        val arabic = "مرحبا كيف حالك؟ يجب أن نذهب إلى المنزل الآن.\nلا أعرف ماذا أفعل."
        val decoded = SubtitleCharset.decode(arabic.toByteArray(cp1256))
        assertEquals(cp1256, decoded.charset)
        assertEquals(arabic, decoded.text)
    }

    @Test
    fun polishWindows1250IsDetected() {
        val polish = "Zażółć gęślą jaźń. Proszę, chodźmy już do domu, bo robi się późno."
        val decoded = SubtitleCharset.decode(polish.toByteArray(cp1250))
        assertEquals(cp1250, decoded.charset)
        assertEquals(polish, decoded.text)
    }

    @Test
    fun frenchWithGraveAccentsIsNotCentralEuropean() {
        val french = "Après le dîner, très tôt, mon frère est allé à la fenêtre. Voilà, c'est la dernière fois."
        val decoded = SubtitleCharset.decode(french.toByteArray(cp1252))
        assertEquals(cp1252, decoded.charset)
        assertEquals(french, decoded.text)
    }

    @Test
    fun czechWindows1250IsDetected() {
        val czech = "Příliš žluťoučký kůň úpěl ďábelské ódy. Děkuji, že jste přišli."
        val decoded = SubtitleCharset.decode(czech.toByteArray(cp1250))
        assertEquals(cp1250, decoded.charset)
        assertEquals(czech, decoded.text)
    }

    @Test
    fun ukrainianWindows1251IsDetected() {
        val text = "Добрий вечір! Я не знаю, де мої ключі. Їжак біжить додому."
        val decoded = SubtitleCharset.decode(text.toByteArray(cp1251))
        assertEquals(cp1251, decoded.charset)
        assertEquals(text, decoded.text)
    }

    @Test
    fun utf8BomIsStripped() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + turkish.toByteArray(Charsets.UTF_8)
        val decoded = SubtitleCharset.decode(bytes)
        assertEquals(Charsets.UTF_8, decoded.charset)
        assertEquals(turkish, decoded.text)
    }

    @Test
    fun utf16LeBom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + turkish.toByteArray(Charsets.UTF_16LE)
        val decoded = SubtitleCharset.decode(bytes, preferred = cp1254)
        assertEquals(Charsets.UTF_16LE, decoded.charset)
        assertEquals(turkish, decoded.text)
    }

    @Test
    fun utf16BeBom() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + turkish.toByteArray(Charsets.UTF_16BE)
        val decoded = SubtitleCharset.decode(bytes)
        assertEquals(Charsets.UTF_16BE, decoded.charset)
        assertEquals(turkish, decoded.text)
    }

    @Test
    fun bomlessUtf16LeIsDetected() {
        val decoded = SubtitleCharset.decode(turkishSrt.toByteArray(Charsets.UTF_16LE))
        assertEquals(Charsets.UTF_16LE, decoded.charset)
        assertEquals(turkishSrt, decoded.text)
    }

    @Test
    fun preferredCharsetWinsWhenValid() {
        // The same bytes are valid ISO-8859-9 and windows-1252; the preference decides.
        val bytes = turkish.toByteArray(cp1254)
        assertEquals(iso88599, SubtitleCharset.decode(bytes, preferred = iso88599).charset)
        assertEquals(turkish, SubtitleCharset.decode(bytes, preferred = iso88599).text)
        assertEquals(cp1252, SubtitleCharset.decode(bytes, preferred = cp1252).charset)
    }

    @Test
    fun preferredCharsetIgnoredWhenBytesAreInvalidInIt() {
        // 0x81 is undefined in windows-1252, and a C1 control in ISO-8859-9.
        val bytes = turkish.toByteArray(cp1254) + byteArrayOf(0x81.toByte())
        val decoded = SubtitleCharset.decode(bytes, preferred = cp1252)
        assertTrue(decoded.charset != cp1252)
        assertTrue(decoded.text.startsWith(turkish))
    }

    @Test
    fun asciiIsUtf8OrPreferred() {
        val ascii = "1\n00:00:01,000 --> 00:00:02,000\nHello there\n".toByteArray(Charsets.US_ASCII)
        assertEquals(Charsets.UTF_8, SubtitleCharset.decode(ascii).charset)
        assertEquals(cp1254, SubtitleCharset.decode(ascii, preferred = cp1254).charset)
        assertEquals(Charsets.UTF_8, SubtitleCharset.decode(ascii, preferred = Charsets.UTF_16LE).charset)
    }

    @Test
    fun emptyInput() {
        val decoded = SubtitleCharset.decode(ByteArray(0))
        assertEquals("", decoded.text)
    }

    @Test
    fun turkishWithTypographicQuotesStays1254() {
        val text = "“Şu an ne yapıyorsun?” diye sordu… — Hiçbir şey, öğleden sonra görüşürüz."
        val decoded = SubtitleCharset.decode(text.toByteArray(cp1254))
        assertEquals(cp1254, decoded.charset)
        assertEquals(text, decoded.text)
        assertFalse(decoded.text.contains('�'))
    }
}

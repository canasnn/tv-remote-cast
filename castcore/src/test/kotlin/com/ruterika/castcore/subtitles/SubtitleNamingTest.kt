package com.ruterika.castcore.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleNamingTest {

    @Test
    fun displayNames() {
        assertEquals("Turkish", SubtitleNaming.displayName("tr"))
        assertEquals("English", SubtitleNaming.displayName("EN"))
        assertEquals("German", SubtitleNaming.displayName("de"))
        assertEquals("Portuguese (Brazil)", SubtitleNaming.displayName("pt-BR"))
        assertEquals("Portuguese (Brazil)", SubtitleNaming.displayName("pt_br"))
        assertEquals("Portuguese", SubtitleNaming.displayName("pt"))
        assertEquals("Arabic", SubtitleNaming.displayName("ara"))
        assertEquals("Chinese (Traditional)", SubtitleNaming.displayName("zh-TW"))
        // Not in the table: falls back to java.util.Locale.
        assertEquals("Welsh", SubtitleNaming.displayName("cy"))
        assertEquals("qq", SubtitleNaming.displayName("qq"))
    }

    @Test
    fun nativeNames() {
        assertEquals("Türkçe", SubtitleNaming.nativeName("tr"))
        assertEquals("Deutsch", SubtitleNaming.nativeName("de"))
        assertEquals("Русский", SubtitleNaming.nativeName("ru"))
    }

    @Test
    fun normalizeLanguage() {
        assertEquals("tr", SubtitleNaming.normalizeLanguage("TUR"))
        assertEquals("tr", SubtitleNaming.normalizeLanguage("Türkçe"))
        assertEquals("tr", SubtitleNaming.normalizeLanguage("turkish"))
        assertEquals("de", SubtitleNaming.normalizeLanguage("ger"))
        assertEquals("pt-BR", SubtitleNaming.normalizeLanguage("pob"))
        assertEquals("zh-CN", SubtitleNaming.normalizeLanguage("chi"))
        assertNull(SubtitleNaming.normalizeLanguage("klingon"))
    }

    @Test
    fun guessLanguageFromFileName() {
        assertEquals("tr", SubtitleNaming.guessLanguage("movie.tr.srt"))
        assertEquals("tr", SubtitleNaming.guessLanguage("Movie.2010.1080p.Turkish.srt"))
        assertEquals("tr", SubtitleNaming.guessLanguage("Film.Türkçe.srt"))
        assertEquals("en", SubtitleNaming.guessLanguage("/sdcard/Download/movie.eng.forced.srt"))
        assertEquals("en", SubtitleNaming.guessLanguage("movie.en.sdh.vtt"))
        assertEquals("pt-BR", SubtitleNaming.guessLanguage("movie.pt-BR.srt"))
        assertEquals("pt-BR", SubtitleNaming.guessLanguage("movie_pt_br.srt"))
        assertEquals("de", SubtitleNaming.guessLanguage("movie [German].ass"))
        assertEquals("it", SubtitleNaming.guessLanguage("https://x.com/subs/movie.ita.srt?x=1"))
        assertNull(SubtitleNaming.guessLanguage("movie.srt"))
        assertNull(SubtitleNaming.guessLanguage("The.Italian.Job.2003.srt"))
        assertNull(SubtitleNaming.guessLanguage("Movie.2010.1080p.srt"))
    }

    @Test
    fun cleanFileNameMovie() {
        assertEquals(
            TitleQuery("The Matrix", 1999),
            SubtitleNaming.cleanTitle("The.Matrix.1999.1080p.BluRay.x264-GROUP.mkv"),
        )
        assertEquals(
            TitleQuery("Blade Runner 2049", 2017),
            SubtitleNaming.cleanTitle("Blade.Runner.2049.2017.2160p.UHD.BluRay.REMUX.HDR.HEVC.Atmos-EPSiLON.mkv"),
        )
        assertEquals(TitleQuery("1917", 2019), SubtitleNaming.cleanTitle("1917.2019.720p.WEB-DL.DDP5.1.H.264.mp4"))
        assertEquals(TitleQuery("2012", 2009), SubtitleNaming.cleanTitle("2012 (2009) 1080p.mkv"))
        assertEquals(TitleQuery("Inception"), SubtitleNaming.cleanTitle("Inception.1080p.x265.mkv"))
        assertEquals(TitleQuery("Spider-Man No Way Home", 2021), SubtitleNaming.cleanTitle("Spider-Man.No.Way.Home.2021.1080p.WEBRip.x264.AAC5.1-[YTS.MX].mp4"))
    }

    @Test
    fun cleanFileNameEpisode() {
        val q = SubtitleNaming.cleanTitle("Breaking.Bad.S05E14.Ozymandias.720p.HDTV.x264-IMMERSE.mkv")
        assertEquals(TitleQuery("Breaking Bad", null, 5, 14), q)
        assertTrue(q.isEpisode)
        assertEquals(TitleQuery("The Office US", 2005, 2, 1), SubtitleNaming.cleanTitle("The.Office.US.2005.S02E01.mkv"))
        assertEquals(TitleQuery("Friends", null, 1, 2), SubtitleNaming.cleanTitle("Friends 1x02 The One with the Sonogram.avi"))
        assertEquals(TitleQuery("Dark", null, 3), SubtitleNaming.cleanTitle("Dark.S03.1080p.NF.WEB-DL"))
        assertEquals(TitleQuery("Show", 2019, 1, 1), SubtitleNaming.cleanTitle("[SubGroup] Show S01E01 (2019) [1080p].mkv"))
    }

    @Test
    fun cleanWebTitles() {
        assertEquals(
            TitleQuery("Inception", 2010),
            SubtitleNaming.cleanTitle("Inception (2010) Türkçe Altyazılı Full HD İzle - HDFilmCehennemi"),
        )
        assertEquals(
            TitleQuery("Kurtlar Vadisi", null, 2, 15),
            SubtitleNaming.cleanTitle("Kurtlar Vadisi 2. Sezon 15. Bölüm İzle | Dizibox"),
        )
        assertEquals(TitleQuery("Oppenheimer", 2023), SubtitleNaming.cleanTitle("Oppenheimer (2023) - YouTube"))
        assertEquals(TitleQuery("Stranger Things", null, 4, 1), SubtitleNaming.cleanTitle("Stranger Things | Season 4 Episode 1 | Netflix"))
        assertEquals(
            TitleQuery("Stranger Things", null, 4, 1),
            SubtitleNaming.cleanTitle("Stranger Things Season 4 Episode 1 - Netflix"),
        )
        assertEquals(TitleQuery("Mr Robot"), SubtitleNaming.cleanTitle("Mr. Robot"))
        assertEquals(TitleQuery("Dune: Part Two", 2024), SubtitleNaming.cleanTitle("Dune: Part Two (2024) TÜRKÇE DUBLAJ 1080p izle"))
    }

    @Test
    fun cleanTitleEdgeCases() {
        assertEquals(TitleQuery(""), SubtitleNaming.cleanTitle(""))
        assertEquals(TitleQuery("1080p"), SubtitleNaming.cleanTitle("1080p"))
        assertFalse(SubtitleNaming.cleanTitle("Movie").isEpisode)
    }

    @Test
    fun sameLanguageMatchesCodesNamesAndRegions() {
        assertTrue(SubtitleNaming.sameLanguage("English", "en"))
        assertTrue(SubtitleNaming.sameLanguage("eng", "en"))
        assertTrue(SubtitleNaming.sameLanguage("tr", "TR"))
        assertTrue(SubtitleNaming.sameLanguage("pt-BR", "pt"))
        assertFalse(SubtitleNaming.sameLanguage("de", "en"))
        assertFalse(SubtitleNaming.sameLanguage(null, "en"))
        assertFalse(SubtitleNaming.sameLanguage("en", ""))
    }
}

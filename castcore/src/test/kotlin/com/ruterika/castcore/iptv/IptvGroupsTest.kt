package com.ruterika.castcore.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvGroupsTest {

    private fun entry(title: String, group: String? = null, tvgName: String? = null) =
        M3uEntry(title = title, url = "http://x/${title.hashCode()}.ts", group = group, tvgName = tvgName)

    @Test
    fun groupsPreserveFirstSeenOrderWithUngroupedLast() {
        val entries = listOf(
            entry("TRT 1", "Ulusal"),
            entry("Yabancı", null),
            entry("beIN Sports 1", "Spor"),
            entry("Show TV", "Ulusal"),
            entry("Boş", "   "),
            entry("TRT Spor", "Spor"),
            entry("Minika", "Çocuk"),
        )
        val groups = IptvGroups.group(entries)
        assertEquals(listOf("Ulusal", "Spor", "Çocuk", IptvGroups.UNGROUPED), groups.map { it.name })
        assertEquals(listOf("TRT 1", "Show TV"), groups[0].entries.map { it.title })
        assertEquals(listOf("beIN Sports 1", "TRT Spor"), groups[1].entries.map { it.title })
        assertEquals(listOf("Yabancı", "Boş"), groups.last().entries.map { it.title })
    }

    @Test
    fun noUngroupedBucketWhenEverythingHasAGroup() {
        val groups = IptvGroups.group(listOf(entry("A", "G1"), entry("B", "G2")))
        assertEquals(listOf("G1", "G2"), groups.map { it.name })
        assertTrue(IptvGroups.group(emptyList()).isEmpty())
    }

    @Test
    fun foldHandlesTurkishAndDiacritics() {
        assertEquals("istanbul", IptvGroups.fold("İSTANBUL"))
        assertEquals("istanbul", IptvGroups.fold("ıstanbul"))
        assertEquals("istanbul", IptvGroups.fold("ISTANBUL"))
        assertEquals("sogus guc cicek", IptvGroups.fold("ŞÖĞÜŞ GÜÇ ÇİÇEK"))
        assertEquals("cafe creme", IptvGroups.fold("Café Crème"))
        assertEquals("trt 1 hd", IptvGroups.fold("TRT 1 HD"))
    }

    @Test
    fun searchIsCaseAndDiacriticInsensitive() {
        val entries = listOf(
            entry("TRT Çocuk"),
            entry("Kanal D"),
            entry("Show TV"),
            entry("Bein", tvgName = "beIN Sports Haber"),
            entry("İz TV"),
            entry("Dizi: Kuruluş Osman S05E01"),
        )
        assertEquals(listOf("TRT Çocuk"), IptvGroups.search(entries, "cocuk").map { it.title })
        assertEquals(listOf("TRT Çocuk"), IptvGroups.search(entries, "ÇOCUK").map { it.title })
        assertEquals(listOf("İz TV", "Dizi: Kuruluş Osman S05E01"), IptvGroups.search(entries, "İZ").map { it.title })
        assertEquals(listOf("İz TV"), IptvGroups.search(entries, "IZ tv").map { it.title })
        assertEquals(listOf("Dizi: Kuruluş Osman S05E01"), IptvGroups.search(entries, "kurulus osman").map { it.title })
        assertEquals(listOf("Bein"), IptvGroups.search(entries, "sports haber").map { it.title })
        assertEquals(listOf("Show TV", "İz TV"), IptvGroups.search(entries, "tv").map { it.title })
        assertTrue(IptvGroups.search(entries, "fox").isEmpty())
    }

    @Test
    fun blankSearchReturnsEverything() {
        val entries = listOf(entry("A"), entry("B"))
        assertSame(entries, IptvGroups.search(entries, "   "))
    }

    @Test
    fun detectsPlaylistUrls() {
        assertTrue(IptvGroups.looksLikePlaylistUrl("http://x/list.m3u"))
        assertTrue(IptvGroups.looksLikePlaylistUrl("http://x/list.M3U8?token=1"))
        assertTrue(IptvGroups.looksLikePlaylistUrl("https://x/tv.m3u.gz"))
        assertTrue(IptvGroups.looksLikePlaylistUrl("http://x/list.m3u|User-Agent=abc"))
        assertTrue(IptvGroups.looksLikePlaylistUrl("http://x:8080/get.php?username=u&password=p&type=m3u_plus&output=ts"))
        assertFalse(IptvGroups.looksLikePlaylistUrl("http://x/live/u/p/123.ts"))
        assertFalse(IptvGroups.looksLikePlaylistUrl("http://x/movie.mp4?type=video"))
        assertFalse(IptvGroups.looksLikePlaylistUrl("http://x/get.php?username=u&output=m3u8"))
        assertFalse(IptvGroups.looksLikePlaylistUrl("http://x/m3u8/stream.ts"))
    }
}

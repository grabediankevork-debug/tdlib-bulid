package com.couchreel.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

private fun day(iso: String): Long = LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toEpochSecond()

private fun mk(n: Int, fileName: String, chat: String, sizeGb: Long, durationSec: Int, date: String): CatalogItem =
    CatalogItem(
        key = "-1001_$n", chatId = -1001, chatTitle = chat, msgId = n.toLong(), fileName = fileName,
        size = sizeGb * 1073741824L, mime = "video/x-matroska", durationSec = durationSec, caption = "", dateSec = day(date),
    )

private fun movie(n: Int, name: String, year: Int, gb: Long, date: String) =
    mk(n, "${name.replace(' ', '.')}.$year.1080p.mkv", "Chan A", gb, 6000, date)

private fun ep(n: Int, season: Int, episode: Int, title: String = "Cool Show", gb: Long = 1, date: String = "2026-10-01") =
    mk(n, "${title.replace(' ', '.')}.S%02dE%02d.720p.mkv".format(season, episode), "Chan B", gb, 2400, date)

private fun sample(): List<CatalogItem> = listOf(
    movie(1, "The Matrix", 1999, 4, "2026-09-01"),
    movie(2, "The Matrix", 1999, 2, "2026-09-02"), // duplicate, smaller
    movie(3, "Heat", 1995, 3, "2026-10-05"),
    movie(4, "Amelie", 2001, 2, "2026-10-03"),
    ep(10, 1, 1), ep(11, 1, 2), ep(12, 1, 3), ep(13, 2, 1),
    ep(14, 1, 1, gb = 2), // bigger duplicate of S01E01
    ep(20, 1, 1, "Other Show", date = "2026-10-09"), ep(21, 1, 2, "Other Show", date = "2026-10-09"),
)

class CatalogTest {
    private fun lib(state: MemoryState = MemoryState()) = Catalog(sample(), state)

    @Test
    fun duplicatesHiddenByDefault() {
        val res = lib().library("movie", "", false, "added", 0, 60)
        assertEquals(1, res.items.count { it.title == "The Matrix" })
        val m = res.items.first { it.title == "The Matrix" }
        assertEquals("-1001_1", m.id) // biggest file wins
        assertEquals(2, m.versions)
        assertEquals(3, res.total)
    }

    @Test
    fun showAllFilesToggle() {
        val res = lib().library("movie", "", true, "added", 0, 60)
        assertEquals(4, res.total)
        assertTrue(res.items.all { it.versions == 1 })
    }

    @Test
    fun sortAndSearch() {
        val c = lib()
        assertEquals("Amelie", c.library("movie", "", false, "title", 0, 60).items[0].title)
        assertEquals("Heat", c.library("movie", "", false, "added", 0, 60).items[0].title)
        assertEquals(listOf("The Matrix"), c.library("movie", "matr", false, "added", 0, 60).items.map { it.title })
    }

    @Test
    fun seriesGroupingAndDuplicates() {
        val c = lib()
        val det = c.seriesDetail("cool show")!!
        assertEquals(4, det.episodes)
        assertEquals(listOf(1, 2), det.seasons.map { it.season })
        assertEquals(listOf(1, 2, 3), det.seasons[0].episodes.map { it.episode })
        assertEquals("-1001_14", det.seasons[0].episodes[0].id) // bigger duplicate shown
        assertEquals(1, det.nextUp!!.episode)
        assertEquals(setOf("cool show", "other show"), c.seriesList("", 0, 60).items.map { it.sid }.toSet())
        assertNull(c.seriesDetail("nope"))
    }

    @Test
    fun watchedFlowAndNextUp() {
        val c = lib()
        c.setWatched("-1001_14", true) // marks both S01E01 files
        var det = c.seriesDetail("cool show")!!
        assertTrue(det.seasons[0].episodes[0].watched)
        assertEquals(1, det.watched)
        assertEquals(1, det.nextUp!!.season)
        assertEquals(2, det.nextUp!!.episode)
        val row = c.home(emptyList(), 0).nextUp.first { it.sid == "cool show" }
        assertEquals(2, row.episode)
        c.setWatched("-1001_10", false) // the other file of the same work
        det = c.seriesDetail("cool show")!!
        assertFalse(det.seasons[0].episodes[0].watched)
    }

    @Test
    fun progressResumeAndAutoWatched() {
        val c = lib()
        assertEquals(false, c.progress("-1001_3", 600_000, 6_000_000))
        var home = c.home(emptyList(), 0)
        assertEquals(listOf("-1001_3"), home.continueWatching.map { it.id })
        assertEquals(600_000L, home.continueWatching[0].positionMs)
        assertEquals(true, c.progress("-1001_3", 5_500_000, 6_000_000)) // >= 90 %
        home = c.home(emptyList(), 0)
        assertTrue(home.continueWatching.isEmpty())
        assertFalse(home.recent.any { it.title == "Heat" })
        assertEquals(true, c.progress("-1001_4", 5_900_000, 6_000_000)) // last two minutes
        assertNull(c.progress("nope", 1, 2))
    }

    @Test
    fun seriesWatchedToggleAndNextOf() {
        val c = lib()
        assertTrue(c.setSeriesWatched("cool show", true))
        assertEquals(4, c.seriesDetail("cool show")!!.watched)
        assertNull(c.seriesDetail("cool show")!!.nextUp)
        c.setSeriesWatched("cool show", false)
        assertEquals(0, c.seriesDetail("cool show")!!.watched)
        assertEquals("-1001_12", c.nextOf("-1001_11")!!.id)
        assertEquals(2, c.nextOf("-1001_12")!!.season)
        assertNull(c.nextOf("-1001_13"))
        assertNull(c.nextOf("-1001_3")) // a movie
    }

    @Test
    fun searchRecordsRecentOnlyWhenSaved() {
        val c = lib()
        c.search("heat", true)
        c.search("cool", true)
        assertEquals(emptyList<String>().size, 0)
        val res = c.search("matrix", true)
        assertEquals(listOf("matrix", "cool", "heat"), res.searches.take(3))
        assertEquals("The Matrix", res.movies[0].title)
        assertEquals("matrix", c.search("x", true).searches[0]) // one-character queries are not stored
        assertEquals("matrix", c.search("mat", false).searches[0]) // previews are not stored
    }

    @Test
    fun watchlistImport() {
        val c = lib()
        val csv = "Date,Name,Year,Letterboxd URI\n2026-01-01,The Matrix,1999,u1\n2026-01-02,Stalker,1979,u2\n2026-01-03,Heat,1995,u3\n"
        assertEquals(Pair(3, 2), c.importWatchlist(csv))
        val wl = c.watchlist()
        assertEquals(2, wl.available)
        assertEquals(3, wl.total)
        assertEquals(listOf("Heat", "The Matrix", "Stalker"), wl.films.map { it.name }) // available first
        assertEquals("-1001_1", wl.films[1].item!!.id)
        assertNull(wl.films[2].item)
        assertEquals(2, c.watchlistAvailable())
    }

    @Test
    fun sourcesCountVideosPerChat() {
        val s = lib().sources()
        assertEquals(sample().size, s.sumOf { it.videos })
    }

    // ---------------------------------------------------------------- cable-style channels

    private val now: ZonedDateTime = ZonedDateTime.of(2026, 11, 1, 10, 30, 0, 0, ZoneOffset.UTC)

    @Test
    fun channelsAreBuilt() {
        val names = lib().channelList().map { it.name }
        assertTrue(names.toString(), "Movies: Mixed" in names)
        assertTrue("Series: Cool Show" in names)
        assertTrue("Series: Other Show" in names)
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun guideIsContiguousDeterministicAndHasOneOnNow() {
        val c = lib()
        val g1 = c.guide(6, now)
        val g2 = c.guide(6, now)
        assertEquals(g1.map { ch -> ch.programmes.map { it.key } }, g2.map { ch -> ch.programmes.map { it.key } })
        for (ch in g1) {
            assertTrue(ch.name, ch.programmes.isNotEmpty())
            assertEquals(ch.name, 1, ch.programmes.count { it.onNow })
            assertTrue(ch.programmes[0].onNow)
            for ((a, b) in ch.programmes.zipWithNext()) {
                assertEquals(ZonedDateTime.parse(a.end), ZonedDateTime.parse(b.start))
            }
        }
    }

    @Test
    fun liveKeyMatchesGuide() {
        val c = lib()
        for (ch in c.guide(3, now)) {
            val onNow = ch.programmes.first { it.onNow }
            assertEquals(onNow.key, c.liveKeyNow(ch.id, now))
        }
        assertNull(c.liveKeyNow("ch99", now))
    }

    @Test
    fun seriesChannelPlaysEpisodesInOrder() {
        val c = lib()
        val id = c.channelList().first { it.name == "Series: Cool Show" }.id
        val keys = c.guide(24, now.withHour(0)).first { it.id == id }.programmes.map { it.key }
        val order = listOf("-1001_14", "-1001_11", "-1001_12", "-1001_13") // S01E01 (bigger file), E02, E03, S02E01
        assertEquals(order, keys.take(4))
        assertNotNull(keys.getOrNull(4))
        assertEquals(order[0], keys[4]) // wraps around
    }
}

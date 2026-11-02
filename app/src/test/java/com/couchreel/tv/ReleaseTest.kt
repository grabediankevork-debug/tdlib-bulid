package com.couchreel.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun item(n: Int, fileName: String, sizeGb: Long = 2, caption: String = ""): CatalogItem =
    CatalogItem(
        key = "-1001_$n", chatId = -1001, chatTitle = "Chan A", msgId = n.toLong(), fileName = fileName,
        size = sizeGb * 1073741824L, mime = "video/x-matroska", durationSec = 0, caption = caption, dateSec = n.toLong(),
    )

class ReleaseTest {
    private fun check(name: String, title: String?, year: Int? = null, kind: String = "movie", season: Int? = null, episode: Int? = null) {
        val p = parseRelease(name)
        assertEquals(name, Parsed(title, year, season, episode, kind), p)
    }

    @Test
    fun parsesCommonReleaseNames() {
        check("The.Matrix.1999.1080p.BluRay.x264-GRP.mkv", "The Matrix", 1999)
        check("Blade.Runner.2049.2017.2160p.WEB-DL.mkv", "Blade Runner 2049", 2017)
        check("Inception (2010) [1080p] [YTS.MX].mp4", "Inception", 2010)
        check("Charlotte's Web 2006 720p.mkv", "Charlotte's Web", 2006)
        check("Spider-Man.2002.mkv", "Spider-Man", 2002)
        check("1917.2019.1080p.mkv", "1917", 2019)
        check("Show.Name.S02E05.720p.HDTV.mp4", "Show Name", null, "episode", 2, 5)
        check("Show Name 1x05 Pilot.avi", "Show Name", null, "episode", 1, 5)
        check("Some.Film.mkv", "Some Film")
        assertNull(parseRelease("").title)
        assertNull(parseRelease(null).title)
    }

    @Test
    fun genericFileNameFallsBackToCaption() {
        val p = bestParse("video.mp4", "Heat 1995 1080p\nsome text")
        assertEquals("Heat", p.title)
        assertEquals(1995, p.year)
    }

    @Test
    fun normalizeStripsAccentsArticlesAndPunctuation() {
        assertEquals("amelie", normalize("The Amélie!"))
        assertEquals("fast and furious", normalize("Fast & Furious"))
        assertEquals("", normalize(null))
    }

    @Test
    fun similarityMatchesDifflibForTypos() {
        val r = similarity("inception", "inceptoin")
        assertTrue("ratio was $r", r > 0.88 && r < 0.90)
        assertEquals(1.0, similarity("abc", "abc"), 1e-9)
        assertEquals(0.0, similarity("abc", "xyz"), 1e-9)
    }

    @Test
    fun csvHandlesQuotesAndBom() {
        val csv = "﻿Date,Name,Year,Letterboxd URI\r\n2026-01-01,\"Everything, Everywhere\",2022,u1\r\n2026-01-02,\"Say \"\"Hi\"\"\",,u2\r\n"
        val films = parseWatchlistCsv(csv)
        assertEquals(2, films.size)
        assertEquals(FilmQuery("Everything, Everywhere", 2022, "u1"), films[0])
        assertEquals("Say \"Hi\"", films[1].name)
        assertNull(films[1].year)
        assertEquals(0, parseWatchlistCsv("Foo,Bar\n1,2\n").size)
    }

    private val library = listOf(
        item(1, "The.Matrix.1999.1080p.mkv"),
        item(2, "The.Matrix.1999.720p.mkv", sizeGb = 1),
        item(3, "Amelie.2001.mkv"),
        item(4, "Heat.mkv"),
        item(5, "Inceptoin.2010.mkv"),
        item(6, "Dune.1984.mkv"),
    )

    private fun one(name: String, year: Int?): FilmMatch = matchFilms(listOf(FilmQuery(name, year)), library)[0]

    @Test
    fun matchingExactPrefersBiggestFile() {
        val m = one("The Matrix", 1999)
        assertEquals("exact", m.status)
        assertEquals(listOf("-1001_1", "-1001_2"), m.hits.map { it.key })
    }

    @Test
    fun matchingYearTolerance() {
        assertEquals("exact", one("Amélie", 2002).status)
        assertEquals("none", one("Dune", 2021).status)
        assertEquals("exact", one("Dune", 1984).status)
    }

    @Test
    fun matchingTitleOnlyFuzzyNone() {
        assertEquals("title-only", one("Heat", 1995).status)
        assertEquals("fuzzy", one("Inception", 2010).status)
        assertEquals("none", one("Nonexistent Movie", 2020).status)
    }
}

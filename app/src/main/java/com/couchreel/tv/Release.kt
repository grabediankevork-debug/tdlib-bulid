package com.couchreel.tv

import java.text.Normalizer
import java.util.Locale

// Kotlin port of tgcommon.py (release-name parsing, title normalisation, watchlist matching, CSV).
// Pure Kotlin on purpose: no Android classes, so it can be unit-tested on a laptop.

val VIDEO_EXT: Set<String> = setOf(".mkv", ".mp4", ".avi", ".mov", ".m4v", ".webm", ".wmv", ".mpg", ".mpeg", ".ts", ".m2ts")

private val QUALITY_WORDS: Set<String> = setOf(
    "4k", "uhd", "bluray", "blu", "bdrip", "brrip", "webrip", "webdl", "hdtv", "dvdrip", "hdrip",
    "x264", "x265", "h264", "h265", "hevc", "avc", "remux", "unrated", "proper", "repack",
    "hdr", "hdr10", "10bit", "aac", "ac3", "dts", "atmos", "amzn", "dsnp", "hmax",
    "yify", "yts", "rarbg", "imax", "hdcam", "telesync", "dvdscr", "bdremux",
)
private val RES_RE = Regex("\\d{3,4}[pi]", RegexOption.IGNORE_CASE)
private val YEAR_RE = Regex("(?:19|20)\\d\\d")
private val SE_RE = Regex("[sS](\\d{1,2})[eE](\\d{1,3}).*")
private val X_RE = Regex("(\\d{1,2})x(\\d{2,3})", RegexOption.IGNORE_CASE)
private val SEASON_ONLY_RE = Regex("[sS](\\d{1,2})")
private val GENERIC_RE = Regex("(vid|video|img|file|document|untitled)( \\d+)*")
private val BRACKETS_RE = Regex("\\[[^\\]]*\\]")
private val WWW_RE = Regex("www\\.\\S+", RegexOption.IGNORE_CASE)
private val AT_RE = Regex("@\\w+")
private val SEPARATORS_RE = Regex("[._]+")
private val PARENS_RE = Regex("[(){}\\[\\]]")
private val WHITESPACE_RE = Regex("\\s+")
private val NON_WORD_RE = Regex("(?U)[\\W_]+")
private val COMBINING_RE = Regex("\\p{Mn}+")
private val ARTICLE_RE = Regex("^(the|a|an) ")

data class Parsed(val title: String?, val year: Int?, val season: Int?, val episode: Int?, val kind: String)

private val EMPTY_PARSED = Parsed(null, null, null, null, "unknown")

private fun isQuality(tok: String): Boolean {
    val base = tok.lowercase(Locale.ROOT)
    val head = base.substringBefore('-')
    return base.replace("-", "") in QUALITY_WORDS || head in QUALITY_WORDS || RES_RE.matches(head)
}

/** "The.Matrix.1999.1080p.BluRay.x264-GRP.mkv" -> title "The Matrix", year 1999, kind "movie". Never throws. */
fun parseRelease(name: String?): Parsed {
    if (name.isNullOrBlank()) return EMPTY_PARSED
    var stem = name.trim()
    val dot = stem.lastIndexOf('.')
    if (dot > 0 && stem.substring(dot).lowercase(Locale.ROOT) in VIDEO_EXT) stem = stem.substring(0, dot)

    var s = BRACKETS_RE.replace(stem, " ")
    s = WWW_RE.replace(s, " ")
    s = AT_RE.replace(s, " ")
    s = SEPARATORS_RE.replace(s, " ")
    s = PARENS_RE.replace(s, " ")
    val tokens = s.trim().split(WHITESPACE_RE).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return EMPTY_PARSED

    var season: Int? = null
    var episode: Int? = null
    var stop = tokens.size
    for (i in tokens.indices) {
        if (i == 0) continue
        val tok = tokens[i]
        val se = SE_RE.matchEntire(tok) ?: X_RE.matchEntire(tok)
        if (se != null) {
            season = se.groupValues[1].toInt()
            episode = se.groupValues[2].toInt()
            stop = i
            break
        }
        val so = SEASON_ONLY_RE.matchEntire(tok)
        if (so != null) {
            season = so.groupValues[1].toInt()
            stop = i
            break
        }
        if (isQuality(tok)) {
            stop = i
            break
        }
    }

    var region = tokens.subList(0, stop)
    var year: Int? = null
    var yearIdx = -1
    for (i in region.indices) {
        if (i > 0 && YEAR_RE.matches(region[i])) yearIdx = i
    }
    if (yearIdx >= 0) {
        year = region[yearIdx].toInt()
        region = region.subList(0, yearIdx)
    }
    val title = region.joinToString(" ").trim { it == ' ' || it == '-' || it == ':' }
    if (title.isEmpty()) return Parsed(null, year, season, episode, "unknown")
    val kind = if (season != null || episode != null) "episode" else "movie"
    return Parsed(title, year, season, episode, kind)
}

/** Comparison-friendly title: accents stripped, lower case, punctuation collapsed, leading article removed. */
fun normalize(title: String?): String {
    if (title.isNullOrEmpty()) return ""
    var t = Normalizer.normalize(title, Normalizer.Form.NFKD)
    t = COMBINING_RE.replace(t, "")
    t = t.lowercase(Locale.ROOT).replace("&", " and ")
    t = NON_WORD_RE.replace(t, " ").trim()
    return ARTICLE_RE.replace(t, "")
}

/** Prefer the file name; fall back to the first caption lines when the name is generic ("video.mp4"). */
fun bestParse(fileName: String?, caption: String?): Parsed {
    val cands = ArrayList<String>()
    if (!fileName.isNullOrBlank()) cands.add(fileName)
    if (!caption.isNullOrEmpty()) {
        caption.lines().take(2).map { it.trim() }.filter { it.isNotEmpty() }.forEach { cands.add(it) }
    }
    for (c in cands) {
        val p = parseRelease(c)
        val n = normalize(p.title)
        if (p.title != null && n.isNotEmpty() && !GENERIC_RE.matches(n)) return p
    }
    return EMPTY_PARSED
}

// ------------------------------------------------------------------ catalog item

data class CatalogItem(
    val key: String,
    val chatId: Long,
    val chatTitle: String,
    val msgId: Long,
    val fileName: String,
    val size: Long,
    val mime: String,
    val durationSec: Int,
    val caption: String,
    val dateSec: Long,
) {
    val parsed: Parsed = bestParse(fileName, caption)
}

// ------------------------------------------------------------------ fuzzy matching (difflib-like)

private fun matchedChars(a: String, alo: Int, ahi: Int, b: String, blo: Int, bhi: Int): Int {
    if (alo >= ahi || blo >= bhi) return 0
    var best = 0
    var bestI = alo
    var bestJ = blo
    var prev = IntArray(bhi - blo + 1)
    for (i in alo until ahi) {
        val cur = IntArray(bhi - blo + 1)
        for (j in blo until bhi) {
            if (a[i] == b[j]) {
                val len = prev[j - blo] + 1
                cur[j - blo + 1] = len
                if (len > best) {
                    best = len
                    bestI = i - len + 1
                    bestJ = j - len + 1
                }
            }
        }
        prev = cur
    }
    if (best == 0) return 0
    return best +
        matchedChars(a, alo, bestI, b, blo, bestJ) +
        matchedChars(a, bestI + best, ahi, b, bestJ + best, bhi)
}

/** Ratcliff/Obershelp similarity 0..1, like Python's difflib.SequenceMatcher.ratio(). */
fun similarity(a: String, b: String): Double {
    if (a.isEmpty() && b.isEmpty()) return 1.0
    return 2.0 * matchedChars(a, 0, a.length, b, 0, b.length) / (a.length + b.length)
}

fun closeMatches(word: String, candidates: Collection<String>, n: Int, cutoff: Double): List<String> =
    candidates.map { it to similarity(word, it) }
        .filter { it.second >= cutoff }
        .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenByDescending { it.first })
        .take(n)
        .map { it.first }

// ------------------------------------------------------------------ film matching (Letterboxd watchlist)

data class FilmQuery(val name: String, val year: Int?, val uri: String = "")
data class FilmMatch(val film: FilmQuery, val status: String, val hits: List<CatalogItem>)

/**
 * status: exact (title equal, years within 1) | title-only (year missing on one side) |
 *         fuzzy (near-identical title, years within 1) | none
 */
fun matchFilms(films: List<FilmQuery>, items: List<CatalogItem>, cutoff: Double = 0.85): List<FilmMatch> {
    val byNorm = HashMap<String, MutableList<CatalogItem>>()
    for (it in items) {
        val p = it.parsed
        if (p.kind == "movie" && p.title != null) {
            val n = normalize(p.title)
            if (n.isNotEmpty()) byNorm.getOrPut(n) { mutableListOf() }.add(it)
        }
    }
    val keys = byNorm.keys.toList()

    fun near(y: Int?, iy: Int?): Boolean = y != null && iy != null && Math.abs(y - iy) <= 1

    return films.map { f ->
        val n = normalize(f.name)
        val y = f.year
        var hits: List<CatalogItem> = emptyList()
        var status = "none"
        val same = byNorm[n]
        if (same != null) {
            val strong = same.filter { near(y, it.parsed.year) }
            val weak = same.filter { y == null || it.parsed.year == null }
            if (strong.isNotEmpty()) {
                hits = strong
                status = "exact"
            } else if (weak.isNotEmpty()) {
                hits = weak
                status = "title-only"
            }
        }
        if (hits.isEmpty() && n.isNotEmpty()) {
            val fuzzy = ArrayList<CatalogItem>()
            for (k in closeMatches(n, keys, 5, cutoff)) {
                if (k == n) continue
                fuzzy.addAll(byNorm.getValue(k).filter { near(y, it.parsed.year) })
            }
            if (fuzzy.isNotEmpty()) {
                hits = fuzzy
                status = "fuzzy"
            }
        }
        FilmMatch(f, status, hits.sortedByDescending { it.size })
    }
}

// ------------------------------------------------------------------ CSV (Letterboxd export)

fun parseCsv(text: String): List<List<String>> {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (inQuotes) {
            if (c == '"') {
                if (i + 1 < text.length && text[i + 1] == '"') {
                    sb.append('"')
                    i++
                } else {
                    inQuotes = false
                }
            } else {
                sb.append(c)
            }
        } else {
            when (c) {
                '"' -> inQuotes = true
                ',' -> {
                    row.add(sb.toString())
                    sb.setLength(0)
                }
                '\n' -> {
                    row.add(sb.toString())
                    sb.setLength(0)
                    rows.add(row)
                    row = ArrayList()
                }
                '\r' -> {}
                else -> sb.append(c)
            }
        }
        i++
    }
    if (sb.isNotEmpty() || row.isNotEmpty()) {
        row.add(sb.toString())
        rows.add(row)
    }
    return rows
}

/** Letterboxd export (Date, Name, Year, Letterboxd URI) or any CSV with Name/Title and Year columns. */
fun parseWatchlistCsv(text: String): List<FilmQuery> {
    val rows = parseCsv(text.removePrefix("﻿"))
    if (rows.isEmpty()) return emptyList()
    val header = rows[0].map { it.trim().lowercase(Locale.ROOT) }
    var iName = header.indexOf("name")
    if (iName < 0) iName = header.indexOf("title")
    val iYear = header.indexOf("year")
    val iUri = header.indexOf("letterboxd uri")
    if (iName < 0) return emptyList()
    val out = ArrayList<FilmQuery>()
    for (r in rows.drop(1)) {
        val name = r.getOrNull(iName)?.trim().orEmpty()
        if (name.isEmpty()) continue
        val y = if (iYear >= 0) r.getOrNull(iYear)?.trim().orEmpty() else ""
        val uri = if (iUri >= 0) r.getOrNull(iUri)?.trim().orEmpty() else ""
        out.add(FilmQuery(name, y.toIntOrNull(), uri))
    }
    return out
}

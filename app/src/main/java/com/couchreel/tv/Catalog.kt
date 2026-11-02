package com.couchreel.tv

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Random

// Kotlin port of api.py (Library) and the channel/guide parts of tgbridge.py.
// Pure Kotlin + java.time, so it can be unit-tested without Android.

private const val WATCHED_FRACTION = 0.90
private const val WATCHED_TAIL_MS = 120_000L
private const val DEFAULT_MOVIE_MIN = 105.0
private const val DEFAULT_EPISODE_MIN = 45.0

fun workId(item: CatalogItem): String {
    val p = item.parsed
    val n = normalize(p.title)
    if (n.isEmpty()) return "k:" + item.key
    return if (p.kind == "episode") "e:$n:${p.season ?: 0}:${p.episode ?: 0}" else "m:$n:${p.year ?: ""}"
}

fun seriesId(item: CatalogItem): String = normalize(item.parsed.title)

/** [mode]: "shuffle", "ordered", "cyclic" or "blocks" (see [CustomChannel]). */
class ChannelSpec(val items: List<CatalogItem>, val mode: String)
class ChannelEditorInfo(val config: ChannelConfig, val builtIn: List<String>, val decades: List<Int>, val sources: List<String>)
class Slot(val start: ZonedDateTime, val end: ZonedDateTime, val item: CatalogItem)

class Catalog(val items: List<CatalogItem>, private val state: StateStore) {
    private val works = LinkedHashMap<String, MutableList<CatalogItem>>()
    private val workOf = HashMap<String, String>()
    private val byKey = HashMap<String, CatalogItem>()
    private val series = HashMap<String, MutableList<String>>() // sid -> work ids in episode order

    private class Snap(val watched: Map<String, Long>, val progress: Map<String, Prog>)
    private class Status(val watched: Boolean, val pos: Long, val dur: Long, val upd: Long, val watchedAt: Long)

    init {
        for (item in items) {
            byKey[item.key] = item
            works.getOrPut(workId(item)) { mutableListOf() }.add(item)
        }
        for ((wid, lst) in works) {
            lst.sortByDescending { it.size } // stable: biggest file first
            for (i in lst) workOf[i.key] = wid
        }
        for ((wid, lst) in works) {
            val rep = lst[0]
            if (rep.parsed.kind == "episode" && rep.parsed.title != null) {
                series.getOrPut(seriesId(rep)) { mutableListOf() }.add(wid)
            }
        }
        for (wids in series.values) {
            wids.sortWith(compareBy<String>({ epOrder(it).first }, { epOrder(it).second }))
        }
    }

    // ------------------------------------------------------------ helpers

    private fun rep(wid: String): CatalogItem = works.getValue(wid)[0]
    private fun epOrder(wid: String): Pair<Int, Int> {
        val p = rep(wid).parsed
        return Pair(p.season ?: 0, p.episode ?: 0)
    }

    private fun snap() = Snap(state.watchedMap(), state.progressMap())

    private fun status(wid: String, s: Snap): Status {
        val keys = works.getValue(wid).map { it.key }
        val watched = keys.any { it in s.watched }
        var pos = 0L
        var dur = 0L
        var upd = 0L
        for (k in keys) {
            val p = s.progress[k]
            if (p != null && p.updated > upd) {
                pos = p.positionMs
                dur = p.durationMs
                upd = p.updated
            }
        }
        val at = keys.mapNotNull { s.watched[it] }.maxOrNull() ?: 0L
        return Status(watched, pos, dur, upd, at)
    }

    private fun card(item: CatalogItem, s: Snap, versions: Int? = null, wid: String? = null): Item {
        val w = wid ?: workOf.getValue(item.key)
        val st = status(w, s)
        val p = item.parsed
        val isEp = p.kind == "episode" && p.title != null
        return Item(
            id = item.key,
            title = p.title ?: item.fileName.ifEmpty { item.key },
            year = p.year,
            kind = if (p.title != null) p.kind else "unknown",
            season = p.season,
            episode = p.episode,
            sid = if (isEp) seriesId(item) else null,
            sizeGb = Math.round(item.size / 1073741824.0 * 100.0) / 100.0,
            versions = versions ?: works.getValue(w).size,
            watched = st.watched,
            positionMs = if (st.watched) 0L else st.pos,
            durationMs = st.dur,
            channel = item.chatTitle,
            fileName = item.fileName,
        )
    }

    private fun cards(wids: List<String>, s: Snap): List<Item> = wids.map { card(rep(it), s, wid = it) }

    private val addedOrder: Comparator<CatalogItem> =
        compareByDescending<CatalogItem> { it.dateSec }.thenByDescending { it.msgId }

    // ------------------------------------------------------------ home

    fun home(channels: List<Channel>, watchlistAvailable: Int): HomeData {
        val s = snap()
        val sts = works.keys.associateWith { status(it, s) }
        val cont = works.keys.filter { val st = sts.getValue(it); !st.watched && st.pos > 0 }
            .sortedByDescending { sts.getValue(it).upd }
        val recent = works.keys.filter { !sts.getValue(it).watched && rep(it).parsed.title != null }
            .sortedWith { a, b -> addedOrder.compare(rep(a), rep(b)) }
        return HomeData(
            continueWatching = cards(cont.take(20), s),
            nextUp = nextUp(s, sts),
            recent = cards(recent.take(30), s),
            channels = channels,
            watchlistAvailable = watchlistAvailable,
            searches = state.recentSearches(),
        )
    }

    private fun nextUp(s: Snap, sts: Map<String, Status>, limit: Int = 15): List<Item> {
        val cands = ArrayList<Pair<Long, String>>()
        for (wids in series.values) {
            var last = -1
            for ((idx, w) in wids.withIndex()) if (sts.getValue(w).watched) last = idx
            if (last < 0) continue
            for (w in wids.drop(last + 1)) {
                if (!sts.getValue(w).watched) {
                    cands.add(Pair(wids.maxOf { sts.getValue(it).watchedAt }, w))
                    break
                }
            }
        }
        cands.sortByDescending { it.first }
        return cards(cands.take(limit).map { it.second }, s)
    }

    // ------------------------------------------------------------ library

    fun library(kind: String, q: String, showAll: Boolean, sort: String, offset: Int, limit: Int): Page<Item> {
        val s = snap()
        val qn = normalize(q)
        val entries = ArrayList<Pair<String, CatalogItem>>()
        for ((wid, lst) in works) {
            val rep = lst[0]
            val p = rep.parsed
            if (kind == "movie" && !(p.kind == "movie" && p.title != null)) continue
            if (kind == "other" && p.title != null) continue
            if (qn.isNotEmpty() && !normalize(p.title ?: rep.fileName).contains(qn)) continue
            for (f in if (showAll) lst else listOf(rep)) entries.add(Pair(wid, f))
        }
        val sorted = if (sort == "title") {
            entries.sortedBy { normalize(it.second.parsed.title ?: it.second.fileName) }
        } else {
            entries.sortedWith { a, b -> addedOrder.compare(a.second, b.second) }
        }
        val page = sorted.drop(offset).take(limit)
        return Page(page.map { card(it.second, s, versions = if (showAll) 1 else null, wid = it.first) }, sorted.size)
    }

    fun seriesList(q: String, offset: Int, limit: Int): Page<SeriesCard> {
        val s = snap()
        val qn = normalize(q)
        class Row(val card: SeriesCard, val latest: CatalogItem)
        val rows = ArrayList<Row>()
        for ((sid, wids) in series) {
            if (qn.isNotEmpty() && !sid.contains(qn)) continue
            val title = rep(wids[0]).parsed.title ?: sid
            val watched = wids.count { status(it, s).watched }
            val latest = wids.map { rep(it) }.minWithOrNull(addedOrder) ?: rep(wids[0])
            rows.add(Row(SeriesCard(sid, title, wids.size, watched), latest))
        }
        rows.sortWith { a, b -> addedOrder.compare(a.latest, b.latest) }
        return Page(rows.drop(offset).take(limit).map { it.card }, rows.size)
    }

    fun seriesDetail(sid: String): SeriesDetail? {
        val wids = series[sid] ?: return null
        val s = snap()
        val sts = wids.associateWith { status(it, s) }
        val bySeason = LinkedHashMap<Int, MutableList<String>>()
        for (w in wids) bySeason.getOrPut(epOrder(w).first) { mutableListOf() }.add(w)
        val last = wids.indices.lastOrNull { sts.getValue(wids[it]).watched } ?: -1
        val nextWid = wids.drop(last + 1).firstOrNull { !sts.getValue(it).watched }
        return SeriesDetail(
            sid = sid,
            title = rep(wids[0]).parsed.title ?: sid,
            episodes = wids.size,
            watched = sts.values.count { it.watched },
            nextUp = nextWid?.let { card(rep(it), s, wid = it) },
            seasons = bySeason.toSortedMap().map { (season, ws) -> SeasonBlock(season, cards(ws, s)) },
        )
    }

    fun nextOf(key: String): Item? {
        val wid = workOf[key] ?: return null
        val r = rep(wid)
        if (r.parsed.kind != "episode" || r.parsed.title == null) return null
        val wids = series[seriesId(r)] ?: return null
        val i = wids.indexOf(wid)
        if (i < 0 || i + 1 >= wids.size) return null
        return card(rep(wids[i + 1]), snap(), wid = wids[i + 1])
    }

    fun search(q: String, save: Boolean): SearchResult {
        val query = q.trim()
        if (save && query.length >= 2) state.addSearch(query)
        return SearchResult(
            movies = if (query.isNotEmpty()) library("movie", query, false, "added", 0, 40).items else emptyList(),
            series = if (query.isNotEmpty()) seriesList(query, 0, 40).items else emptyList(),
            searches = state.recentSearches(),
        )
    }

    fun sources(): List<Source> =
        items.groupBy { it.chatId }.map { (id, lst) -> Source(id, lst[0].chatTitle, lst.size) }
            .sortedByDescending { it.videos }

    // ------------------------------------------------------------ mutations

    /** returns whether the title is now counted as watched */
    fun progress(key: String, positionMs: Long, durationMs: Long): Boolean? {
        val wid = workOf[key] ?: return null
        val done = durationMs > 0 &&
            (positionMs.toDouble() / durationMs >= WATCHED_FRACTION || durationMs - positionMs <= WATCHED_TAIL_MS)
        if (done) state.setWatched(works.getValue(wid).map { it.key }, true) else state.setProgress(key, positionMs, durationMs)
        return done
    }

    fun setWatched(key: String, flag: Boolean): Boolean {
        val wid = workOf[key] ?: return false
        state.setWatched(works.getValue(wid).map { it.key }, flag)
        return true
    }

    fun setSeriesWatched(sid: String, flag: Boolean): Boolean {
        val wids = series[sid] ?: return false
        state.setWatched(wids.flatMap { w -> works.getValue(w).map { it.key } }, flag)
        return true
    }

    // ------------------------------------------------------------ watchlist

    fun importWatchlist(csv: String): Pair<Int, Int> {
        val films = parseWatchlistCsv(csv)
        val rows = matchFilms(films, items).map { m ->
            WatchRow(m.film.name, m.film.year, m.film.uri, m.status, m.hits.map { it.key })
        }
        state.setWatchlist(rows)
        return Pair(rows.size, rows.count { it.keys.isNotEmpty() })
    }

    fun watchlistAvailable(): Int = state.watchlist().count { r -> r.keys.any { it in byKey } }

    fun watchlist(): Watchlist {
        val s = snap()
        val films = state.watchlist().map { r ->
            val k = r.keys.firstOrNull { it in byKey }
            WatchFilm(r.name, r.year, r.status, k?.let { card(byKey.getValue(it), s) })
        }.sortedWith(compareBy<WatchFilm>({ it.item == null }, { it.name.lowercase() }))
        return Watchlist(films, films.count { it.item != null }, films.size)
    }

    // ------------------------------------------------------------ cable-style channels

    private class Built(val specs: Map<String, ChannelSpec>, val ids: Map<String, String>, val pad: Int)

    @Volatile private var built: Built? = null

    private fun channelSet(): Built {
        built?.let { return it }
        val cfg = state.channelConfig()
        val all = LinkedHashMap<String, ChannelSpec>()
        val used = HashSet<String>()
        for (c in cfg.custom) {
            val items = customItems(c)
            if (items.isEmpty()) continue
            var name = c.name.trim().ifEmpty { "My channel" }
            var n = 2
            while (!used.add(name)) name = "${c.name.trim()} ${n++}"
            all[name] = ChannelSpec(items, c.mode)
        }
        for ((name, spec) in buildChannels()) {
            if (name in cfg.hidden) continue
            var shown = name
            var n = 2
            while (!used.add(shown)) shown = "$name ${n++}"
            all[shown] = spec
        }
        val ids = LinkedHashMap<String, String>()
        all.keys.sorted().forEachIndexed { i, name -> ids["ch${i + 1}"] = name }
        return Built(all, ids, cfg.padMinutes).also { built = it }
    }

    private fun reps(): List<CatalogItem> = works.values.map { it[0] }

    private fun customItems(c: CustomChannel): List<CatalogItem> {
        val reps = reps().filter { it.parsed.title != null }
        return when (c.kind) {
            "title" -> {
                val terms = c.value.split(',').map { normalize(it) }.filter { it.isNotEmpty() }
                if (terms.isEmpty()) emptyList()
                else reps.filter { r -> val t = normalize(r.parsed.title); terms.any { it in t } }
            }
            "decade" -> {
                val d = c.value.trim().toIntOrNull() ?: return emptyList()
                reps.filter { it.parsed.kind == "movie" && (it.parsed.year ?: -1) in d..(d + 9) }
            }
            "source" -> reps.filter { it.chatTitle == c.value }
            "movies" -> reps.filter { it.parsed.kind == "movie" }
            "episodes" -> reps.filter { it.parsed.kind == "episode" }
            else -> emptyList()
        }
    }

    /** how many titles a channel definition would contain (for the editor) */
    fun previewCount(c: CustomChannel): Int = customItems(c).size

    fun channelEditorInfo(): ChannelEditorInfo {
        val reps = reps()
        val decades = reps.filter { it.parsed.kind == "movie" }.mapNotNull { it.parsed.year }.map { it / 10 * 10 }.toSortedSet().toList()
        val sources = reps.map { it.chatTitle }.filter { it.isNotBlank() }.toSortedSet().toList()
        return ChannelEditorInfo(state.channelConfig(), buildChannels().keys.toList(), decades, sources)
    }

    fun setChannelConfig(cfg: ChannelConfig) {
        state.setChannelConfig(cfg.copy(padMinutes = cfg.padMinutes.coerceIn(0, 120)))
        built = null
    }

    private fun buildChannels(minMovies: Int = 3, minEpisodes: Int = 2, minSource: Int = 3): Map<String, ChannelSpec> {
        val ch = LinkedHashMap<String, ChannelSpec>()
        val reps = reps()
        val movies = reps.filter { it.parsed.kind == "movie" && it.parsed.title != null }
        if (movies.size >= minMovies) ch["Movies: Mixed"] = ChannelSpec(movies, "shuffle")
        val decades = HashMap<Int, MutableList<CatalogItem>>()
        for (m in movies) {
            val y = m.parsed.year ?: continue
            decades.getOrPut(y / 10 * 10) { mutableListOf() }.add(m)
        }
        for ((d, lst) in decades.toSortedMap()) if (lst.size >= minMovies) ch["Movies: ${d}s"] = ChannelSpec(lst, "shuffle")
        val shows = HashMap<String, MutableList<CatalogItem>>()
        for (e in reps.filter { it.parsed.kind == "episode" && it.parsed.title != null }) {
            shows.getOrPut(seriesId(e)) { mutableListOf() }.add(e)
        }
        for (lst in shows.values) {
            if (lst.size >= minEpisodes) {
                val sorted = lst.sortedWith(compareBy<CatalogItem>({ it.parsed.season ?: 0 }, { it.parsed.episode ?: 0 }))
                ch["Series: ${sorted[0].parsed.title}"] = ChannelSpec(sorted, "ordered")
            }
        }
        for ((name, lst) in reps.groupBy { it.chatTitle }.toSortedMap()) {
            if (lst.size >= minSource) ch["Channel: $name"] = ChannelSpec(lst, "shuffle")
        }
        val titled = reps.filter { it.parsed.title != null }
        if (titled.size >= minMovies) ch["Mixed Bag"] = ChannelSpec(titled, "shuffle")
        return ch
    }

    fun channelList(): List<Channel> {
        val b = channelSet()
        return b.ids.map { (id, name) -> Channel(id, name, b.specs.getValue(name).items.size) }
    }

    private fun minutes(item: CatalogItem): Double =
        if (item.durationSec > 0) item.durationSec / 60.0
        else if (item.parsed.kind == "episode") DEFAULT_EPISODE_MIN else DEFAULT_MOVIE_MIN

    private fun dayStart(now: ZonedDateTime): ZonedDateTime = now.toLocalDate().atStartOfDay(now.zone)

    private fun epKey(i: CatalogItem) = Triple(i.parsed.season ?: 0, i.parsed.episode ?: 0, i.parsed.year ?: 0)

    private fun inOrder(items: List<CatalogItem>): List<CatalogItem> =
        items.sortedWith(
            compareBy<CatalogItem>({ normalize(it.parsed.title) }, { epKey(it).first }, { epKey(it).second }, { epKey(it).third }),
        )

    /** the order a channel plays in; same input and same day always give the same result */
    private fun arrange(spec: ChannelSpec, name: String, day: java.time.LocalDate): List<CatalogItem> {
        val rnd = Random("$name-$day".hashCode().toLong())
        return when (spec.mode) {
            "ordered" -> inOrder(spec.items)
            "cyclic", "blocks" -> {
                val groups = LinkedHashMap<String, MutableList<CatalogItem>>()
                for (i in inOrder(spec.items)) {
                    val g = if (i.parsed.kind == "episode") "s:" + seriesId(i) else "movies"
                    groups.getOrPut(g) { mutableListOf() }.add(i)
                }
                groups["movies"]?.shuffle(rnd)
                val lists = groups.values.toMutableList()
                if (spec.mode == "blocks") lists.shuffle(rnd) else if (lists.isNotEmpty()) java.util.Collections.rotate(lists, rnd.nextInt(lists.size))
                val take = if (spec.mode == "blocks") 3 else 1
                val pos = IntArray(lists.size)
                val out = ArrayList<CatalogItem>()
                while (out.size < spec.items.size) {
                    for ((gi, lst) in lists.withIndex()) {
                        var n = 0
                        while (n < take && pos[gi] < lst.size) { out.add(lst[pos[gi]++]); n++ }
                    }
                }
                out
            }
            else -> spec.items.shuffled(rnd)
        }
    }

    private fun schedule(spec: ChannelSpec, name: String, now: ZonedDateTime, pad: Int): List<Slot> {
        if (spec.items.isEmpty()) return emptyList()
        val start = dayStart(now)
        val pool = arrange(spec, name, start.toLocalDate())
        val end = start.plusHours(48)
        val out = ArrayList<Slot>()
        var t = start
        var i = 0
        while (t.isBefore(end)) {
            val it = pool[i % pool.size]
            var secs = maxOf(60L, (minutes(it) * 60).toLong())
            if (pad > 0) { val p = pad * 60L; secs = (secs + p - 1) / p * p } // pad to the next quarter hour, like TV
            val len = Duration.ofSeconds(secs)
            out.add(Slot(t, t.plus(len), it))
            t = t.plus(len)
            i++
        }
        return out
    }

    private fun programmeTitle(item: CatalogItem): String {
        val p = item.parsed
        val t = p.title ?: item.fileName
        return when {
            p.kind == "movie" && p.year != null -> "$t (${p.year})"
            p.kind == "episode" -> String.format(java.util.Locale.ROOT, "%s  S%02dE%02d", t, p.season ?: 0, p.episode ?: 0)
            else -> t
        }
    }

    fun guide(hours: Int, now: ZonedDateTime = ZonedDateTime.now()): List<GuideChannel> {
        val end = now.plusHours(hours.toLong())
        val b = channelSet()
        return b.ids.map { (id, name) ->
            val progs = schedule(b.specs.getValue(name), name, now, b.pad)
                .filter { it.end.isAfter(now) && it.start.isBefore(end) }
                .map {
                    Programme(
                        it.start.toString(), it.end.toString(), programmeTitle(it.item), it.item.key,
                        !it.start.isAfter(now) && it.end.isAfter(now),
                    )
                }
            GuideChannel(id, name, progs)
        }
    }

    fun liveKeyNow(channelId: String, now: ZonedDateTime = ZonedDateTime.now()): String? {
        val b = channelSet()
        val name = b.ids[channelId] ?: return null
        return schedule(b.specs.getValue(name), name, now, b.pad)
            .firstOrNull { !it.start.isAfter(now) && it.end.isAfter(now) }?.item?.key
    }

    fun item(key: String): CatalogItem? = byKey[key]

    companion object {
        fun zone(): ZoneId = ZoneId.systemDefault()
        fun at(epochMs: Long): ZonedDateTime = Instant.ofEpochMilli(epochMs).atZone(zone())
    }
}

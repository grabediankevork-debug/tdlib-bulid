package com.couchreel.tv

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

typealias Play = (Item) -> Unit

private fun Item.safeToggle() = !watched

// ================================================================== HOME

@Composable
fun HomeScreen(api: Backend, tick: Int, play: Play, go: (Route) -> Unit) {
    val scope = rememberCoroutineScope()
    var bump by remember { mutableIntStateOf(0) }
    val ui by loadState(tick, bump) { api.home() }
    val scanStatus by api.status.collectAsState()

    Gate(ui) { h ->
        val toggle: (Item) -> Unit = { item ->
            scope.launch {
                try {
                    api.setWatched(item.id, item.safeToggle())
                } catch (e: Exception) {
                    // ignore; the row simply doesn't change
                }
                bump++
            }
        }

        // The same title can be in several rows; show it once, in the first row that has it.
        val seen = HashSet<String>()
        val sections = listOf(
            Triple("Continue watching", "cont", h.continueWatching),
            Triple("Next up", "next", h.nextUp),
            Triple("Recently added", "recent", h.recent),
        ).map { (title, key, items) -> Triple(title, key, items.filter { seen.add(it.id) }) }
            .filter { it.third.isNotEmpty() }

        if (sections.isEmpty() && h.channels.isEmpty()) {
            Centered(
                if (api.isLocal) {
                    "Your library is empty.\n" + scanStatus.ifBlank { "Join some channels with videos, then press Rescan in Settings." }
                } else {
                    "Your library is empty.\nOn the server run:  python tgbridge.py index --channels all\nthen restart the server."
                },
            )
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                StatusLine(scanStatus)
                sections.forEachIndexed { idx, (title, key, items) ->
                    CardRow(title, "home", key, items, { it.id }, firstRow = idx == 0) { item, mod ->
                        ItemCard(item, mod, onLong = { toggle(item) }) { play(item) }
                    }
                }
                if (h.channels.isNotEmpty()) {
                    CardRow("Channels", "home", "channels", h.channels, { "ch:" + it.id }, firstRow = sections.isEmpty()) { ch, mod ->
                        MediaCard(ch.name, "${ch.count} videos · live guide", ch.name, mod) { go(Route.Channels) }
                    }
                }
                if (h.watchlistAvailable > 0) {
                    ButtonRow {
                        Chip("Watchlist: ${h.watchlistAvailable} available") { go(Route.Watchlist) }
                    }
                }
                StatusLine("Tip: hold OK on a title to mark it watched / unwatched.")
            }
        }
    }
}

// ================================================================== MOVIES

@Composable
fun MoviesScreen(api: Backend, tick: Int, play: Play) {
    val scope = rememberCoroutineScope()
    var allFiles by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf("added") }
    var bump by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<Item>>(emptyList()) }
    var total by remember { mutableIntStateOf(-1) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(allFiles, sort, tick, bump) {
        try {
            val p = api.library("movie", allFiles, sort, 0, 200)
            items = p.items
            total = p.total
            error = ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "error"
        }
    }

    Column(Modifier.fillMaxSize()) {
        ButtonRow {
            Chip(if (allFiles) "All versions shown" else "Duplicates hidden", selected = allFiles) { allFiles = !allFiles }
            Chip(if (sort == "added") "Sort: newest" else "Sort: A–Z") { sort = if (sort == "added") "title" else "added" }
            if (total >= 0) Text("$total titles", color = TextDim, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp))
        }
        when {
            error.isNotEmpty() && items.isEmpty() -> Centered("Couldn't load movies\n$error")
            total < 0 -> Centered("Loading…")
            items.isEmpty() -> Centered("No movies found")
            else -> LazyVerticalGrid(
                state = FocusMemory.grid("movies"),
                columns = GridCells.Adaptive(236.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(8.dp),
            ) {
                itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
                    ItemCard(
                        item,
                        Modifier.focusMemory("movies", item.id, defaultFirst = i == 0),
                        onLong = {
                            scope.launch {
                                try {
                                    api.setWatched(item.id, item.safeToggle())
                                } catch (e: Exception) {
                                    // ignore
                                }
                                bump++
                            }
                        },
                    ) { play(item) }
                }
                if (items.size < total) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Chip("Load more (${items.size} of $total)") {
                            scope.launch {
                                try {
                                    val p = api.library("movie", allFiles, sort, items.size, 200)
                                    val have = items.map { it.id }.toHashSet()
                                    items = items + p.items.filter { it.id !in have }
                                    total = p.total
                                } catch (e: Exception) {
                                    error = e.message ?: "error"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ================================================================== SERIES

@Composable
fun SeriesScreen(api: Backend, tick: Int, go: (Route) -> Unit) {
    val ui by loadState(tick) { api.seriesList(0, 200) }
    Gate(ui) { page ->
        if (page.items.isEmpty()) {
            Centered("No series found.\nEpisodes need names like  Show.Name.S01E02.mkv")
        } else {
            Column(Modifier.fillMaxSize()) {
                if (page.total > page.items.size) StatusLine("Showing the ${page.items.size} newest of ${page.total} series. Use Search for the rest.")
                LazyVerticalGrid(
                    state = FocusMemory.grid("series"),
                    columns = GridCells.Adaptive(236.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    itemsIndexed(page.items, key = { _, it -> it.sid }) { i, s ->
                        MediaCard(
                            title = s.title,
                            subtitle = "${s.watched}/${s.episodes} watched",
                            seed = s.title,
                            modifier = Modifier.focusMemory("series", s.sid, defaultFirst = i == 0),
                            watched = s.episodes > 0 && s.watched >= s.episodes,
                            progress = if (s.episodes > 0) s.watched.toFloat() / s.episodes else 0f,
                        ) { go(Route.SeriesDetail(s.sid)) }
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesDetailScreen(api: Backend, sid: String, tick: Int, play: Play) {
    val scope = rememberCoroutineScope()
    var bump by remember { mutableIntStateOf(0) }
    val ui by loadState(sid, tick, bump) { api.seriesDetail(sid) }
    val screen = "series/$sid"

    Gate(ui) { d ->
        val toggle: (Item) -> Unit = { ep ->
            scope.launch {
                try {
                    api.setWatched(ep.id, ep.safeToggle())
                } catch (e: Exception) {
                    // ignore
                }
                bump++
            }
        }
        val allDone = d.episodes > 0 && d.watched >= d.episodes

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(d.title, color = TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            StatusLine("${d.watched} of ${d.episodes} episodes watched")
            ButtonRow {
                val next = d.nextUp
                if (next != null) {
                    Chip(
                        "Play next: ${next.episodeCode}",
                        Modifier.focusMemory(screen, "btn:next", defaultFirst = true),
                    ) { play(next) }
                }
                Chip(
                    if (allDone) "Mark all unwatched" else "Mark all watched",
                    Modifier.focusMemory(screen, "btn:all", defaultFirst = next == null),
                ) {
                    scope.launch {
                        try {
                            api.setSeriesWatched(d.sid, !allDone)
                        } catch (e: Exception) {
                            // ignore
                        }
                        bump++
                    }
                }
            }
            d.seasons.forEach { block ->
                CardRow(
                    title = if (block.season == 0) "Specials / unnumbered" else "Season ${block.season}",
                    screen = screen,
                    rowKey = "s${block.season}",
                    items = block.episodes,
                    id = { it.id },
                    firstRow = false,
                ) { ep, mod ->
                    MediaCard(
                        title = ep.episodeCode.ifEmpty { ep.title },
                        subtitle = String.format(Locale.ROOT, "%.1f GB", ep.sizeGb) + if (ep.versions > 1) " · ${ep.versions} versions" else "",
                        seed = d.title + ep.season,
                        modifier = mod,
                        watched = ep.watched,
                        progress = ep.progress,
                        onLong = { toggle(ep) },
                    ) { play(ep) }
                }
            }
            StatusLine("Hold OK on an episode to mark it watched / unwatched.")
        }
    }
}

// ================================================================== SEARCH

@Composable
fun SearchScreen(api: Backend, tick: Int, play: Play, go: (Route) -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<SearchResult?>(null) }
    var recents by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(tick) {
        try {
            recents = api.home().searches
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "error"
        }
    }
    // live preview while typing; not saved to recent searches
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            result = null
            return@LaunchedEffect
        }
        delay(450)
        try {
            result = api.search(query.trim(), false)
            error = ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "error"
        }
    }

    fun commit(q: String) {
        scope.launch {
            try {
                val r = api.search(q.trim(), true)
                result = r
                recents = r.searches
            } catch (e: Exception) {
                error = e.message ?: "error"
            }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search movies and series") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (query.trim().length >= 2) commit(query)
                focus.moveFocus(FocusDirection.Down)
            }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (recents.isNotEmpty()) {
            Text("Recent searches (OK to reuse, no typing needed)", color = TextDim, fontSize = 14.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recents, key = { it }) { r ->
                    Chip(r) {
                        query = r
                        commit(r)
                    }
                }
            }
        }
        if (error.isNotEmpty()) StatusLine("Problem: $error")
        val res = result
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (res != null && res.movies.isEmpty() && res.series.isEmpty()) StatusLine("No matches for \"${query.trim()}\"")
            if (res != null && res.movies.isNotEmpty()) {
                CardRow("Movies", "search", "movies", res.movies, { it.id }, firstRow = false) { item, mod ->
                    ItemCard(item, mod) { play(item) }
                }
            }
            if (res != null && res.series.isNotEmpty()) {
                CardRow("Series", "search", "series", res.series, { "s:" + it.sid }, firstRow = false) { s, mod ->
                    MediaCard(s.title, "${s.watched}/${s.episodes} watched", s.title, mod) { go(Route.SeriesDetail(s.sid)) }
                }
            }
        }
    }
}

// ================================================================== CHANNELS (cable-style guide)

@Composable
fun ChannelsScreen(api: Backend, tick: Int, playLive: (String, String) -> Unit, onEdit: () -> Unit) {
    val ui by loadState(tick) { api.guide(6) }
    Gate(ui) { channels ->
        if (channels.isEmpty() && !api.isLocal) {
            Centered("No channels yet.\nThey are built from your library (needs a few movies or a series).")
        } else {
            LazyColumn(
                state = FocusMemory.list("channels"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                if (api.isLocal) {
                    item(key = "edit") {
                        ButtonRow { Chip("Edit channels", Modifier.focusMemory("channels", "edit", true)) { onEdit() } }
                    }
                    if (channels.isEmpty()) {
                        item(key = "none") {
                            Text(
                                "No channels yet. They are built from your library, or press “Edit channels” to make your own.",
                                color = TextDim, fontSize = 16.sp,
                            )
                        }
                    }
                }
                lazyItemsIndexed(channels, key = { _, it -> it.id }) { i, ch ->
                    val now = ch.programmes.firstOrNull { it.onNow }
                    val upcoming = ch.programmes.filter { !it.onNow }.take(2)
                    PanelRow(
                        modifier = Modifier.focusMemory("channels", ch.id, defaultFirst = i == 0 && !api.isLocal),
                        onClick = { playLive(ch.id, ch.name) },
                    ) {
                        Text(ch.name, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Now: ${now?.title ?: "—"}", color = Accent, fontSize = 16.sp)
                        if (upcoming.isNotEmpty()) {
                            Text("Next: " + upcoming.joinToString("  ·  ") { it.title }, color = TextDim, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

// ================================================================== WATCHLIST

@Composable
fun WatchlistScreen(api: Backend, tick: Int, play: Play) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bump by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf("") }
    val ui by loadState(tick, bump) { api.watchlist() }

    fun importText(text: String) {
        scope.launch {
            try {
                api.importWatchlist(text)
                bump++
                msg = "Imported."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                msg = "Import failed: ${e.message}"
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    }
                    api.importWatchlist(text)
                    bump++
                    msg = "Imported."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    msg = "Import failed: ${e.message}"
                }
            }
        }
    }

    Gate(ui) { wl ->
        Column(Modifier.fillMaxSize()) {
            if (api.isLocal) {
                ButtonRow {
                    Chip("Choose watchlist.csv", Modifier.focusMemory("watchlist", "btn:pick", defaultFirst = wl.total == 0)) {
                        try {
                            picker.launch(arrayOf("*/*"))
                        } catch (e: Exception) {
                            msg = "This TV has no file picker. Use 'Read from app folder' instead."
                        }
                    }
                    Chip("Read from app folder") {
                        val f = File(ctx.getExternalFilesDir(null), "watchlist.csv")
                        if (f.exists()) {
                            importText(f.readText())
                        } else {
                            msg = "Copy watchlist.csv to ${f.parent} first (for example: adb push watchlist.csv ${f.parent}/)"
                        }
                    }
                }
            }
            StatusLine(msg)
            if (wl.total == 0) {
                Centered(
                    if (api.isLocal) {
                        "No watchlist imported yet.\nExport it on Letterboxd (Settings → Data → Export),\nthen choose watchlist.csv above."
                    } else {
                        "No watchlist imported yet.\n\nOn your phone or PC open\n${api.watchlistUploadUrl()}\n" +
                            "and choose watchlist.csv from your Letterboxd export\n(Letterboxd → Settings → Data → Export)."
                    },
                )
            } else {
                StatusLine("${wl.available} of ${wl.total} films are in your channels")
                LazyVerticalGrid(
                    state = FocusMemory.grid("watchlist"),
                    columns = GridCells.Adaptive(236.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    itemsIndexed(wl.films, key = { i, f -> "$i:${f.name}" }) { i, f ->
                        val item = f.item
                        MediaCard(
                            title = f.name,
                            subtitle = if (item != null) {
                                (f.year?.toString() ?: "") + " · " + String.format(Locale.ROOT, "%.1f GB", item.sizeGb)
                            } else {
                                (f.year?.toString() ?: "") + " · not in your channels"
                            },
                            seed = f.name,
                            modifier = Modifier.focusMemory("watchlist", "$i:${f.name}", defaultFirst = i == 0),
                            watched = item?.watched == true,
                            progress = item?.progress ?: 0f,
                        ) { if (item != null) play(item) }
                    }
                }
            }
        }
    }
}

// ================================================================== HUBS (auto-join)

private suspend fun waitJob(api: Backend, id: String, onProgress: (JobInfo) -> Unit): JobInfo {
    while (true) {
        val j = api.job(id)
        onProgress(j)
        if (j.state != "running") return j
        delay(1000)
    }
}

@Composable
fun HubsScreen(api: Backend) {
    val scope = rememberCoroutineScope()
    var channel by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<HubEntry>>(emptyList()) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }

    fun selectable(e: HubEntry) = e.status == "video" || e.status == "private"

    fun scan() {
        if (busy || channel.isBlank()) return
        busy = true
        status = "Scanning…"
        entries = emptyList()
        selected.clear()
        scope.launch {
            try {
                val id = api.hubScan(channel.trim())
                val job = waitJob(api, id) { j -> status = "Checking linked channels… ${j.done}/${j.total}" }
                if (job.state == "error") {
                    status = "Scan failed: ${job.error}"
                } else {
                    val list = (job.result?.objects() ?: emptyList()).map {
                        HubEntry(
                            kind = it.optString("kind"),
                            key = it.optString("key"),
                            label = it.optString("label"),
                            title = it.optString("title"),
                            videos = it.optInt("videos"),
                            status = it.optString("status"),
                        )
                    }
                    entries = list
                    list.filter { it.status == "video" }.forEach { selected[it.label] = true }
                    status = "Found ${list.size} linked channels, ${list.count { it.status == "video" }} with video."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Error: ${e.message}"
            }
            busy = false
        }
    }

    fun join() {
        val chosen = entries.filter { selected[it.label] == true }
        if (busy || chosen.isEmpty()) return
        busy = true
        status = "Joining ${minOf(chosen.size, 20)} channels (paced to stay within Telegram limits)…"
        scope.launch {
            try {
                val id = api.hubJoin(chosen)
                val job = waitJob(api, id) { j -> status = "Joining… ${j.done}/${j.total}" }
                if (job.state == "error") {
                    status = "Join failed: ${job.error}"
                } else {
                    val res = job.result?.objects() ?: emptyList()
                    val ok = res.count { it.optBoolean("ok") }
                    val firstError = res.firstOrNull { !it.optBoolean("ok") }?.optString("error").orEmpty()
                    status = "Joined $ok of ${res.size}." + if (firstError.isNotEmpty()) " Stopped: $firstError" else ""
                    status += if (api.isLocal) "  Press Rescan in Settings to add their videos." else "  Re-run 'tgbridge.py index' on the server to add their videos."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Error: ${e.message}"
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Join the channels linked from a hub channel (only ones that contain video)", color = TextDim, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = channel,
                onValueChange = { channel = it },
                singleLine = true,
                label = { Text("Hub channel (@name or t.me link)") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { scan() }),
                modifier = Modifier.weight(1f),
            )
            Chip(if (busy) "Working…" else "Scan") { scan() }
        }
        StatusLine(status)
        val count = entries.count { selected[it.label] == true }
        if (entries.isNotEmpty()) {
            ButtonRow {
                Chip("Join selected ($count)") { join() }
                Chip("Select all with video") {
                    entries.filter { it.status == "video" }.forEach { selected[it.label] = true }
                }
                Chip("Clear") { selected.clear() }
            }
        }
        LazyColumn(
            state = FocusMemory.list("hubs"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(entries, key = { it.label }) { e ->
                val on = selected[e.label] == true
                val label = when (e.status) {
                    "video" -> "${e.videos}+ videos"
                    "private" -> "private invite (can't be checked before joining)"
                    "member" -> "already joined"
                    "no_video" -> "no video found"
                    else -> "couldn't check"
                }
                PanelRow(
                    modifier = Modifier.focusMemory("hubs", e.label),
                    onClick = { if (selectable(e)) selected[e.label] = !on },
                ) {
                    Text(
                        (if (on) "☑  " else if (selectable(e)) "☐  " else "     ") + e.title.ifEmpty { e.label },
                        color = if (selectable(e)) TextPrimary else TextDim,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("${e.label}  ·  $label", color = TextDim, fontSize = 14.sp)
                }
            }
        }
    }
}

// ================================================================== SETTINGS / FIRST RUN

@Composable
fun SettingsScreen(initial: ServerConfig?, first: Boolean, onSaved: (ServerConfig) -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(initial?.base ?: "") }
    var token by remember { mutableStateOf(initial?.token ?: "") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun test() {
        if (busy) return
        val base = normalizeBase(address)
        if (base.isEmpty()) {
            status = "Enter your server address, e.g. 192.168.1.50:8765"
            return
        }
        busy = true
        status = "Connecting…"
        scope.launch {
            try {
                val sources = Api(base, token.trim()).ping()
                status = "Connected. $sources source channels."
                onSaved(ServerConfig(base, token.trim()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Couldn't connect: ${e.message}"
            }
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (first) {
            Text("Welcome to Couchreel", color = TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(
                "Couchreel plays the Telegram videos your own tgbridge server streams.\nEnter the server address and the token you started it with.",
                color = TextDim,
                fontSize = 18.sp,
            )
        }
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            singleLine = true,
            label = { Text("Server address (e.g. 192.168.1.50:8765)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            singleLine = true,
            label = { Text("Token (blank if the server has none)") },
            modifier = Modifier.fillMaxWidth(),
        )
        ButtonRow { Chip(if (busy) "Testing…" else "Test and save") { test() } }
        StatusLine(status)
    }
}

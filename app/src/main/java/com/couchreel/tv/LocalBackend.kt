package com.couchreel.tv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Direct mode: no server. The library comes from scanning your Telegram channels through TDLib. */
class LocalBackend(
    private val td: TdClient,
    private val state: StateStore,
    private val catalogStore: CatalogStore,
) : Backend {

    private companion object {
        const val PER_CHAT_LIMIT = 3000          // newest messages read per channel
        const val MIN_SIZE = 20L * 1024 * 1024   // ignore clips under 20 MB
        const val HUB_SCAN_LIMIT = 300
        const val HUB_MIN_VIDEOS = 3
        const val MAX_JOINS = 20
    }

    override val isLocal = true
    private val _version = MutableStateFlow(0)
    private val _status = MutableStateFlow("")
    override val version: StateFlow<Int> = _version
    override val status: StateFlow<String> = _status

    @Volatile private var catalog = Catalog(catalogStore.load(), state)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val scanLock = Mutex()
    private val hub = TdHub(td)

    private class JobState(val id: String) {
        @Volatile var state = "running"
        @Volatile var done = 0
        @Volatile var total = 0
        @Volatile var error = ""
        @Volatile var result: JSONArray? = null
    }

    private val jobs = ConcurrentHashMap<String, JobState>()

    init {
        if (catalog.items.isEmpty()) scope.launch { rescan() }
    }

    private suspend fun <T> bg(block: () -> T): T = withContext(Dispatchers.Default) { block() }

    private fun publish(items: List<CatalogItem>, save: Boolean) {
        catalog = Catalog(items.toList(), state)
        _version.value = _version.value + 1
        if (save) catalogStore.save(items)
    }

    // ------------------------------------------------------------------ scanning

    override suspend fun rescan() {
        if (!scanLock.tryLock()) return
        try {
            _status.value = "Finding your channels…"
            val chats = td.videoChats()
            val all = ArrayList<CatalogItem>()
            for ((i, chat) in chats.withIndex()) {
                _status.value = "Scanning ${i + 1}/${chats.size}: ${chat.title}"
                try {
                    all.addAll(td.scanChat(chat, PER_CHAT_LIMIT, MIN_SIZE))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // one unreadable channel should not stop the scan
                }
                if ((i + 1) % 5 == 0) publish(all, false)
            }
            publish(all, true)
            _status.value = if (all.isEmpty()) "No videos found in your channels. Join some, then rescan in Settings." else ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = "Scan failed: ${e.message}"
        } finally {
            scanLock.unlock()
        }
    }

    /** start a scan that keeps running even if the screen that asked for it is closed */
    fun startRescan() {
        scope.launch { rescan() }
    }

    // ------------------------------------------------------------------ reads

    override suspend fun home(): HomeData = bg { catalog.home(catalog.channelList(), catalog.watchlistAvailable()) }

    override suspend fun library(kind: String, allFiles: Boolean, sort: String, offset: Int, limit: Int): Page<Item> =
        bg { catalog.library(kind, "", allFiles, sort, offset, limit) }

    override suspend fun seriesList(offset: Int, limit: Int): Page<SeriesCard> = bg { catalog.seriesList("", offset, limit) }

    override suspend fun seriesDetail(sid: String): SeriesDetail =
        bg { catalog.seriesDetail(sid) ?: throw IllegalArgumentException("Unknown series") }

    override suspend fun search(q: String, save: Boolean): SearchResult = bg { catalog.search(q, save) }
    override suspend fun next(key: String): Item? = bg { catalog.nextOf(key) }
    override suspend fun sources(): List<Source> = bg { catalog.sources() }
    override suspend fun guide(hours: Int): List<GuideChannel> = bg { catalog.guide(hours) }
    override suspend fun watchlist(): Watchlist = bg { catalog.watchlist() }
    override suspend fun ping(): Int = bg { catalog.sources().size }

    // ------------------------------------------------------------------ writes

    override suspend fun progress(key: String, positionMs: Long, durationMs: Long) {
        bg { catalog.progress(key, positionMs, durationMs) }
    }

    override suspend fun setWatched(key: String, watched: Boolean) {
        bg { catalog.setWatched(key, watched) }
    }

    override suspend fun setSeriesWatched(sid: String, watched: Boolean) {
        bg { catalog.setSeriesWatched(sid, watched) }
    }

    override suspend fun importWatchlist(csv: String) {
        bg { catalog.importWatchlist(csv) }
        _version.value = _version.value + 1
    }

    override fun watchlistUploadUrl(): String = ""
    override fun liveUrl(channelId: String): String = ""
    override suspend fun liveKeyNow(channelId: String): String? = bg { catalog.liveKeyNow(channelId) }

    // ------------------------------------------------------------------ hub auto-join (background jobs)

    private fun startJob(block: suspend (JobState) -> JSONArray): String {
        val job = JobState(UUID.randomUUID().toString().take(8))
        jobs[job.id] = job
        scope.launch {
            try {
                job.result = block(job)
                job.state = "done"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                job.error = e.message ?: e.javaClass.simpleName
                job.state = "error"
            }
        }
        return job.id
    }

    override suspend fun hubScan(channel: String): String = startJob { job ->
        val entries = hub.scan(channel, HUB_SCAN_LIMIT, HUB_MIN_VIDEOS) { done, total ->
            job.done = done
            job.total = total
        }
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().put("kind", e.kind).put("key", e.key).put("label", e.label)
                    .put("title", e.title).put("videos", e.videos).put("status", e.status),
            )
        }
        arr
    }

    override suspend fun hubJoin(entries: List<HubEntry>): String = startJob { job ->
        val results = hub.join(entries, MAX_JOINS) { done, total ->
            job.done = done
            job.total = total
        }
        val arr = JSONArray()
        for (r in results) arr.put(JSONObject().put("label", r.label).put("ok", r.ok).put("error", r.error))
        arr
    }

    override suspend fun job(id: String): JobInfo {
        val j = jobs[id] ?: throw IllegalArgumentException("Unknown job")
        return JobInfo(j.id, j.state, j.done, j.total, j.error, j.result)
    }

    // ------------------------------------------------------------------ playback + maintenance

    /** TDLib's current file id for a library key (ids are looked up fresh because they can change). */
    suspend fun fileIdFor(key: String): Int {
        val item = catalog.item(key) ?: throw IllegalArgumentException("This video is no longer in your library")
        val m = td.getMessage(item.chatId, item.msgId)
        val f = td.fileOf(m) ?: throw IllegalStateException("The message no longer contains a video")
        return f.id
    }

    suspend fun clearCache() {
        td.clearCache()
    }

    suspend fun logOut() {
        catalogStore.clear()
        td.logOut()
    }
}

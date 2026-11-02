package com.couchreel.tv

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// On-device storage: watched marks, resume positions, recent searches, watchlist, and the video index.

data class Prog(val positionMs: Long, val durationMs: Long, val updated: Long)
data class WatchRow(val name: String, val year: Int?, val uri: String, val status: String, val keys: List<String>)

interface StateStore {
    fun watchedMap(): Map<String, Long>
    fun progressMap(): Map<String, Prog>
    fun setProgress(key: String, positionMs: Long, durationMs: Long)
    fun setWatched(keys: Collection<String>, flag: Boolean)
    fun addSearch(q: String)
    fun recentSearches(n: Int = 8): List<String>
    fun setWatchlist(rows: List<WatchRow>)
    fun watchlist(): List<WatchRow>
}

/** In-memory implementation; also used by unit tests. */
open class MemoryState : StateStore {
    private val lock = Any()
    private val watched = HashMap<String, Long>()
    private val progress = HashMap<String, Prog>()
    private val searches = ArrayList<String>() // newest first
    private var rows: List<WatchRow> = emptyList()

    /** called after every mutation; [FileState] persists here */
    protected open fun changed() {}

    /** monotonically increasing clock so quick successive updates keep their order */
    private var lastTick = 0L
    private fun tick(): Long {
        val now = System.currentTimeMillis()
        lastTick = if (now > lastTick) now else lastTick + 1
        return lastTick
    }

    override fun watchedMap(): Map<String, Long> = synchronized(lock) { HashMap(watched) }
    override fun progressMap(): Map<String, Prog> = synchronized(lock) { HashMap(progress) }

    override fun setProgress(key: String, positionMs: Long, durationMs: Long) {
        synchronized(lock) { progress[key] = Prog(positionMs, durationMs, tick()) }
        changed()
    }

    override fun setWatched(keys: Collection<String>, flag: Boolean) {
        synchronized(lock) {
            val t = tick()
            for (k in keys) {
                if (flag) watched[k] = t else watched.remove(k)
                progress.remove(k)
            }
        }
        changed()
    }

    override fun addSearch(q: String) {
        synchronized(lock) {
            searches.remove(q)
            searches.add(0, q)
            while (searches.size > 12) searches.removeAt(searches.size - 1)
        }
        changed()
    }

    override fun recentSearches(n: Int): List<String> = synchronized(lock) { searches.take(n) }

    override fun setWatchlist(rows: List<WatchRow>) {
        synchronized(lock) { this.rows = rows }
        changed()
    }

    override fun watchlist(): List<WatchRow> = synchronized(lock) { rows }

    // used by FileState to load/save
    protected fun dump(): JSONObject = synchronized(lock) {
        val w = JSONObject()
        watched.forEach { (k, v) -> w.put(k, v) }
        val p = JSONObject()
        progress.forEach { (k, v) -> p.put(k, JSONArray().put(v.positionMs).put(v.durationMs).put(v.updated)) }
        val wl = JSONArray()
        for (r in rows) {
            wl.put(
                JSONObject().put("name", r.name).put("year", r.year ?: JSONObject.NULL).put("uri", r.uri)
                    .put("status", r.status).put("keys", JSONArray(r.keys)),
            )
        }
        JSONObject().put("watched", w).put("progress", p).put("searches", JSONArray(searches)).put("watchlist", wl)
    }

    protected fun restore(o: JSONObject) {
        synchronized(lock) {
            watched.clear()
            progress.clear()
            searches.clear()
            val w = o.optJSONObject("watched")
            w?.keys()?.forEach { watched[it] = w.getLong(it) }
            val p = o.optJSONObject("progress")
            p?.keys()?.forEach {
                val a = p.getJSONArray(it)
                progress[it] = Prog(a.getLong(0), a.getLong(1), a.getLong(2))
            }
            val s = o.optJSONArray("searches")
            if (s != null) for (i in 0 until s.length()) searches.add(s.getString(i))
            val wl = o.optJSONArray("watchlist")
            val list = ArrayList<WatchRow>()
            if (wl != null) {
                for (i in 0 until wl.length()) {
                    val r = wl.getJSONObject(i)
                    val ks = r.optJSONArray("keys")
                    list.add(
                        WatchRow(
                            name = r.optString("name"),
                            year = if (r.isNull("year")) null else r.optInt("year"),
                            uri = r.optString("uri"),
                            status = r.optString("status"),
                            keys = if (ks == null) emptyList() else List(ks.length()) { ks.getString(it) },
                        ),
                    )
                }
            }
            rows = list
        }
    }
}

/** JSON file on the device; written atomically after each change. */
class FileState(private val file: File) : MemoryState() {
    init {
        try {
            if (file.exists()) restore(JSONObject(file.readText()))
        } catch (e: Exception) {
            // corrupt file: start clean rather than crash
        }
    }

    override fun changed() {
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(dump().toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            // disk full or similar: the in-memory state still works for this session
        }
    }
}

/** The scanned video index, cached so the app opens instantly. */
class CatalogStore(private val file: File) {
    fun save(items: List<CatalogItem>) {
        val arr = JSONArray()
        for (e in items) {
            arr.put(
                JSONObject().put("k", e.key).put("c", e.chatId).put("t", e.chatTitle).put("m", e.msgId)
                    .put("f", e.fileName).put("s", e.size).put("mt", e.mime).put("d", e.durationSec)
                    .put("cp", e.caption).put("dt", e.dateSec),
            )
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun load(): List<CatalogItem> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                CatalogItem(
                    key = o.getString("k"),
                    chatId = o.getLong("c"),
                    chatTitle = o.optString("t"),
                    msgId = o.getLong("m"),
                    fileName = o.optString("f"),
                    size = o.optLong("s"),
                    mime = o.optString("mt"),
                    durationSec = o.optInt("d"),
                    caption = o.optString("cp"),
                    dateSec = o.optLong("dt"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clear() {
        file.delete()
    }
}

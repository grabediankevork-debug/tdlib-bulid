package com.couchreel.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thin client for the tgbridge JSON API. All calls are suspend functions and safe to call from the main thread. */
class Api(base: String, private val token: String) : Backend {

    override val isLocal = false
    override val version: StateFlow<Int> = MutableStateFlow(0)
    override val status: StateFlow<String> = MutableStateFlow("")

    private val root: HttpUrl = base.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Bad server address: $base")

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private fun url(path: String, last: String? = null, params: Map<String, String> = emptyMap()): HttpUrl {
        val b = root.newBuilder()
        b.addPathSegments(path)
        if (last != null) b.addPathSegment(last)
        for ((k, v) in params) b.addQueryParameter(k, v)
        if (token.isNotBlank()) b.addQueryParameter("token", token)
        return b.build()
    }

    private suspend fun exec(req: Request): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("Server said HTTP ${resp.code}" + if (text.isNotBlank()) ": ${text.take(100)}" else "")
            }
            JSONObject(text)
        }
    }

    private suspend fun get(path: String, last: String? = null, params: Map<String, String> = emptyMap()): JSONObject =
        exec(Request.Builder().url(url(path, last, params)).get().build())

    private suspend fun post(path: String, body: JSONObject): JSONObject =
        exec(
            Request.Builder().url(url(path))
                .post(body.toString().toRequestBody("application/json".toMediaType())).build(),
        )

    // -- URLs for the player -------------------------------------------------
    fun streamUrl(key: String): String = url("stream", key).toString()
    override fun liveUrl(channelId: String): String = url("live", channelId).toString()
    override fun watchlistUploadUrl(): String = url("watchlist-upload").toString()

    // -- parsing helpers -------------------------------------------------------
    private fun items(a: JSONArray?): List<Item> = a?.objects()?.map { Item.from(it) } ?: emptyList()

    private fun seriesCards(a: JSONArray?): List<SeriesCard> = a?.objects()?.map {
        SeriesCard(it.getString("sid"), it.optString("title"), it.optInt("episodes"), it.optInt("watched"))
    } ?: emptyList()

    private fun channels(a: JSONArray?): List<Channel> = a?.objects()?.map {
        Channel(it.getString("id"), it.optString("name"), it.optInt("count"))
    } ?: emptyList()

    // -- reads ------------------------------------------------------------
    override suspend fun home(): HomeData {
        val o = get("api/home")
        val recents = o.optJSONArray("searches")
        return HomeData(
            continueWatching = items(o.optJSONArray("continue")),
            nextUp = items(o.optJSONArray("next_up")),
            recent = items(o.optJSONArray("recent")),
            channels = channels(o.optJSONArray("channels")),
            watchlistAvailable = o.optInt("watchlist_available", 0),
            searches = if (recents == null) emptyList() else List(recents.length()) { recents.getString(it) },
        )
    }

    override suspend fun library(kind: String, allFiles: Boolean, sort: String, offset: Int, limit: Int): Page<Item> {
        val o = get(
            "api/library",
            params = mapOf(
                "kind" to kind,
                "all_files" to if (allFiles) "1" else "0",
                "sort" to sort,
                "offset" to offset.toString(),
                "limit" to limit.toString(),
            ),
        )
        return Page(items(o.optJSONArray("items")), o.optInt("total"))
    }

    override suspend fun seriesList(offset: Int, limit: Int): Page<SeriesCard> {
        val o = get("api/series", params = mapOf("offset" to offset.toString(), "limit" to limit.toString()))
        return Page(seriesCards(o.optJSONArray("items")), o.optInt("total"))
    }

    override suspend fun seriesDetail(sid: String): SeriesDetail {
        val o = get("api/series", last = sid)
        val seasons = (o.optJSONArray("seasons") ?: JSONArray()).objects().map {
            SeasonBlock(it.getInt("season"), items(it.optJSONArray("episodes")))
        }
        return SeriesDetail(
            sid = o.getString("sid"),
            title = o.optString("title"),
            episodes = o.optInt("episodes"),
            watched = o.optInt("watched"),
            nextUp = o.optJSONObject("next_up")?.let { Item.from(it) },
            seasons = seasons,
        )
    }

    /** [save] = true remembers the query in "recent searches"; live-as-you-type previews pass false. */
    override suspend fun search(q: String, save: Boolean): SearchResult {
        val o = get("api/search", params = mapOf("q" to q, "save" to if (save) "1" else "0"))
        val recents = o.optJSONArray("searches")
        return SearchResult(
            movies = items(o.optJSONArray("movies")),
            series = seriesCards(o.optJSONArray("series")),
            searches = if (recents == null) emptyList() else List(recents.length()) { recents.getString(it) },
        )
    }

    override suspend fun next(key: String): Item? {
        val o = get("api/next", params = mapOf("key" to key))
        return o.optJSONObject("next")?.let { Item.from(it) }
    }

    override suspend fun sources(): List<Source> {
        val o = get("api/sources")
        return (o.optJSONArray("items") ?: JSONArray()).objects().map {
            Source(it.optLong("chat_id"), it.optString("title"), it.optInt("videos"))
        }
    }

    override suspend fun guide(hours: Int): List<GuideChannel> {
        val o = get("api/guide", params = mapOf("hours" to hours.toString()))
        return (o.optJSONArray("channels") ?: JSONArray()).objects().map { c ->
            GuideChannel(
                id = c.getString("id"),
                name = c.optString("name"),
                programmes = (c.optJSONArray("programmes") ?: JSONArray()).objects().map { p ->
                    Programme(
                        start = p.optString("start"),
                        end = p.optString("end"),
                        title = p.optString("title"),
                        key = p.optString("key"),
                        onNow = p.optBoolean("on_now"),
                    )
                },
            )
        }
    }

    override suspend fun watchlist(): Watchlist {
        val o = get("api/watchlist")
        val films = (o.optJSONArray("films") ?: JSONArray()).objects().map {
            WatchFilm(
                name = it.optString("name"),
                year = it.intOrNull("year"),
                status = it.optString("status"),
                item = it.optJSONObject("item")?.let { j -> Item.from(j) },
            )
        }
        return Watchlist(films, o.optInt("available"), o.optInt("total"))
    }

    // -- writes -----------------------------------------------------------
    override suspend fun progress(key: String, positionMs: Long, durationMs: Long) {
        post(
            "api/progress",
            JSONObject().put("key", key).put("position_ms", positionMs).put("duration_ms", durationMs),
        )
    }

    override suspend fun setWatched(key: String, watched: Boolean) {
        post("api/watched", JSONObject().put("key", key).put("watched", watched))
    }

    override suspend fun setSeriesWatched(sid: String, watched: Boolean) {
        post("api/series_watched", JSONObject().put("sid", sid).put("watched", watched))
    }

    // -- hub auto-join jobs -------------------------------------------------
    override suspend fun hubScan(channel: String): String {
        val o = post("api/hub/scan", JSONObject().put("channel", channel))
        return o.getString("job")
    }

    override suspend fun hubJoin(entries: List<HubEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().put("kind", e.kind).put("key", e.key).put("label", e.label))
        }
        return post("api/hub/join", JSONObject().put("links", arr)).getString("job")
    }

    override suspend fun job(id: String): JobInfo {
        val o = get("api/job", params = mapOf("id" to id))
        val p = o.optJSONObject("progress")
        return JobInfo(
            id = id,
            state = o.optString("state"),
            done = p?.optInt("done") ?: 0,
            total = p?.optInt("total") ?: 0,
            error = o.optString("error"),
            result = o.optJSONArray("result"),
        )
    }

    /** Cheap reachability check used by the settings screen. */
    override suspend fun ping(): Int {
        val o = get("api/sources")
        return (o.optJSONArray("items") ?: JSONArray()).length()
    }

    override suspend fun liveKeyNow(channelId: String): String? = null

    override suspend fun importWatchlist(csv: String) {
        exec(Request.Builder().url(url("api/watchlist")).post(csv.toRequestBody("text/csv".toMediaType())).build())
    }

    override suspend fun rescan() {
        // server mode: the server owns the index (re-run `tgbridge.py index` there)
    }
}

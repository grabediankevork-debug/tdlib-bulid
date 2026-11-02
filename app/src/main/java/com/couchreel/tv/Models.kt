package com.couchreel.tv

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

fun JSONObject.str(k: String): String? = if (!has(k) || isNull(k)) null else getString(k)
fun JSONObject.intOrNull(k: String): Int? = if (!has(k) || isNull(k)) null else getInt(k)
fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

data class Item(
    val id: String,
    val title: String,
    val year: Int?,
    val kind: String,
    val season: Int?,
    val episode: Int?,
    val sid: String?,
    val sizeGb: Double,
    val versions: Int,
    val watched: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val channel: String,
    val fileName: String,
) {
    val isEpisode: Boolean get() = kind == "episode"

    val episodeCode: String
        get() = if (isEpisode) String.format(Locale.ROOT, "S%02dE%02d", season ?: 0, episode ?: 0) else ""

    val subtitle: String
        get() {
            val parts = mutableListOf<String>()
            if (isEpisode) {
                parts.add(episodeCode)
            } else if (year != null) {
                parts.add(year.toString())
            }
            parts.add(String.format(Locale.ROOT, "%.1f GB", sizeGb))
            if (versions > 1) parts.add("$versions versions")
            return parts.joinToString(" · ")
        }

    val progress: Float
        get() = if (durationMs > 0 && positionMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    companion object {
        fun from(o: JSONObject) = Item(
            id = o.getString("id"),
            title = o.optString("title", ""),
            year = o.intOrNull("year"),
            kind = o.optString("kind", "unknown"),
            season = o.intOrNull("season"),
            episode = o.intOrNull("episode"),
            sid = o.str("sid"),
            sizeGb = o.optDouble("size_gb", 0.0),
            versions = o.optInt("versions", 1),
            watched = o.optBoolean("watched", false),
            positionMs = o.optLong("position_ms", 0L),
            durationMs = o.optLong("duration_ms", 0L),
            channel = o.optString("channel", ""),
            fileName = o.optString("file_name", ""),
        )
    }
}

data class SeriesCard(val sid: String, val title: String, val episodes: Int, val watched: Int)
data class Channel(val id: String, val name: String, val count: Int)
data class Page<T>(val items: List<T>, val total: Int)

data class HomeData(
    val continueWatching: List<Item>,
    val nextUp: List<Item>,
    val recent: List<Item>,
    val channels: List<Channel>,
    val watchlistAvailable: Int,
    val searches: List<String>,
)

data class SeasonBlock(val season: Int, val episodes: List<Item>)

data class SeriesDetail(
    val sid: String,
    val title: String,
    val episodes: Int,
    val watched: Int,
    val nextUp: Item?,
    val seasons: List<SeasonBlock>,
)

data class SearchResult(val movies: List<Item>, val series: List<SeriesCard>, val searches: List<String>)
data class Source(val chatId: Long, val title: String, val videos: Int)
data class Programme(val start: String, val end: String, val title: String, val key: String, val onNow: Boolean)
data class GuideChannel(val id: String, val name: String, val programmes: List<Programme>)
data class WatchFilm(val name: String, val year: Int?, val status: String, val item: Item?)
data class Watchlist(val films: List<WatchFilm>, val available: Int, val total: Int)

data class HubEntry(
    val kind: String,
    val key: String,
    val label: String,
    val title: String,
    val videos: Int,
    val status: String,
)

data class JobInfo(
    val id: String,
    val state: String,
    val done: Int,
    val total: Int,
    val error: String,
    val result: JSONArray?,
)

data class ServerConfig(val base: String, val token: String)

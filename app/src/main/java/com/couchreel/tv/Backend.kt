package com.couchreel.tv

import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the screens need. Two implementations:
 *  - [Api]          talks to a tgbridge server over HTTP (server mode)
 *  - [LocalBackend] talks to Telegram directly through TDLib (direct mode, no server)
 */
interface Backend {
    /** true when running directly on TDLib */
    val isLocal: Boolean

    /** increments whenever the library changes (e.g. a scan finished), so screens reload */
    val version: StateFlow<Int>

    /** human-readable background status, e.g. "Scanning 3/12: Movies HD"; empty when idle */
    val status: StateFlow<String>

    suspend fun home(): HomeData
    suspend fun library(kind: String, allFiles: Boolean, sort: String, offset: Int, limit: Int): Page<Item>
    suspend fun seriesList(offset: Int, limit: Int): Page<SeriesCard>
    suspend fun seriesDetail(sid: String): SeriesDetail
    suspend fun search(q: String, save: Boolean): SearchResult
    suspend fun next(key: String): Item?
    suspend fun sources(): List<Source>
    suspend fun guide(hours: Int): List<GuideChannel>
    suspend fun watchlist(): Watchlist

    suspend fun progress(key: String, positionMs: Long, durationMs: Long)
    suspend fun setWatched(key: String, watched: Boolean)
    suspend fun setSeriesWatched(sid: String, watched: Boolean)

    suspend fun hubScan(channel: String): String
    suspend fun hubJoin(entries: List<HubEntry>): String
    suspend fun job(id: String): JobInfo

    suspend fun ping(): Int

    /** server mode only: page where a watchlist.csv can be uploaded from a browser */
    fun watchlistUploadUrl(): String

    /** server mode only: URL that redirects to what is on a channel right now */
    fun liveUrl(channelId: String): String

    /** direct mode: the library key airing on a channel right now (null if none / server mode) */
    suspend fun liveKeyNow(channelId: String): String?

    /** direct mode: channel customisation (own channels, hidden built-ins, slot length); null in server mode */
    suspend fun channelEditor(): ChannelEditorInfo?
    suspend fun saveChannels(cfg: ChannelConfig)
    suspend fun previewChannel(c: CustomChannel): Int

    /** import a Letterboxd watchlist.csv given as text */
    suspend fun importWatchlist(csv: String)

    /** direct mode: scan Telegram channels again */
    suspend fun rescan()
}

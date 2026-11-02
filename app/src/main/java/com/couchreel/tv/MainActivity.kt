package com.couchreel.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

sealed interface Route {
    data object Home : Route
    data object Movies : Route
    data object Series : Route
    data class SeriesDetail(val sid: String) : Route
    data object Search : Route
    data object Channels : Route
    data object ChannelEditor : Route
    data object Watchlist : Route
    data object Hubs : Route
    data object Settings : Route
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        setContent {
            CouchTheme {
                CouchApp(settings)
            }
        }
    }
}

private val tabs: List<Pair<String, Route>> = listOf(
    "Home" to Route.Home,
    "Movies" to Route.Movies,
    "Series" to Route.Series,
    "Search" to Route.Search,
    "Channels" to Route.Channels,
    "Watchlist" to Route.Watchlist,
    "Join channels" to Route.Hubs,
    "Settings" to Route.Settings,
)

/** (title, library key, direct url or null, is-live, start position ms) */
typealias StartPlayer = (String, String, String?, Boolean, Long) -> Unit

private fun playerIntent(
    ctx: Context,
    base: String,
    token: String,
    local: Boolean,
    title: String,
    key: String,
    url: String?,
    live: Boolean,
    startMs: Long,
): Intent {
    val i = Intent(ctx, PlayerActivity::class.java)
        .putExtra("base", base)
        .putExtra("token", token)
        .putExtra("local", local)
        .putExtra("title", title)
        .putExtra("key", key)
        .putExtra("live", live)
        .putExtra("start", startMs)
    if (url != null) i.putExtra("url", url)
    return i
}

/** API keys baked in at build time (gradle.properties: tgApiId / tgApiHash), if any. */
private fun builtInCreds(): Pair<Int, String>? =
    if (BuildConfig.TG_API_ID != 0 && BuildConfig.TG_API_HASH.isNotBlank()) {
        Pair(BuildConfig.TG_API_ID, BuildConfig.TG_API_HASH)
    } else {
        null
    }

@Composable
fun CouchApp(settings: Settings) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(settings.mode) }
    var serverCfg by remember { mutableStateOf(settings.load()) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 48.dp, vertical = 24.dp),
    ) {
        when (mode) {
            "server" -> {
                val c = serverCfg
                if (c == null) {
                    SettingsScreen(initial = null, first = true) {
                        settings.save(it)
                        serverCfg = it
                    }
                } else {
                    val api = remember(c) { Api(c.base, c.token) }
                    ConnectedApp(
                        api = api,
                        startPlayer = { title, key, url, live, start ->
                            ctx.startActivity(playerIntent(ctx, c.base, c.token, false, title, key, url, live, start))
                        },
                    ) { go ->
                        SettingsScreen(initial = c, first = false) {
                            settings.save(it)
                            serverCfg = it
                            go(Route.Home)
                        }
                    }
                }
            }
            "direct" -> DirectGate(settings, ctx) {
                settings.mode = null
                mode = null
            }
            else -> ModeScreen { m ->
                settings.mode = m
                mode = m
            }
        }
    }
}

@Composable
private fun DirectGate(settings: Settings, ctx: Context, onBackToMode: () -> Unit) {
    var creds by remember { mutableStateOf(builtInCreds() ?: settings.loadCreds()) }
    val c = creds
    if (c == null) {
        CredentialsScreen { id, hash ->
            settings.saveCreds(id, hash)
            creds = Pair(id, hash)
        }
        return
    }
    val td = remember(c) { Graph.startTd(c.first, c.second) }
    val auth by td.auth.collectAsState()

    when (val a = auth) {
        is TdClient.Auth.Starting -> Centered("Starting Telegram…")
        is TdClient.Auth.Closed -> Centered("The Telegram session was closed. Close and reopen the app.")
        is TdClient.Auth.Failed -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Couldn't start Telegram", color = TextPrimary, fontSize = 26.sp)
            Text(a.message, color = TextDim, fontSize = 17.sp)
            ButtonRow { Chip("Back") { onBackToMode() } }
        }
        is TdClient.Auth.Ready -> {
            val backend = remember { Graph.localBackend() }
            ConnectedApp(
                api = backend,
                startPlayer = { title, key, url, live, start ->
                    ctx.startActivity(playerIntent(ctx, "", "", true, title, key, url, live, start))
                },
            ) { _ ->
                DirectSettingsScreen(backend, onSwitchMode = onBackToMode, onLoggedOut = onBackToMode)
            }
        }
        else -> LoginScreen(td, a)
    }
}

@Composable
private fun ConnectedApp(
    api: Backend,
    startPlayer: StartPlayer,
    settingsContent: @Composable (go: (Route) -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val ver by api.version.collectAsState()
    val tick = rememberResumeTick() + ver
    val route = stack.last()

    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    val go: (Route) -> Unit = { r ->
        if (r is Route.SeriesDetail) {
            stack.add(r)
        } else {
            stack.clear()
            stack.add(Route.Home)
            if (r != Route.Home) stack.add(r)
        }
    }
    val play: Play = { item ->
        val label = if (item.isEpisode) "${item.title}  ${item.episodeCode}" else item.title
        startPlayer(label, item.id, null, false, if (item.watched) 0L else item.positionMs)
    }
    val playLive: (String, String) -> Unit = { channelId, name ->
        if (api.isLocal) {
            scope.launch {
                val key = api.liveKeyNow(channelId)
                if (key != null) startPlayer(name, key, null, true, 0L)
            }
        } else {
            startPlayer(name, "", api.liveUrl(channelId), true, 0L)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 18.dp)) {
            tabs.forEach { (label, target) ->
                val selected = when (route) {
                    is Route.SeriesDetail -> target == Route.Series
                    Route.ChannelEditor -> target == Route.Channels
                    else -> target == route
                }
                Chip(label, selected = selected) { go(target) }
            }
        }
        Box(Modifier.weight(1f)) {
            when (route) {
                Route.Home -> HomeScreen(api, tick, play, go)
                Route.Movies -> MoviesScreen(api, tick, play)
                Route.Series -> SeriesScreen(api, tick, go)
                is Route.SeriesDetail -> SeriesDetailScreen(api, route.sid, tick, play)
                Route.Search -> SearchScreen(api, tick, play, go)
                Route.Channels -> ChannelsScreen(api, tick, playLive) { go(Route.ChannelEditor) }
                Route.ChannelEditor -> ChannelEditorScreen(api, tick) { go(Route.Channels) }
                Route.Watchlist -> WatchlistScreen(api, tick, play)
                Route.Hubs -> HubsScreen(api)
                Route.Settings -> settingsContent(go)
            }
        }
    }
}

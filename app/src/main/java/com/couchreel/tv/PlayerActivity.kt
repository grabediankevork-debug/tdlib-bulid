package com.couchreel.tv

import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Full-screen player. D-pad: OK shows/hides controls, left/right seek, the settings cog on the control
 * bar switches audio track and subtitles. Reports position so Continue Watching works, and moves on to
 * the next episode when one ends.
 *
 * Two sources: a tgbridge server URL (server mode), or a Telegram file streamed straight from TDLib
 * (direct mode, see TdDataSource).
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var server: Api? = null
    private var backend: Backend? = null
    private var localBackend: LocalBackend? = null
    private var key: String = ""
    private var title: String = ""
    private var live = false
    private var fileId = 0

    // survives the activity so the final progress report is not cancelled when it closes
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val isLocal = intent.getBooleanExtra("local", false)
        key = intent.getStringExtra("key").orEmpty()
        title = intent.getStringExtra("title").orEmpty()
        live = intent.getBooleanExtra("live", false)
        val startMs = intent.getLongExtra("start", 0L)
        val url = intent.getStringExtra("url")

        if (isLocal) {
            val lb = Graph.local
            if (lb == null) {
                Toast.makeText(this, "Telegram session not ready. Open the app again.", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            localBackend = lb
            backend = lb
        } else {
            val api = try {
                Api(intent.getStringExtra("base").orEmpty(), intent.getStringExtra("token").orEmpty())
            } catch (e: Exception) {
                Toast.makeText(this, "Bad server settings", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            server = api
            backend = api
        }

        val view = PlayerView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            useController = true
            controllerShowTimeoutMs = 4000
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(view)

        val p = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player = p
        view.player = p
        view.requestFocus()

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    "Can't play this file: ${error.errorCodeName}. Try another version of the title.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        })

        Toast.makeText(this, title, Toast.LENGTH_SHORT).show()
        startPlayback(key, url, startMs)

        if (key.isNotEmpty() && !live) {
            lifecycleScope.launch {
                while (isActive) {
                    delay(15_000)
                    report(false)
                }
            }
        }
    }

    private fun startPlayback(k: String, url: String?, startMs: Long) {
        val p = player ?: return
        lifecycleScope.launch {
            try {
                val start = if (startMs > 5_000) startMs else C.TIME_UNSET
                val lb = localBackend
                if (lb != null) {
                    discardFile()
                    val td = Graph.td ?: throw IllegalStateException("Telegram is not running")
                    val fid = lb.fileIdFor(k)
                    fileId = fid
                    val src = ProgressiveMediaSource.Factory(TdDataSource.Factory(td))
                        .createMediaSource(MediaItem.fromUri("td://file/$fid"))
                    p.setMediaSource(src, start)
                } else {
                    val target = url ?: server?.streamUrl(k) ?: throw IllegalStateException("No address to play")
                    p.setMediaItem(MediaItem.fromUri(target), start)
                }
                p.prepare()
                p.playWhenReady = true
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, "Can't start playback: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    /** Direct mode: stop downloading and remove the cached copy so videos never pile up on the device. */
    private fun discardFile() {
        val id = fileId
        if (id != 0) {
            Graph.td?.let {
                it.cancelDownload(id)
                it.deleteFile(id)
            }
            fileId = 0
        }
    }

    private fun report(finished: Boolean) {
        val p = player ?: return
        val b = backend ?: return
        if (key.isEmpty() || live) return
        val dur = p.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        val pos = if (finished) dur else p.currentPosition.coerceIn(0L, dur)
        val k = key
        bg.launch {
            try {
                b.progress(k, pos, dur)
            } catch (e: Exception) {
                // a short outage is fine; the next report catches up
            }
        }
    }

    private fun onEnded() {
        val b = backend
        if (key.isEmpty() || live || b == null) {
            finish()
            return
        }
        val finishedKey = key
        report(true)
        lifecycleScope.launch {
            val next = try {
                b.next(finishedKey)
            } catch (e: Exception) {
                null
            }
            if (next == null) {
                finish()
            } else {
                key = next.id
                title = "${next.title}  ${next.episodeCode}"
                Toast.makeText(this@PlayerActivity, "Next: $title", Toast.LENGTH_LONG).show()
                delay(3000)
                startPlayback(next.id, null, 0L)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        report(false)
        player?.pause()
    }

    override fun onDestroy() {
        report(false)
        player?.release()
        player = null
        discardFile()
        super.onDestroy()
    }
}

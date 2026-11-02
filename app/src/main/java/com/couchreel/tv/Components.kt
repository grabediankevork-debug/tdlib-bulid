package com.couchreel.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

// ---------------------------------------------------------------- loading state

sealed interface Ui<out T> {
    data object Loading : Ui<Nothing>
    data class Ok<T>(val value: T) : Ui<T>
    data class Err(val message: String) : Ui<Nothing>
}

/** Runs [block] again whenever any of [keys] change; keeps showing the previous value while reloading. */
@Composable
fun <T> loadState(vararg keys: Any?, block: suspend () -> T): State<Ui<T>> =
    produceState<Ui<T>>(Ui.Loading, *keys) {
        value = try {
            Ui.Ok(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Ui.Err(e.message ?: e.javaClass.simpleName)
        }
    }

@Composable
fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = TextDim, fontSize = 20.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun <T> Gate(ui: Ui<T>, content: @Composable (T) -> Unit) {
    when (ui) {
        is Ui.Loading -> Centered("Loading…")
        is Ui.Err -> Centered("Couldn't reach your server\n${ui.message}\n\nCheck Settings.")
        is Ui.Ok -> content(ui.value)
    }
}

/** Bumps every time the app returns to the foreground (e.g. after the player closes). */
@Composable
fun rememberResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val seenFirst = booleanArrayOf(false)
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (seenFirst[0]) tick++ else seenFirst[0] = true
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

// ---------------------------------------------------------------- focus memory

/** Remembers the last focused item and scroll position per screen so Back returns you where you were. */
object FocusMemory {
    val last = HashMap<String, String>()
    private val lists = HashMap<String, LazyListState>()
    private val grids = HashMap<String, LazyGridState>()

    fun list(key: String): LazyListState = lists.getOrPut(key) { LazyListState() }
    fun grid(key: String): LazyGridState = grids.getOrPut(key) { LazyGridState() }
}

/**
 * Tracks focus for [id] on [screen]. The remembered item (or, when nothing is remembered, the one
 * flagged [defaultFirst]) takes focus when it first appears.
 */
@Composable
fun Modifier.focusMemory(screen: String, id: String, defaultFirst: Boolean = false): Modifier {
    val requester = remember { FocusRequester() }
    val remembered = FocusMemory.last[screen]
    val wants = if (remembered != null) remembered == id else defaultFirst
    LaunchedEffect(wants) {
        if (wants) {
            delay(150)
            try {
                requester.requestFocus()
            } catch (e: Exception) {
                // item not attached yet; the user can still navigate with the D-pad
            }
        }
    }
    return this
        .focusRequester(requester)
        .onFocusChanged { if (it.isFocused) FocusMemory.last[screen] = id }
}

// ---------------------------------------------------------------- remote-friendly click

/**
 * Click on OK/Enter, or a long press (hold OK ~0.5 s) for a secondary action. Pointer clicks still work.
 */
@Composable
fun Modifier.tvClickable(onLong: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    var longFired by remember { mutableStateOf(false) }
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLong)
    return this
        .onPreviewKeyEvent { ev ->
            val k = ev.key
            if (k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter) {
                when (ev.type) {
                    KeyEventType.KeyDown -> {
                        val repeats = ev.nativeKeyEvent.repeatCount
                        if (repeats == 0) {
                            longFired = false
                        } else if (repeats == 1 && !longFired) {
                            val action = long
                            if (action != null) {
                                longFired = true
                                action()
                            }
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        if (!longFired) click()
                        longFired = false
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
        .clickable(onClick = onClick)
}

// ---------------------------------------------------------------- widgets

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        color = TextPrimary,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
fun Chip(text: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) Accent else if (selected) PanelHi else Panel)
            .tvClickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (focused) Color.Black else TextPrimary,
            fontSize = 16.sp,
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

private fun hueOf(seed: String): Float = ((seed.hashCode() and 0x7fffffff) % 360).toFloat()

@Composable
fun MediaCard(
    title: String,
    subtitle: String,
    seed: String,
    modifier: Modifier = Modifier,
    watched: Boolean = false,
    progress: Float = 0f,
    onLong: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val hue = hueOf(seed)
    Box(
        modifier = modifier
            .width(220.dp)
            .height(132.dp)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                val s = if (focused) 1.06f else 1f
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(Color.hsv(hue, 0.45f, 0.40f), Color.hsv((hue + 40f) % 360f, 0.5f, 0.22f)),
                ),
            )
            .border(if (focused) 3.dp else 0.dp, if (focused) Accent else Color.Transparent, shape)
            .tvClickable(onLong, onClick),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
        ) {
            Text(
                title,
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (watched) {
            Text(
                "✓",
                color = Accent,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
            )
        }
        if (progress > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress)
                    .height(5.dp)
                    .background(Accent),
            )
        }
    }
}

@Composable
fun ItemCard(item: Item, modifier: Modifier = Modifier, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    MediaCard(
        title = item.title,
        subtitle = item.subtitle,
        seed = item.title,
        modifier = modifier,
        watched = item.watched,
        progress = item.progress,
        onLong = onLong,
        onClick = onClick,
    )
}

@Composable
fun PanelRow(
    modifier: Modifier = Modifier,
    onLong: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) PanelHi else Panel)
            .border(if (focused) 2.dp else 0.dp, if (focused) Accent else Color.Transparent, shape)
            .tvClickable(onLong, onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        content = content,
    )
}

/** A titled horizontally scrolling row; every item gets a [Modifier] that carries focus memory. */
@Composable
fun <T> CardRow(
    title: String,
    screen: String,
    rowKey: String,
    items: List<T>,
    id: (T) -> String,
    firstRow: Boolean,
    content: @Composable (T, Modifier) -> Unit,
) {
    Column {
        SectionTitle(title)
        LazyRow(
            state = FocusMemory.list("$screen/$rowKey"),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp, horizontal = 4.dp),
        ) {
            itemsIndexed(items, key = { _, it -> id(it) }) { index, it ->
                content(it, Modifier.focusMemory(screen, id(it), defaultFirst = firstRow && index == 0))
            }
        }
    }
}

@Composable
fun StatusLine(text: String) {
    if (text.isNotBlank()) {
        Text(text, color = TextDim, fontSize = 16.sp, modifier = Modifier.padding(vertical = 6.dp))
    }
}

@Composable
fun ButtonRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        content()
    }
}

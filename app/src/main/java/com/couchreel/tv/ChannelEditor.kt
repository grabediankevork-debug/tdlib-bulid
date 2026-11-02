package com.couchreel.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Direct mode: make your own channels, hide the built-in ones, choose how channels are ordered.

private val KINDS = listOf(
    "title" to "Title words",
    "decade" to "Decade",
    "source" to "Telegram channel",
    "movies" to "All movies",
    "episodes" to "All episodes",
)

private val MODES = listOf(
    "shuffle" to "Shuffle",
    "cyclic" to "Shows in turn",
    "blocks" to "Blocks of 3",
    "ordered" to "In order",
)

private val PADS = listOf(0, 15, 30, 60)

private fun kindLabel(k: String) = KINDS.firstOrNull { it.first == k }?.second ?: k
private fun modeLabel(m: String) = MODES.firstOrNull { it.first == m }?.second ?: m

private fun describe(c: CustomChannel): String {
    val rule = when (c.kind) {
        "title" -> "titles with: ${c.value}"
        "decade" -> "movies from the ${c.value}s"
        "source" -> "from ${c.value}"
        else -> kindLabel(c.kind).lowercase()
    }
    return "$rule  ·  ${modeLabel(c.mode)}"
}

@Composable
fun ChannelEditorScreen(api: Backend, tick: Int, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var bump by remember { mutableIntStateOf(0) }
    var editIndex by remember { mutableIntStateOf(-2) } // -2 = list, -1 = new channel, >=0 = existing
    var draft by remember { mutableStateOf(CustomChannel("", "title", "", "shuffle")) }
    var msg by remember { mutableStateOf("") }
    val ui by loadState(tick, bump) { api.channelEditor() }

    Gate(ui) { info ->
        if (info == null) {
            Centered("Channel editing is available when the app talks to Telegram directly.")
            return@Gate
        }
        val cfg = info.config

        fun save(next: ChannelConfig, done: String = "") {
            scope.launch {
                try {
                    api.saveChannels(next)
                    msg = done
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    msg = "Couldn't save: ${e.message}"
                }
                bump++
            }
        }

        if (editIndex != -2) {
            ChannelForm(
                api = api,
                info = info,
                draft = draft,
                isNew = editIndex == -1,
                onChange = { draft = it },
                onCancel = { editIndex = -2 },
                onDelete = {
                    save(cfg.copy(custom = cfg.custom.filterIndexed { i, _ -> i != editIndex }), "Channel deleted.")
                    editIndex = -2
                },
                onSave = {
                    val d = it.copy(name = it.name.trim().ifEmpty { defaultName(it) })
                    val list = if (editIndex == -1) cfg.custom + d else cfg.custom.mapIndexed { i, c -> if (i == editIndex) d else c }
                    save(cfg.copy(custom = list), "Saved “${d.name}”.")
                    editIndex = -2
                },
            )
            return@Gate
        }

        LazyColumn(
            state = FocusMemory.list("chedit"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ButtonRow {
                    Chip("New channel", Modifier.focusMemory("chedit", "new", true)) {
                        draft = CustomChannel("", "title", "", "shuffle")
                        editIndex = -1
                    }
                    Chip(if (cfg.padMinutes == 0) "Slots: exact length" else "Slots: ${cfg.padMinutes} min") {
                        val next = PADS[(PADS.indexOf(cfg.padMinutes).let { if (it < 0) 0 else it } + 1) % PADS.size]
                        save(cfg.copy(padMinutes = next))
                    }
                    Chip("Back to channels") { onDone() }
                }
            }
            if (msg.isNotBlank()) item { StatusLine(msg) }
            item { SectionTitle("Your channels") }
            if (cfg.custom.isEmpty()) {
                item { Text("None yet. Press “New channel”.", color = TextDim, fontSize = 16.sp) }
            }
            itemsIndexed(cfg.custom) { i, c ->
                PanelRow(modifier = Modifier.focusMemory("chedit", "c$i"), onClick = {
                    draft = c
                    editIndex = i
                }) {
                    Text(c.name, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(describe(c), color = TextDim, fontSize = 14.sp)
                }
            }
            item { SectionTitle("Built-in channels (press to show or hide)") }
            items(info.builtIn, key = { "b:$it" }) { name ->
                val hidden = name in cfg.hidden
                PanelRow(modifier = Modifier.focusMemory("chedit", "b:$name"), onClick = {
                    save(cfg.copy(hidden = if (hidden) cfg.hidden - name else cfg.hidden + name))
                }) {
                    Text((if (hidden) "☐  " else "☑  ") + name, color = if (hidden) TextDim else TextPrimary, fontSize = 18.sp)
                }
            }
        }
    }
}

private fun defaultName(c: CustomChannel): String = when (c.kind) {
    "title" -> c.value.split(',').firstOrNull()?.trim()?.ifEmpty { null }?.let { "$it channel" } ?: "My channel"
    "decade" -> "${c.value}s movies"
    "source" -> c.value.ifEmpty { "My channel" }
    "movies" -> "My movies"
    else -> "My shows"
}

@Composable
private fun ChannelForm(
    api: Backend,
    info: ChannelEditorInfo,
    draft: CustomChannel,
    isNew: Boolean,
    onChange: (CustomChannel) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSave: (CustomChannel) -> Unit,
) {
    var count by remember { mutableIntStateOf(-1) }
    LaunchedEffect(draft.kind, draft.value) {
        count = try {
            api.previewChannel(draft)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            -1
        }
    }

    // keep a sensible value when the rule type changes
    fun withKind(k: String): CustomChannel = when (k) {
        "decade" -> draft.copy(kind = k, value = info.decades.firstOrNull()?.toString() ?: "")
        "source" -> draft.copy(kind = k, value = info.sources.firstOrNull() ?: "")
        "title" -> draft.copy(kind = k, value = if (draft.kind == "title") draft.value else "")
        else -> draft.copy(kind = k, value = "")
    }

    fun <T> cycle(list: List<T>, current: T): T = list[(list.indexOf(current).let { if (it < 0) -1 else it } + 1) % list.size]

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (isNew) "New channel" else "Edit channel", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it)) },
            singleLine = true,
            label = { Text("Channel name (optional)") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("What plays on it", color = TextDim, fontSize = 14.sp)
        ButtonRow {
            KINDS.forEach { (k, label) -> Chip(label, selected = draft.kind == k) { if (draft.kind != k) onChange(withKind(k)) } }
        }
        when (draft.kind) {
            "title" -> OutlinedTextField(
                value = draft.value,
                onValueChange = { onChange(draft.copy(value = it)) },
                singleLine = true,
                label = { Text("Words in the title, separated by commas (e.g. friends, seinfeld)") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            "decade" -> if (info.decades.isEmpty()) Text("No movies with a year in your library yet.", color = TextDim)
            else Chip("Decade: ${draft.value}s  (press to change)") {
                onChange(draft.copy(value = cycle(info.decades.map { it.toString() }, draft.value)))
            }
            "source" -> if (info.sources.isEmpty()) Text("No Telegram channels scanned yet.", color = TextDim)
            else Chip("Channel: ${draft.value}  (press to change)") { onChange(draft.copy(value = cycle(info.sources, draft.value))) }
            else -> {}
        }
        Text("How it is ordered", color = TextDim, fontSize = 14.sp)
        ButtonRow {
            MODES.forEach { (m, label) -> Chip(label, selected = draft.mode == m) { onChange(draft.copy(mode = m)) } }
        }
        Text(
            when {
                count < 0 -> ""
                count == 0 -> "Nothing in your library matches yet, so this channel won't appear."
                else -> "$count titles match."
            },
            color = if (count == 0) Accent else TextDim,
            fontSize = 16.sp,
        )
        ButtonRow {
            Chip("Save", Modifier.focusMemory("chform", "save", true)) { onSave(draft) }
            Chip("Cancel") { onCancel() }
            if (!isNew) Chip("Delete") { onDelete() }
        }
    }
}

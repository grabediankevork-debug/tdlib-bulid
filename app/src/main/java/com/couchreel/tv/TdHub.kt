package com.couchreel.tv

import kotlinx.coroutines.delay
import org.drinkless.tdlib.TdApi
import java.util.Random

/**
 * "Hub channel" support: read a channel whose posts link to other channels, and join the linked
 * channels that actually contain video. Port of hubjoin.py, using TDLib directly.
 */
class TdHub(private val td: TdClient) {

    class JoinResult(val label: String, val ok: Boolean, val error: String)

    // ------------------------------------------------------------ link extraction (pure, unit-tested)

    companion object {
        private val LINK_RE = Regex(
            "(?:https?://)?(?:t\\.me|telegram\\.me)/(\\+[\\w-]+|joinchat/[\\w-]+|[A-Za-z]\\w{3,31})(?:/\\d+)?",
            RegexOption.IGNORE_CASE,
        )
        private val RESERVED = setOf("s", "c", "share", "addstickers", "addemoji", "proxy", "socks", "login", "iv")

        /** Returns ordered unique pairs of ("public"|"invite", username-or-hash). */
        fun extractLinks(texts: Iterable<String>): List<Pair<String, String>> {
            val seen = LinkedHashSet<Pair<String, String>>()
            for (t in texts) {
                for (m in LINK_RE.findAll(t)) {
                    val path = m.groupValues[1]
                    when {
                        path.startsWith("+") -> seen.add(Pair("invite", path.substring(1)))
                        path.lowercase().startsWith("joinchat/") -> seen.add(Pair("invite", path.substringAfter('/')))
                        path.lowercase() in RESERVED -> {}
                        else -> seen.add(Pair("public", path))
                    }
                }
            }
            return seen.toList()
        }
    }

    // ------------------------------------------------------------ collecting links from a hub

    private fun formatted(ft: TdApi.FormattedText?, out: MutableList<String>) {
        if (ft == null) return
        out.add(ft.text ?: "")
        for (e in ft.entities ?: emptyArray()) {
            val t = e.type
            if (t is TdApi.TextEntityTypeTextUrl) out.add(t.url ?: "")
        }
    }

    private fun textsOf(m: TdApi.Message, out: MutableList<String>) {
        when (val c = m.content) {
            is TdApi.MessageText -> formatted(c.text, out)
            is TdApi.MessageVideo -> formatted(c.caption, out)
            is TdApi.MessageDocument -> formatted(c.caption, out)
            is TdApi.MessagePhoto -> formatted(c.caption, out)
            is TdApi.MessageAnimation -> formatted(c.caption, out)
            else -> {}
        }
        val rm = m.replyMarkup
        if (rm is TdApi.ReplyMarkupInlineKeyboard) {
            for (row in rm.rows) {
                for (b in row) {
                    val t = b.type
                    if (t is TdApi.InlineKeyboardButtonTypeUrl) out.add(t.url ?: "")
                }
            }
        }
    }

    private suspend fun resolveHub(ref: String): TdApi.Chat {
        val r = ref.trim()
        r.toLongOrNull()?.let { return td.getChat(it) }
        val m = LINK_RE.find(r)
        val username = if (m != null && !m.groupValues[1].startsWith("+")) m.groupValues[1] else r.removePrefix("@")
        return td.searchPublicChat(username)
    }

    private suspend fun collectLinks(hub: TdApi.Chat, scanLimit: Int): List<Pair<String, String>> {
        val texts = ArrayList<String>()
        var from = 0L
        var seen = 0
        var empty = 0
        while (seen < scanLimit) {
            val h = TdApi.GetChatHistory()
            h.chatId = hub.id
            h.fromMessageId = from
            h.offset = 0
            h.limit = 100
            h.onlyLocal = false
            val msgs = td.call(h).messages ?: emptyArray()
            if (msgs.isEmpty()) {
                if (++empty >= 2) break
                delay(300)
                continue
            }
            for (m in msgs) textsOf(m, texts)
            seen += msgs.size
            from = msgs[msgs.size - 1].id
        }
        return extractLinks(texts)
    }

    // ------------------------------------------------------------ scan

    /**
     * status per entry: video | no_video | member | private | error
     * ("private" = invite link; it cannot be checked for video before joining)
     */
    suspend fun scan(ref: String, scanLimit: Int, minVideos: Int, onProgress: (Int, Int) -> Unit): List<HubEntry> {
        val hub = resolveHub(ref)
        val links = collectLinks(hub, scanLimit)
        onProgress(0, links.size)
        val out = ArrayList<HubEntry>()
        for ((i, link) in links.withIndex()) {
            val (kind, key) = link
            val label = "t.me/" + (if (kind == "invite") "+" else "") + key
            var title = ""
            var videos = 0
            var status = "error"
            try {
                if (kind == "public") {
                    val chat = td.searchPublicChat(key)
                    val t = chat.type
                    if (t is TdApi.ChatTypeSupergroup) {
                        title = chat.title ?: ""
                        if (td.isMember(t.supergroupId)) {
                            status = "member"
                        } else {
                            val (ok, n) = td.hasVideos(chat, minVideos)
                            videos = n
                            status = if (ok) "video" else "no_video"
                        }
                    } else {
                        onProgress(i + 1, links.size)
                        continue // a user or bot, not a channel
                    }
                } else {
                    val info = td.checkInvite("https://t.me/+$key")
                    title = info.title ?: ""
                    status = if (info.chatId != 0L) "member" else "private"
                }
            } catch (e: TdError) {
                if (e.code == 429) delay(retryAfterMs(e))
            }
            out.add(HubEntry(kind, key, label, title, videos, status))
            onProgress(i + 1, links.size)
        }
        return out
    }

    private fun retryAfterMs(e: TdError): Long {
        val n = Regex("(\\d+)").find(e.message ?: "")?.groupValues?.get(1)?.toLongOrNull() ?: 30L
        return minOf(n + 2, 120L) * 1000L
    }

    // ------------------------------------------------------------ join (paced)

    suspend fun join(
        entries: List<HubEntry>,
        maxJoins: Int,
        onProgress: (Int, Int) -> Unit,
    ): List<JoinResult> {
        val list = entries.take(maxJoins)
        val rnd = Random()
        val results = ArrayList<JoinResult>()
        onProgress(0, list.size)
        for ((i, e) in list.withIndex()) {
            try {
                if (e.kind == "public") {
                    val chat = td.searchPublicChat(e.key)
                    td.joinPublic(chat.id)
                } else {
                    td.joinInvite("https://t.me/+${e.key}")
                }
                results.add(JoinResult(e.label, true, ""))
            } catch (err: TdError) {
                if (err.code == 429) {
                    results.add(JoinResult(e.label, false, "Telegram asked to slow down (${err.message}). Stopped; try again later."))
                    onProgress(i + 1, list.size)
                    break
                }
                results.add(JoinResult(e.label, false, err.message ?: "error ${err.code}"))
            }
            onProgress(i + 1, list.size)
            if (i + 1 < list.size) delay(25_000L + rnd.nextInt(25_000)) // 25-50 s between joins
        }
        return results
    }
}

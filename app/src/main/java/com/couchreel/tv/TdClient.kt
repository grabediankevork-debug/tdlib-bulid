package com.couchreel.tv

import android.content.Context
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * ALL direct use of TDLib (Telegram's client library) lives in this file and TdDataSource.kt.
 * If your TDLib build has slightly different class or field names, the compile errors will be here.
 *
 * Written against the TDLib 1.8.x Java API (org.drinkless.tdlib.TdApi) built from Telegram's own
 * example/android scripts. Requests are built with no-argument constructors + public fields, which is
 * the form that changes least between TDLib versions.
 */

class TdError(val code: Int, message: String) : Exception(message)

class TdClient(private val ctx: Context, private val apiId: Int, private val apiHash: String) {

    sealed interface Auth {
        data object Starting : Auth
        data object NeedPhone : Auth
        data class QrCode(val link: String) : Auth
        data class NeedCode(val info: String) : Auth
        data class NeedPassword(val hint: String) : Auth
        data object Ready : Auth
        data object Closed : Auth
        data class Failed(val message: String) : Auth
    }

    val auth = MutableStateFlow<Auth>(Auth.Starting)

    private val files = ConcurrentHashMap<Int, TdApi.File>()
    @Volatile private var client: Client? = null

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (client != null) return
        try {
            Client.execute(TdApi.SetLogVerbosityLevel(0))
            client = Client.create(Client.ResultHandler { obj -> onUpdate(obj) }, null, null)
        } catch (e: Throwable) {
            auth.value = Auth.Failed("TDLib could not start: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun onUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> onAuthState(obj.authorizationState)
            is TdApi.UpdateFile -> files[obj.file.id] = obj.file
            else -> {}
        }
    }

    private fun onAuthState(st: TdApi.AuthorizationState) {
        when (st) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val dbDir = File(ctx.filesDir, "td").apply { mkdirs() }
                val filesDir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "td_files").apply { mkdirs() }
                val p = TdApi.SetTdlibParameters()
                p.useTestDc = false
                p.databaseDirectory = dbDir.absolutePath
                p.filesDirectory = filesDir.absolutePath
                p.databaseEncryptionKey = ByteArray(0)
                p.useFileDatabase = true
                p.useChatInfoDatabase = true
                p.useMessageDatabase = true
                p.useSecretChats = false
                p.apiId = apiId
                p.apiHash = apiHash
                p.systemLanguageCode = "en"
                p.deviceModel = Build.MODEL ?: "Android TV"
                p.systemVersion = Build.VERSION.RELEASE ?: ""
                p.applicationVersion = "0.2"
                fire(p)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> auth.value = Auth.NeedPhone
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> auth.value = Auth.QrCode(st.link)
            is TdApi.AuthorizationStateWaitCode -> auth.value = Auth.NeedCode("Enter the code Telegram sent you")
            is TdApi.AuthorizationStateWaitPassword -> auth.value = Auth.NeedPassword(st.passwordHint ?: "")
            is TdApi.AuthorizationStateReady -> auth.value = Auth.Ready
            is TdApi.AuthorizationStateClosed -> auth.value = Auth.Closed
            is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateClosing -> {}
            else -> auth.value = Auth.Failed("Unsupported login step: ${st.javaClass.simpleName}. Log in with the Telegram app on your phone first, then retry.")
        }
    }

    // ------------------------------------------------------------------ request plumbing

    /** fire-and-forget */
    fun fire(f: TdApi.Function<*>) {
        client?.send(f, null)
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> call(f: TdApi.Function<T>): T = suspendCancellableCoroutine { cont ->
        val c = client
        if (c == null) {
            cont.resumeWithException(IllegalStateException("TDLib is not running"))
        } else {
            c.send(f, Client.ResultHandler { res ->
                if (res is TdApi.Error) cont.resumeWithException(TdError(res.code, res.message ?: "error"))
                else cont.resume(res as T)
            })
        }
    }

    fun <T : TdApi.Object> callBlocking(f: TdApi.Function<T>): T = runBlocking { call(f) }

    // ------------------------------------------------------------------ login

    suspend fun submitPhone(phone: String) {
        val f = TdApi.SetAuthenticationPhoneNumber()
        f.phoneNumber = phone
        call(f)
    }

    suspend fun requestQr() {
        val f = TdApi.RequestQrCodeAuthentication()
        f.otherUserIds = LongArray(0)
        call(f)
    }

    suspend fun submitCode(code: String) {
        val f = TdApi.CheckAuthenticationCode()
        f.code = code
        call(f)
    }

    suspend fun submitPassword(password: String) {
        val f = TdApi.CheckAuthenticationPassword()
        f.password = password
        call(f)
    }

    suspend fun logOut() {
        call(TdApi.LogOut())
    }

    // ------------------------------------------------------------------ chats and messages

    private suspend fun loadAllChats() {
        for (i in 0 until 30) {
            val l = TdApi.LoadChats()
            l.chatList = TdApi.ChatListMain()
            l.limit = 200
            try {
                call(l)
            } catch (e: TdError) {
                if (e.code == 404) break else throw e // 404 = everything is loaded
            }
        }
    }

    /** Channels and supergroups you are a member of. */
    suspend fun videoChats(): List<TdApi.Chat> {
        loadAllChats()
        val g = TdApi.GetChats()
        g.chatList = TdApi.ChatListMain()
        g.limit = 2000
        val ids = call(g).chatIds
        val out = ArrayList<TdApi.Chat>()
        for (id in ids) {
            val gc = TdApi.GetChat()
            gc.chatId = id
            val chat = call(gc)
            if (chat.type is TdApi.ChatTypeSupergroup) out.add(chat)
        }
        return out
    }

    suspend fun getChat(chatId: Long): TdApi.Chat {
        val gc = TdApi.GetChat()
        gc.chatId = chatId
        return call(gc)
    }

    suspend fun searchPublicChat(username: String): TdApi.Chat {
        val s = TdApi.SearchPublicChat()
        s.username = username
        return call(s)
    }

    private fun toItem(chat: TdApi.Chat, m: TdApi.Message, minSize: Long): CatalogItem? {
        val c = m.content
        val file: TdApi.File
        val name: String
        val mime: String
        val dur: Int
        val caption: String
        when (c) {
            is TdApi.MessageVideo -> {
                file = c.video.video
                name = c.video.fileName ?: ""
                mime = c.video.mimeType ?: ""
                dur = c.video.duration
                caption = c.caption?.text ?: ""
            }
            is TdApi.MessageDocument -> {
                file = c.document.document
                name = c.document.fileName ?: ""
                mime = c.document.mimeType ?: ""
                dur = 0
                caption = c.caption?.text ?: ""
                val dot = name.lastIndexOf('.')
                val ext = if (dot >= 0) name.substring(dot).lowercase() else ""
                if (!mime.startsWith("video/") && ext !in VIDEO_EXT) return null
            }
            else -> return null
        }
        val size = if (file.size > 0L) file.size else file.expectedSize
        if (size < minSize) return null
        return CatalogItem(
            key = "${chat.id}_${m.id}",
            chatId = chat.id,
            chatTitle = chat.title ?: "",
            msgId = m.id,
            fileName = name,
            size = size,
            mime = mime,
            durationSec = dur,
            caption = caption.take(300),
            dateSec = m.date.toLong(),
        )
    }

    /** Walks a chat's history (newest first) and returns its video files. */
    suspend fun scanChat(chat: TdApi.Chat, perChatLimit: Int, minSize: Long): List<CatalogItem> {
        val out = ArrayList<CatalogItem>()
        var from = 0L
        var seen = 0
        var emptyStreak = 0
        while (seen < perChatLimit) {
            val h = TdApi.GetChatHistory()
            h.chatId = chat.id
            h.fromMessageId = from
            h.offset = 0
            h.limit = 100
            h.onlyLocal = false
            val msgs = call(h).messages ?: emptyArray()
            if (msgs.isEmpty()) {
                emptyStreak++
                if (emptyStreak >= 2) break
                delay(300)
                continue
            }
            emptyStreak = 0
            for (m in msgs) toItem(chat, m, minSize)?.let { out.add(it) }
            seen += msgs.size
            from = msgs[msgs.size - 1].id
        }
        return out
    }

    /** True when at least [min] videos are visible in the first few hundred messages. */
    suspend fun hasVideos(chat: TdApi.Chat, min: Int): Pair<Boolean, Int> {
        var from = 0L
        var seen = 0
        var count = 0
        var empty = 0
        while (seen < 300 && count < min) {
            val h = TdApi.GetChatHistory()
            h.chatId = chat.id
            h.fromMessageId = from
            h.offset = 0
            h.limit = 100
            h.onlyLocal = false
            val msgs = call(h).messages ?: emptyArray()
            if (msgs.isEmpty()) {
                if (++empty >= 2) break
                delay(300)
                continue
            }
            for (m in msgs) if (toItem(chat, m, 0L) != null) count++
            seen += msgs.size
            from = msgs[msgs.size - 1].id
        }
        return Pair(count >= min, count)
    }

    suspend fun getMessage(chatId: Long, messageId: Long): TdApi.Message {
        val g = TdApi.GetMessage()
        g.chatId = chatId
        g.messageId = messageId
        return call(g)
    }

    /** The playable file of a video/document message, or null. */
    fun fileOf(m: TdApi.Message): TdApi.File? = when (val c = m.content) {
        is TdApi.MessageVideo -> c.video.video
        is TdApi.MessageDocument -> c.document.document
        else -> null
    }

    // ------------------------------------------------------------------ files (streaming)

    fun cachedFile(fileId: Int): TdApi.File? = files[fileId]

    suspend fun getFile(fileId: Int): TdApi.File {
        val g = TdApi.GetFile()
        g.fileId = fileId
        val f = call(g)
        files[fileId] = f
        return f
    }

    /** Start (or restart) downloading [fileId] from byte [offset]. Does not wait. */
    fun download(fileId: Int, offset: Long) {
        val d = TdApi.DownloadFile()
        d.fileId = fileId
        d.priority = 32
        d.offset = offset
        d.limit = 0L
        d.synchronous = false
        fire(d)
    }

    fun cancelDownload(fileId: Int) {
        val c = TdApi.CancelDownloadFile()
        c.fileId = fileId
        c.onlyIfPending = false
        fire(c)
    }

    fun deleteFile(fileId: Int) {
        val d = TdApi.DeleteFile()
        d.fileId = fileId
        fire(d)
    }

    /** Remove every cached download. */
    suspend fun clearCache() {
        val o = TdApi.OptimizeStorage()
        o.size = 0L
        o.ttl = 0
        o.count = 0
        o.immunityDelay = 0
        o.fileTypes = emptyArray()
        o.chatIds = LongArray(0)
        o.excludeChatIds = LongArray(0)
        o.returnDeletedFileStatistics = false
        o.chatLimit = 0
        call(o)
    }

    // ------------------------------------------------------------------ joining

    suspend fun joinPublic(chatId: Long) {
        val j = TdApi.JoinChat()
        j.chatId = chatId
        call(j)
    }

    suspend fun joinInvite(link: String) {
        val j = TdApi.JoinChatByInviteLink()
        j.inviteLink = link
        call(j)
    }

    suspend fun checkInvite(link: String): TdApi.ChatInviteLinkInfo {
        val c = TdApi.CheckChatInviteLink()
        c.inviteLink = link
        return call(c)
    }

    suspend fun isMember(supergroupId: Long): Boolean {
        val g = TdApi.GetSupergroup()
        g.supergroupId = supergroupId
        return when (val st = call(g).status) {
            is TdApi.ChatMemberStatusMember, is TdApi.ChatMemberStatusAdministrator, is TdApi.ChatMemberStatusCreator -> true
            is TdApi.ChatMemberStatusRestricted -> st.isMember
            else -> false
        }
    }
}

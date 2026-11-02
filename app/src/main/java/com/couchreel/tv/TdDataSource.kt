package com.couchreel.tv

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Lets ExoPlayer play a Telegram file straight from TDLib, with seeking, without waiting for a full download.
 *
 * URI form:  td://file/<fileId>
 *
 * open(position): asks TDLib to download from that byte offset.
 * read(): returns bytes once TDLib reports them as downloaded (File.local.downloadOffset /
 *         downloadedPrefixSize), reading them from TDLib's own cache file.
 */
@OptIn(UnstableApi::class)
class TdDataSource(private val td: TdClient) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var fileId = 0
    private var raf: RandomAccessFile? = null
    private var rafPath = ""
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false
    private var lastIssued = 0L

    @OptIn(UnstableApi::class)
    class Factory(private val td: TdClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = TdDataSource(td)
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        fileId = dataSpec.uri.lastPathSegment?.toIntOrNull() ?: throw IOException("Bad file address: ${dataSpec.uri}")
        val f = td.cachedFile(fileId) ?: td.callBlocking(getFileRequest(fileId))
        val size = if (f.size > 0L) f.size else f.expectedSize
        position = dataSpec.position
        if (size > 0 && position >= size) throw IOException("Seek past end of file")
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) size - position else dataSpec.length
        td.download(fileId, position)
        lastIssued = System.currentTimeMillis()
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    private fun getFileRequest(id: Int) = org.drinkless.tdlib.TdApi.GetFile().also { it.fileId = id }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val started = System.currentTimeMillis()
        var lastProgressAt = started
        while (true) {
            val f = td.cachedFile(fileId) ?: td.callBlocking(getFileRequest(fileId))
            val lf = f.local
            val start = lf.downloadOffset
            val end = start + lf.downloadedPrefixSize
            val covered = lf.path.isNotEmpty() && (lf.isDownloadingCompleted || (position >= start && position < end))
            if (covered) {
                val availableTo = if (lf.isDownloadingCompleted) Long.MAX_VALUE else end
                val n = minOf(length.toLong(), bytesRemaining, availableTo - position).toInt()
                if (n > 0) {
                    val r = ensureOpen(lf.path)
                    r.seek(position)
                    val got = r.read(buffer, offset, n)
                    if (got > 0) {
                        position += got
                        bytesRemaining -= got
                        bytesTransferred(got)
                        return got
                    }
                }
            } else {
                // not downloaded here yet: make sure a download is heading to this position
                val willReach = lf.isDownloadingActive && start <= position && position <= end
                val now = System.currentTimeMillis()
                if (!willReach && now - lastIssued > 1500) {
                    td.download(fileId, position)
                    lastIssued = now
                }
            }
            if (System.currentTimeMillis() - lastProgressAt > 45_000) {
                throw IOException("Telegram download stalled (no data for 45 s)")
            }
            try {
                Thread.sleep(80)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            }
        }
    }

    private fun ensureOpen(path: String): RandomAccessFile {
        val current = raf
        if (current != null && rafPath == path) return current
        current?.close()
        val n = RandomAccessFile(path, "r")
        raf = n
        rafPath = path
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            raf?.close()
        } catch (e: IOException) {
            // ignore
        }
        raf = null
        rafPath = ""
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

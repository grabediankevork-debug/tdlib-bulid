package com.couchreel.tv

import android.app.Application
import java.io.File

class CouchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}

/** Process-wide holders, shared by MainActivity and PlayerActivity (same process). */
object Graph {
    private lateinit var app: Application

    @Volatile var td: TdClient? = null
        private set

    @Volatile var local: LocalBackend? = null
        private set

    fun init(a: Application) {
        app = a
    }

    @Synchronized
    fun startTd(apiId: Int, apiHash: String): TdClient {
        td?.let { return it }
        val c = TdClient(app, apiId, apiHash)
        td = c
        c.start()
        return c
    }

    @Synchronized
    fun localBackend(): LocalBackend {
        local?.let { return it }
        val t = td ?: throw IllegalStateException("Telegram is not started")
        val b = LocalBackend(t, FileState(File(app.filesDir, "state.json")), CatalogStore(File(app.filesDir, "catalog.json")))
        local = b
        return b
    }

    @Synchronized
    fun reset() {
        td = null
        local = null
    }
}

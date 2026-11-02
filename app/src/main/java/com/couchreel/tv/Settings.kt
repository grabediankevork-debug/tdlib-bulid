package com.couchreel.tv

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("couchreel", Context.MODE_PRIVATE)

    fun load(): ServerConfig? {
        val base = prefs.getString("base", null) ?: return null
        return ServerConfig(base, prefs.getString("token", "") ?: "")
    }

    /** null = not chosen yet, "server" = tgbridge server, "direct" = Telegram directly (no server) */
    var mode: String?
        get() = prefs.getString("mode", null)
        set(v) {
            prefs.edit().putString("mode", v).apply()
        }

    fun loadCreds(): Pair<Int, String>? {
        val id = prefs.getInt("api_id", 0)
        val hash = prefs.getString("api_hash", "") ?: ""
        return if (id != 0 && hash.isNotBlank()) Pair(id, hash) else null
    }

    fun saveCreds(apiId: Int, apiHash: String) {
        prefs.edit().putInt("api_id", apiId).putString("api_hash", apiHash).apply()
    }

    fun save(cfg: ServerConfig) {
        prefs.edit().putString("base", cfg.base).putString("token", cfg.token).apply()
    }
}

/** "192.168.1.5:8765/" -> "http://192.168.1.5:8765" */
fun normalizeBase(input: String): String {
    var t = input.trim().trimEnd('/')
    if (t.isNotEmpty() && !t.startsWith("http://") && !t.startsWith("https://")) {
        t = "http://$t"
    }
    return t
}

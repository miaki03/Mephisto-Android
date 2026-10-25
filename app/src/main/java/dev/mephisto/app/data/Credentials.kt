package dev.mephisto.app.data

import android.content.Context
import dev.mephisto.app.core.MephistoApi
import dev.mephisto.app.core.UrlConnectionTransport

/**
 * 服务端地址与令牌的本地存储。
 *
 * 放 SharedPreferences 而不是 DataStore：这里只有两个字符串，DataStore 会引入
 * 协程与 Flows 的复杂度，对"读两个值"没有收益。真要加密再换 EncryptedSharedPreferences。
 */
data class Credentials(val baseUrl: String, val token: String) {
    val usable: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()

    fun api(): MephistoApi = MephistoApi(UrlConnectionTransport(), baseUrl, token)

    companion object {
        private const val FILE = "mephisto"
        private const val KEY_BASE = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_LEVEL = "keep_alive_level"
        const val DEFAULT_BASE = "http://127.0.0.1:8787"

        fun load(context: Context): Credentials {
            val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return Credentials(
                baseUrl = prefs.getString(KEY_BASE, DEFAULT_BASE) ?: DEFAULT_BASE,
                token = prefs.getString(KEY_TOKEN, "") ?: "",
            )
        }

        fun save(context: Context, credentials: Credentials) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString(KEY_BASE, credentials.baseUrl)
                .putString(KEY_TOKEN, credentials.token)
                .apply()
        }

        fun level(context: Context): String =
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LEVEL, "persistent") ?: "persistent"

        fun setLevel(context: Context, name: String) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_LEVEL, name).apply()
        }
    }
}

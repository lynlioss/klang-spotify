package io.github.lynlioss.klang.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object SourceHttp {
    val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).build()
    suspend fun text(url: String, limit: Int = 16 * 1024 * 1024): String = runInterruptible(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Service request failed (${response.code})" }
            val bytes = requireNotNull(response.body).byteStream().use { it.readNBytes(limit + 1) }
            require(bytes.size <= limit) { "Service returned too much data" }; bytes.toString(Charsets.UTF_8)
        }
    }
    suspend fun json(url: String) = JSONObject(text(url))
}

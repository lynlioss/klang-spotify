package io.github.lynlioss.klang.spotify

import android.content.Context
import app.lynli.klang.importing.recordingScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Uses only the connected user's Qobuz account and its matching app credentials. */
internal class QobuzSource(private val context: Context) {
    private val vault get() = AccountVault.get(context)
    suspend fun login(email: String, password: String, appId: String, secret: String) {
        require(appId.matches(Regex("[A-Za-z0-9]{1,100}"))) { "Enter a valid Qobuz app ID." }
        require(secret.isNotBlank() && secret.length <= 256) { "Enter the matching Qobuz app secret." }
        val body = FormBody.Builder().add("email", email).add("password", password).add("app_id", appId).build()
        val result = request(Request.Builder().url("$API/user/login").header("X-App-Id", appId).post(body).build())
        vault.putAll(mapOf("qobuz_token" to result.getString("user_auth_token"), "qobuz_app_id" to appId,
            "qobuz_app_secret" to secret, "qobuz_profile" to (result.optJSONObject("user")?.optString("display_name") ?: email),
            "qobuz_generation" to UUID.randomUUID().toString()))
    }
    private suspend fun api(path: String, params: Map<String, String>, account: Map<String, String?>): JSONObject {
        val appId = account["qobuz_app_id"] ?: error("Connect Qobuz in this plugin's settings.")
        val token = account["qobuz_token"] ?: error("Connect Qobuz in this plugin's settings.")
        val url = "$API/$path".toHttpUrl().newBuilder().addQueryParameter("app_id", appId)
        params.forEach { (key, value) -> url.addQueryParameter(key, value) }
        return request(Request.Builder().url(url.build()).header("X-App-Id", appId).header("X-User-Auth-Token", token).build())
    }
    private suspend fun request(request: Request): JSONObject = runInterruptible(Dispatchers.IO) {
        SourceHttp.client.newCall(request).execute().use { response ->
            if (response.code == 401) error("Qobuz login expired. Reconnect in the plugin's settings.")
            require(response.isSuccessful) { "Qobuz request failed (${response.code})" }
            val bytes = requireNotNull(response.body).byteStream().use { it.readNBytes(8 * 1024 * 1024 + 1) }
            require(bytes.size <= 8 * 1024 * 1024)
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
    }
    suspend fun resolve(item: JSONObject): JSONObject? {
        val account = vault.snapshot(listOf("qobuz_token", "qobuz_app_id", "qobuz_app_secret"))
        if (account["qobuz_token"] == null) return null
        val song = CatalogCodec.importSong(item)
        val terms = listOfNotNull(item.optString("isrc").takeIf { it.isNotBlank() && it != "null" }, "${song.artist} ${song.title}").distinct()
        for (term in terms) {
            val search = api("catalog/search", mapOf("query" to term, "type" to "tracks", "limit" to "50"), account)
            val candidates = search.optJSONObject("tracks")?.optJSONArray("items")?.objects().orEmpty()
            val ranked = candidates.map { candidate ->
                val identity = JSONObject().put("title", candidate.optString("title"))
                    .put("artist", candidate.optJSONObject("performer")?.optString("name").orEmpty())
                    .put("duration", candidate.optDouble("duration", 0.0)).put("catalog", true)
                    .put("explicit", candidate.optBoolean("parental_warning"))
                val isrc = item.optString("isrc").takeIf { it.isNotBlank() && it != "null" }
                val sameIsrc = isrc != null && isrc.equals(candidate.optString("isrc"), true)
                candidate to if (!candidate.optBoolean("streamable")) 0.0 else if (sameIsrc) 1.0 else recordingScore(song, identity)
            }.filter { it.second >= .8 }.sortedByDescending { it.second }
            for ((track, confidence) in ranked.take(3)) {
                val result = fileUrl(track.getLong("id"), item.optInt("qobuzFormat", 27), account)
                if (!QobuzProtocol.fullRecording(result, song.durationMs)) continue
                val url = result.optString("url").takeIf { it.startsWith("https://") } ?: continue
                val info = JSONObject().put("id", track.getLong("id").toString()).put("title", song.title)
                    .put("webpage_url", "https://www.qobuz.com/track/${track.getLong("id")}")
                    .put("extractor", "generic").put("extractor_key", "Generic")
                    .put("duration", track.optLong("duration")).put("url", url)
                    .put("formats", JSONArray().put(JSONObject().put("format_id", "qobuz").put("url", url)
                        .put("vcodec", "none").put("acodec", "flac").put("ext", "flac").put("protocol", "https")))
                return JSONObject().put("url", url).put("source", "qobuz").put("format", "FLAC")
                    .put("bitDepth", result.optInt("bit_depth")).put("sampleRate", result.optDouble("sampling_rate"))
                    .put("expiresAt", System.currentTimeMillis() + 5 * 60_000).put("confidence", confidence)
                    .put("recordingMetadata", track).put("info", info)
            }
        }
        return null
    }
    private suspend fun fileUrl(trackId: Long, requested: Int, account: Map<String, String?>): JSONObject {
        val format = requested.takeIf { it in listOf(6, 7, 27) } ?: 27
        val secret = account["qobuz_app_secret"] ?: error("Enter the matching Qobuz app secret in settings.")
        val ts = System.currentTimeMillis() / 1000
        // Qobuz's request signature binds format, intent, track, timestamp, and this account's app pair.
        val sig = QobuzProtocol.signature(trackId, format, ts, secret)
        return api("track/getFileUrl", mapOf("track_id" to "$trackId", "format_id" to "$format", "intent" to "stream",
            "request_ts" to "$ts", "request_sig" to sig), account)
    }
    companion object { private const val API = "https://www.qobuz.com/api.json/0.2" }
}

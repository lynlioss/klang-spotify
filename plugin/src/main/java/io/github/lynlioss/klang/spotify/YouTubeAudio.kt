package io.github.lynlioss.klang.spotify

import app.lynli.klang.importing.ImportHttp
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Direct player requests use YumaPlayer's public, unauthenticated client configuration. */
internal object YouTubeAudio {
    private val http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS).callTimeout(2, TimeUnit.SECONDS).build()
    private const val AGENT = "com.google.android.apps.youtube.vr.oculus/1.37 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)"
    suspend fun info(id: String): JSONObject {
        require(id.matches(Regex("[A-Za-z0-9_-]{11}")))
        val body = JSONObject().put("videoId", id).put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "ANDROID_VR").put("clientVersion", "1.37").put("hl", "en").put("gl", "US")
            .put("osName", "Android").put("osVersion", "12").put("deviceMake", "Oculus").put("deviceModel", "Quest 3")
            .put("androidSdkVersion", 32).put("userAgent", AGENT)))
        val response = JSONObject(ImportHttp.postJson("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body.toString(), http,
            headers = mapOf("User-Agent" to AGENT, "X-YouTube-Client-Name" to "28", "X-YouTube-Client-Version" to "1.37",
                "Origin" to "https://www.youtube.com", "Referer" to "https://www.youtube.com/")))
        val info = parse(id, response)
        val formats = info.getJSONArray("formats")
        // A direct URL is useful only if the CDN actually permits this client to open it.
        probe(formats.getJSONObject(formats.length() - 1).getString("url"))
        return info
    }
    internal fun parse(id: String, response: JSONObject): JSONObject {
        require(response.optJSONObject("playabilityStatus")?.optString("status") == "OK")
        val details = response.getJSONObject("videoDetails")
        require(details.getString("videoId") == id && !details.optBoolean("isLiveContent"))
        val duration = details.optString("lengthSeconds").toLongOrNull() ?: 0
        require(duration > 0)
        val streaming = response.getJSONObject("streamingData")
        require(streaming.optJSONArray("licenseInfos").let { it == null || it.length() == 0 })
        val raw = streaming.optJSONArray("adaptiveFormats") ?: JSONArray()
        val formats = (0 until raw.length()).map(raw::getJSONObject).mapNotNull { format ->
            val mime = format.optString("mimeType")
            val url = format.optString("url")
            if (!mime.startsWith("audio/") || format.has("signatureCipher") || format.has("cipher") ||
                format.optBoolean("isDrc") || format.has("drmFamilies") || !isAudioUrl(url)) return@mapNotNull null
            val codec = Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.get(1) ?: return@mapNotNull null
            if (codec != "opus" && !codec.startsWith("mp4a.")) return@mapNotNull null
            val track = format.optJSONObject("audioTrack")
            if (track != null && !track.optBoolean("audioIsDefault", true)) return@mapNotNull null
            JSONObject().put("format_id", format.getInt("itag").toString()).put("url", url).put("vcodec", "none")
                .put("acodec", codec).put("abr", format.optLong("bitrate") / 1000.0)
                .put("filesize", format.optString("contentLength")).put("lastModified", format.optString("lastModified"))
                .put("http_headers", JSONObject().put("User-Agent", AGENT))
        }.sortedBy { it.optDouble("abr") }
        require(formats.isNotEmpty()) { "The direct player did not return accessible audio." }
        return JSONObject().put("id", id).put("title", details.getString("title"))
            .put("uploader", details.optString("author")).put("channel", details.optString("author"))
            .put("duration", duration).put("formats", JSONArray(formats)).put("nativePlayback", true)
            .put("webpage_url", "https://music.youtube.com/watch?v=$id")
            .put("thumbnails", details.optJSONObject("thumbnail")?.optJSONArray("thumbnails"))
    }
    private fun isAudioUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        value.length <= 16_384 && uri.scheme == "https" && uri.userInfo == null &&
            uri.host?.endsWith(".googlevideo.com") == true
    }.getOrDefault(false)
    private suspend fun probe(url: String): Unit = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).header("User-Agent", AGENT).header("Range", "bytes=0-0").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWith(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { check(it.isSuccessful && requireNotNull(it.body).byteStream().read() != -1) } }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }
}

/** Reuse audio bytes after a URL refresh only when the encoded recording/version is identical. */
internal fun recordingCacheKey(id: String, format: JSONObject): String? {
    val query = runCatching { URI(format.getString("url")).rawQuery.orEmpty().split('&')
        .associate { it.substringBefore('=') to it.substringAfter('=', "") } }.getOrDefault(emptyMap())
    val length = (query["clen"] ?: format.optString("filesize")).toLongOrNull()?.takeIf { it > 0 } ?: return null
    val version = (query["lmt"] ?: format.optString("lastModified")).toLongOrNull()?.takeIf { it > 0 } ?: return null
    return "youtube:$id:${format.getString("format_id")}:$length:$version"
}

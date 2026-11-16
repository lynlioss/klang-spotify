package io.github.lynlioss.klang.spotify

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

internal object QobuzProtocol {
    fun signature(trackId: Long, format: Int, timestamp: Long, secret: String): String {
        val data = "trackgetFileUrlformat_id${format}intentstreamtrack_id${trackId}${timestamp}${secret}"
        return MessageDigest.getInstance("MD5").digest(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** A successful HTTP response can still be a 30-second MP3 preview. */
    fun fullRecording(result: JSONObject, expectedDurationMs: Long): Boolean {
        if (result.optInt("format_id") < 6 || result.optBoolean("sample") || result.optBoolean("is_preview")) return false
        if (result.optJSONArray("restrictions")?.objects().orEmpty()
                .any { it.optString("code").equals("UserUnauthenticated", true) }) return false
        val duration = result.optDouble("duration", 0.0)
        if (duration > 0 && expectedDurationMs > 0 && duration * 1000 < expectedDurationMs * .9) return false
        return runCatching { URI(result.getString("url")).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
    }
}

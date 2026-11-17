package io.github.lynlioss.klang.spotify

import org.json.JSONObject

/** Public, non-secret properties for the host's song details sheet. */
internal fun MatchStatus.toJson(item: JSONObject): JSONObject = JSONObject().apply {
    put("state", state)
    put("verified", verified)
    put("retryAt", retryAt)
    item.optString("url").takeIf { it.startsWith("https://") }?.let { put("spotify", it) }
    val recording = candidates.firstOrNull() ?: return@apply
    val id = recording.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return@apply
    val artists = recording.optJSONArray("artists")?.let { list ->
        (0 until list.length()).mapNotNull { index -> list.optJSONObject(index)?.optString("name") }
            .filter { it.isNotBlank() }.joinToString(", ")
    }.orEmpty()
    val artist = recording.clean("artist").ifBlank { artists }
    val title = recording.clean("track").ifBlank { recording.clean("title") }
    put("youtube", "https://music.youtube.com/watch?v=$id")
    put("recording", JSONObject().put("id", id).put("title", title).put("artist", artist)
        .put("album", recording.clean("album"))
        .put("durationMs", (recording.optDouble("duration", 0.0) * 1000).toLong()))
}

private fun JSONObject.clean(name: String) = optString(name).takeIf { it.isNotBlank() && it != "null" }.orEmpty()

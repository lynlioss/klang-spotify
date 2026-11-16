package io.github.lynlioss.klang.spotify

import app.lynli.klang.importing.selectOriginalAudio
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubeAudioTest {
    private val videoId = "abcdefghijk"
    private fun format(itag: Int = 251, url: String = "https://r1.googlevideo.com/videoplayback?expire=1900000000") = JSONObject()
        .put("itag", itag).put("mimeType", "audio/webm; codecs=\"opus\"").put("bitrate", 160000).put("url", url)
    private fun response(vararg formats: JSONObject) = JSONObject()
        .put("playabilityStatus", JSONObject().put("status", "OK"))
        .put("videoDetails", JSONObject().put("videoId", videoId).put("title", "Artist - Song")
            .put("author", "Artist - Topic").put("lengthSeconds", "210"))
        .put("streamingData", JSONObject().put("adaptiveFormats", JSONArray(formats.toList())))

    @Test fun directAudioRetainsRecordingAndCodecMetadata() {
        val info = YouTubeAudio.parse(videoId, response(format()))
        assertEquals(videoId, info.getString("id"))
        assertEquals(210L, info.getLong("duration"))
        assertEquals("251", selectOriginalAudio(info).id)
        assertTrue(info.getBoolean("nativePlayback"))
    }
    @Test fun cipherProtectedDrmAndForeignUrlsFallBackToTheExtractor() {
        val rejected = listOf(format().put("signatureCipher", "s=cipher"), format().put("drmFamilies", JSONArray().put("widevine")),
            format(url = "http://r1.googlevideo.com/file"), format(url = "https://googlevideo.com.evil.test/file"),
            format(url = "https://secret@r1.googlevideo.com/file"), format().put("audioTrack", JSONObject().put("audioIsDefault", false)))
        rejected.forEach { format -> assertThrows(IllegalArgumentException::class.java) { YouTubeAudio.parse(videoId, response(format)) } }
    }
    @Test fun unavailableOrWrongRecordingsCannotBeReturnedAsStreams() {
        assertThrows(IllegalArgumentException::class.java) { YouTubeAudio.parse(videoId,
            response(format()).put("playabilityStatus", JSONObject().put("status", "LOGIN_REQUIRED"))) }
        val wrong = response(format())
        wrong.getJSONObject("videoDetails").put("videoId", "wrongvideo1")
        assertThrows(IllegalArgumentException::class.java) { YouTubeAudio.parse(videoId, wrong) }
        val live = response(format())
        live.getJSONObject("videoDetails").put("isLiveContent", true)
        assertThrows(IllegalArgumentException::class.java) { YouTubeAudio.parse(videoId, live) }
    }
    @Test fun sourceDefaultsAndMigrationRespectConnectedQobuzChoices() {
        assertEquals("youtube", playbackPreference(null, false))
        assertEquals("youtube", playbackPreference(null, true))
        assertEquals("youtube", playbackPreference("auto", false))
        assertEquals("auto", playbackPreference("auto", true))
        assertEquals("qobuz", playbackPreference("qobuz", true))
        assertEquals("youtube", playbackPreference("qobuz", false))
    }
    @Test fun audioByteIdentitySurvivesUrlRefreshButChangesWithEncodingVersion() {
        val first = JSONObject().put("format_id", "251")
            .put("url", "https://r1.googlevideo.com/file?expire=100&clen=4000000&lmt=12345")
        val refreshed = JSONObject(first.toString()).put("url", "https://r2.googlevideo.com/file?expire=200&clen=4000000&lmt=12345")
        assertEquals(recordingCacheKey(videoId, first), recordingCacheKey(videoId, refreshed))
        assertNotEquals(recordingCacheKey(videoId, first), recordingCacheKey(videoId, JSONObject(first.toString()).put("format_id", "140")))
        assertNull(recordingCacheKey(videoId, JSONObject(first.toString()).put("url", "https://r1.googlevideo.com/file?expire=100")))
    }
}

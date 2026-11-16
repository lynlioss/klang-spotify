package io.github.lynlioss.klang.spotify

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QobuzProtocolTest {
    private fun recording() = JSONObject().put("format_id", 27).put("duration", 200).put("url", "https://audio.example.test/file.flac")
    @Test fun rejectsPreviewAndAccountRestrictions() {
        assertTrue(QobuzProtocol.fullRecording(recording(), 200000))
        assertFalse(QobuzProtocol.fullRecording(recording().put("format_id", 5), 200000))
        assertFalse(QobuzProtocol.fullRecording(recording().put("sample", true), 200000))
        assertFalse(QobuzProtocol.fullRecording(recording().put("is_preview", true), 200000))
        assertFalse(QobuzProtocol.fullRecording(recording().put("duration", 30), 200000))
        assertFalse(QobuzProtocol.fullRecording(recording().put("restrictions", JSONArray().put(JSONObject().put("code", "UserUnauthenticated"))), 200000))
        assertFalse(QobuzProtocol.fullRecording(recording().put("url", "http://audio.example.test/file.flac"), 200000))
        assertFalse(QobuzProtocol.fullRecording(recording().put("url", "https://token@audio.example.test/file.flac"), 200000))
        assertFalse(QobuzProtocol.fullRecording(recording().apply { remove("format_id") }, 200000))
    }
    @Test fun signatureBindsTheExactTimestampAndOwnAppSecret() {
        // Synthetic credentials only. Expected vector generated independently with hashlib.
        assertEquals("5440e9a0a2afed5e4565a7c455fa1fa3", QobuzProtocol.signature(12345, 27, 1800000000, "synthetic-test-secret"))
        assertNotEquals(QobuzProtocol.signature(12345, 27, 1800000000, "synthetic-test-secret"),
            QobuzProtocol.signature(12345, 27, 1800000001, "synthetic-test-secret"))
        assertNotEquals(QobuzProtocol.signature(12345, 27, 1800000000, "synthetic-test-secret"),
            QobuzProtocol.signature(12345, 27, 1800000000, "other-synthetic-secret"))
    }
}

package io.github.lynlioss.klang.spotify

import app.lynli.klang.importing.ImportSong
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingMatchesTest {
    @get:Rule val directory = TemporaryFolder()
    private val song = ImportSong("spotify:track:example", "https://open.spotify.com/track/example",
        "Song", "Artist", "Album", 210_000, spotify = true, explicit = true)
    private fun recording(id: String = "abcdefghijk") = JSONObject().put("id", id).put("title", "Song")
        .put("artist", "Artist").put("duration", 210).put("url", "https://audio.test/file?secret=temporary")
        .put("formats", JSONArray().put(JSONObject().put("url", "https://audio.test/file")))
        .put("http_headers", JSONObject().put("Authorization", "private"))

    @Test fun restartRetainsValidatedIdentityWithoutPersistingStreamUrlsOrHeaders() {
        val file = directory.newFile("matches.json")
        RecordingMatches(file).put(song, "account-a", recording())
        val saved = requireNotNull(RecordingMatches(file).get(song, "account-a"))
        assertEquals("abcdefghijk", saved.getString("id"))
        assertFalse(file.readText().contains("https://"))
        assertFalse(file.readText().contains("Authorization"))
        assertFalse(saved.has("formats"))
        saved.put("id", "changed")
        assertEquals("abcdefghijk", RecordingMatches(file).get(song, "account-a")!!.getString("id"))
    }

    @Test fun accountMetadataAndRecordingVersionChangesCannotReuseTheWrongMatch() {
        val cache = RecordingMatches(directory.newFile("matches.json"))
        cache.put(song, "account-a", recording())
        assertNull(cache.get(song, "account-b"))
        assertNull(cache.get(song.copy(title = "Song (Live)"), "account-a"))
        assertNull(cache.get(song.copy(explicit = false), "account-a"))
        cache.remove(song, "account-a")
        assertNull(cache.get(song, "account-a"))
    }

    @Test fun boundedCacheEvictsOldSongsAndExpiresOldMatches() {
        var now = 1_000_000L
        val file = directory.newFile("matches.json")
        val cache = RecordingMatches(file, capacity = 2, now = { now })
        cache.put(song, "a", recording())
        cache.put(song.copy(id = "b"), "a", recording())
        cache.get(song, "a")
        cache.put(song.copy(id = "c"), "a", recording())
        assertNull(cache.get(song.copy(id = "b"), "a"))
        assertNotNull(RecordingMatches(file, now = { now }).get(song, "a"))
        now += 31L * 24 * 60 * 60 * 1000
        assertNull(cache.get(song, "a"))
    }

    @Test fun malformedOrUnwritableCacheNeverDisablesMatching() {
        val file = directory.newFile("matches.json").apply { writeText("{truncated") }
        assertNull(RecordingMatches(file).get(song, "a"))
        val cache = RecordingMatches(directory.root)
        cache.put(song, "a", recording())
        assertNotNull(cache.get(song, "a"))
    }
}

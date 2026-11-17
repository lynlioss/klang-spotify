package io.github.lynlioss.klang.spotify

import android.content.Context
import app.lynli.klang.importing.ImportSong
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class RecordingDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: RecordingDatabase
    private val song = ImportSong("spotify:track:example", "https://open.spotify.com/track/example", "Song", "Artist", "Album", 210_000, spotify = true, explicit = true)
    private fun item(id: String = song.sourceId) = JSONObject().put("id", id).put("kind", "track").put("title", song.title)
        .put("artist", song.artist).put("album", song.album).put("durationMs", song.durationMs).put("explicit", true).put("url", song.url)
    private fun recording() = JSONObject().put("id", "abcdefghijk").put("title", "Song").put("artist", "Artist").put("duration", 210)
        .put("formats", JSONArray().put(JSONObject().put("url", "https://audio.test/file?secret=private")))
        .put("http_headers", JSONObject().put("Authorization", "private"))

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("recording-links.db")
        File(context.filesDir, "playback").deleteRecursively()
        database = RecordingDatabase(context)
    }
    @After fun teardown() { database.close() }

    @Test fun persistsRecordingCreditsAndKeepsAccountsAndVersionsSeparate() {
        database.saveVerified(item(), "a", recording(), database.generation)
        database.close(); database = RecordingDatabase(context)
        val saved = database.candidates(song, "a").single()
        assertEquals("abcdefghijk", saved.getString("id"))
        assertFalse(saved.toString().contains("https://")); assertFalse(saved.has("formats")); assertFalse(saved.has("http_headers"))
        assertTrue(database.candidates(song, "b").isEmpty())
        assertTrue(database.candidates(song.copy(title = "Song (Live)"), "a").isEmpty())
        assertEquals(1, database.stats("a").verified)
    }
    @Test fun priorityAndBackoffLetTheQueuePassLikedSongsWithoutLosingPendingWork() {
        database.enqueue(listOf(item("liked")), "a", 0)
        database.enqueue(listOf(item("queued")), "a", 2)
        val task = requireNotNull(database.next("a"))
        assertEquals("queued", task.item.getString("id"))
        database.failed(task, database.generation)
        assertEquals("liked", database.next("a")!!.item.getString("id"))
        assertEquals(2, database.stats("a").pending)
    }
    @Test fun clearingStopsInflightWritesAndKeepsTheDatabaseUsable() {
        database.enqueue(listOf(item()), "a")
        val task = requireNotNull(database.next("a"))
        val generation = database.generation
        database.clear()
        database.save(task, listOf(recording()), false, generation)
        database.saveVerified(item(), "a", recording(), generation)
        assertFalse(database.enqueue(listOf(item()), "a", expectedGeneration = generation))
        assertEquals(0, database.stats("a").songs)
        database.enqueue(listOf(item()), "a")
        assertEquals(1, database.stats("a").pending)
    }
    @Test fun migratesThePreviousJsonCacheOnce() {
        val legacy = File(context.filesDir, "playback/recording-matches.json").apply { parentFile!!.mkdirs() }
        RecordingMatches(legacy).put(song, "a", recording())
        assertEquals("abcdefghijk", database.candidates(song, "a").single().getString("id"))
        assertFalse(legacy.exists())
        database.clear()
        assertTrue(database.candidates(song, "a").isEmpty())
    }
    @Test fun catalogCandidatesCannotOverwriteAForegroundVerifiedRecording() {
        database.enqueue(listOf(item()), "a")
        val task = requireNotNull(database.next("a"))
        database.saveVerified(item(), "a", recording(), database.generation)
        database.save(task, listOf(recording().put("id", "zyxwvutsrqp")), false)
        assertEquals("abcdefghijk", database.candidates(song, "a").single().getString("id"))
        assertEquals(1, database.stats("a").verified)
    }
    @Test fun accommodatesLikedLibrariesLargerThanTheOldCache() {
        repeat(30) { page -> database.enqueue((0 until 100).map { item("liked-${page * 100 + it}") }, "a") }
        assertEquals(3_000, database.stats("a").songs)
        assertEquals(3_000, database.stats("a").pending)
    }
}

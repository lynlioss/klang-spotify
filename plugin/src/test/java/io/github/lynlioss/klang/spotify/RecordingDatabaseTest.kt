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
import kotlinx.coroutines.*

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
    @Test fun concurrentWorkersClaimDifferentSongsAndReleasedWorkCanResume() = runBlocking {
        database.enqueue((0 until 8).map { item("song-$it") }, "a")
        val generation = database.generation
        val tasks = (0 until 4).map { async(Dispatchers.Default) { database.claimMatches("a", 1, generation).single() } }.awaitAll()
        assertEquals(4, tasks.map { it.key }.distinct().size)
        val rest = database.claimMatches("a", 4, generation)
        assertTrue(rest.none { next -> tasks.any { it.key == next.key } })
        assertTrue(database.claimMatches("a", 1, generation).isEmpty())
        database.release(tasks.first(), generation)
        assertEquals(tasks.first().key, database.claimMatches("a", 1, generation).single().key)
        assertTrue(database.claimMatches("b", 1, generation).isEmpty())
    }
    @Test fun libraryCursorsAndDiscoveredSavedCollectionsSurviveRestarts() {
        val generation = database.generation
        database.seedLibrary("a", generation)
        val liked = database.claimLibrary("a", 1, generation).single()
        assertEquals("liked", liked.kind)
        database.saveLibraryPage(liked, listOf(item("first")), 100, generation)
        database.close(); database = RecordingDatabase(context)
        val resumed = database.claimLibrary("a", 1, database.generation).single()
        assertEquals(100, resumed.offset)
        database.saveLibraryPage(resumed, listOf(item("second")), null, database.generation)
        val playlists = database.claimLibrary("a", 1, database.generation).single()
        assertEquals("playlists", playlists.kind)
        val saved = JSONObject().put("id", "spotify:playlist:saved").put("kind", "playlist")
        database.saveLibraryPage(playlists, listOf(saved, saved), null, database.generation)
        val albums = database.claimLibrary("a", 1, database.generation).single()
        database.saveLibraryPage(albums, listOf(JSONObject().put("id", "spotify:album:saved").put("kind", "album")), null, database.generation)
        val collections = database.claimLibrary("a", 3, database.generation)
        assertEquals(setOf("playlist", "album"), collections.map { it.kind }.toSet())
        assertEquals(2, collections.size)
        collections.forEach { database.saveLibraryPage(it, listOf(item("first")), null, database.generation) }
        assertFalse(database.libraryPending("a"))
        assertEquals(2, database.stats("a").songs)
        database.seedLibrary("a", database.generation, force = true)
        assertEquals(0, database.claimLibrary("a", 1, database.generation).single().offset)
    }
    @Test fun failedOrClearedLibraryPagesCannotLoseWorkOrRepopulateTheCache() {
        val generation = database.generation
        database.seedLibrary("a", generation)
        val task = database.claimLibrary("a", 1, generation).single()
        database.failed(task, generation)
        assertTrue(database.libraryPending("a"))
        assertEquals("playlists", database.claimLibrary("a", 1, generation).single().kind)
        database.clear()
        database.close(); database = RecordingDatabase(context)
        database.saveLibraryPage(task, listOf(item()), 100, generation)
        assertFalse(database.libraryPending("a"))
        assertEquals(0, database.stats("a").songs)
    }
    @Test fun finishedPageCannotReleaseTheNextWorkersCursorLease() {
        database.seedLibrary("a", database.generation)
        val first = database.claimLibrary("a", 1, database.generation).single()
        database.saveLibraryPage(first, listOf(item()), 100, database.generation)
        val next = database.claimLibrary("a", 1, database.generation).single()
        assertEquals(100, next.offset)
        database.release(first, database.generation)
        assertEquals("playlists", database.claimLibrary("a", 1, database.generation).single().kind)
    }
    @Test fun upgradingVersionOneRetainsExistingLinksAndAddsResumableLibraryDiscovery() {
        context.openOrCreateDatabase("recording-links.db", Context.MODE_PRIVATE, null).use { old ->
            old.execSQL(RecordingDatabase.SCHEMA.replace(", leaseUntil INTEGER NOT NULL DEFAULT 0", ""))
            old.execSQL("CREATE TABLE migrations (name TEXT PRIMARY KEY)")
            old.execSQL("INSERT INTO migrations(name) VALUES('json')")
            old.execSQL("INSERT INTO songs(key,account,item,candidates,verified,matchedAt) VALUES(?,?,?,?,1,?)",
                arrayOf<Any>(recordingMatchKey(song, "a"), "a", item().toString(), JSONArray().put(recordingCredits(recording())).toString(), System.currentTimeMillis()))
            old.version = 1
        }
        assertEquals(1, database.stats("a").verified)
        assertEquals("abcdefghijk", database.candidates(song, "a").single().getString("id"))
        database.seedLibrary("a", database.generation)
        assertEquals(3, database.claimLibrary("a", 3, database.generation).size)
    }
    @Test fun fullLibrariesAreNotTruncatedAtTwentyThousandSongs() {
        database.enqueue((0 until 20_001).map { item("library-$it") }, "a")
        assertEquals(20_001, database.stats("a").songs)
    }
    @Test fun reportsPerSongLinkStateForSongProperties() {
        assertEquals("none", database.status(song, "a").state)
        database.enqueue(listOf(item()), "a")
        assertEquals("queued", database.status(song, "a").state)
        val task = requireNotNull(database.next("a"))
        database.save(task, listOf(recording().put("catalog", true)), false, database.generation)
        val status = database.status(song, "a")
        assertEquals("linked", status.state)
        assertFalse(status.verified)
        val json = status.toJson(item())
        assertEquals("https://music.youtube.com/watch?v=abcdefghijk", json.getString("youtube"))
        assertEquals("https://open.spotify.com/track/example", json.getString("spotify"))
        assertEquals("Song", json.getJSONObject("recording").getString("title"))
        assertFalse(json.toString().contains("https://audio.test"))
        assertFalse(json.toString().contains("private"))
    }
    @Test fun waitingStateFollowsTheRetryBackoff() {
        database.enqueue(listOf(item()), "a")
        database.failed(requireNotNull(database.next("a")), database.generation)
        assertEquals("waiting", database.status(song, "a").state)
    }
}

package io.github.lynlioss.klang.spotify

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteDatabase
import app.lynli.klang.importing.ImportSong
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Only catalog identities/credits live on disk. Expiring audio URLs stay in memory. */
internal fun recordingMatchKey(song: ImportSong, account: String): String = MessageDigest.getInstance("SHA-256")
    .digest(JSONArray(listOf(account, song.sourceId, song.title, song.artist, song.album, song.durationMs, song.explicit))
        .toString().toByteArray()).joinToString("") { "%02x".format(it) }

internal fun recordingCredits(info: JSONObject): JSONObject = JSONObject().apply {
    listOf("id", "title", "track", "artist", "artists", "album", "duration", "channel", "uploader", "explicit", "catalog")
        .forEach { name -> if (info.has(name)) put(name, info.get(name)) }
    require(getString("id").matches(Regex("[A-Za-z0-9_-]{11}")))
}

internal data class MatchTask(val key: String, val account: String, val item: JSONObject, val attempts: Int)
internal data class MatchStats(val songs: Int, val matched: Int, val verified: Int, val pending: Int, val bytes: Long)

/** Durable queue and per-song updates avoid rewriting a whole library for each recording match. */
internal class RecordingDatabase(private val context: Context) : SQLiteOpenHelper(context, NAME, null, 1) {
    private val legacy = File(context.filesDir, "playback/recording-matches.json")
    @Volatile var generation = 0L
        private set

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SCHEMA)
        db.execSQL("CREATE INDEX matching_work ON songs(account, priority DESC, retryAt, touched)")
        db.execSQL("CREATE TABLE migrations (name TEXT PRIMARY KEY)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized private fun migrate(account: String) {
        val db = writableDatabase
        db.rawQuery("SELECT name FROM migrations WHERE name='json'", null).use { if (it.moveToFirst()) return }
        db.beginTransaction()
        try {
            if (legacy.isFile && legacy.length() <= 4 * 1024 * 1024) runCatching {
                val entries = JSONObject(legacy.readText()).getJSONArray("entries")
                for (index in 0 until entries.length()) {
                    val entry = entries.getJSONObject(index)
                    val credits = recordingCredits(entry.getJSONObject("recording")).put("catalog", true)
                    val values = ContentValues().apply {
                        put("key", entry.getString("key")); put("account", account); put("item", "{}")
                        put("candidates", JSONArray().put(credits).toString()); put("verified", 1)
                        put("matchedAt", entry.optLong("savedAt")); put("touched", entry.optLong("savedAt"))
                    }
                    db.insertWithOnConflict("songs", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            db.execSQL("INSERT OR IGNORE INTO migrations(name) VALUES('json')")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        legacy.delete(); File(legacy.parentFile, "${legacy.name}.tmp").delete()
    }

    @Synchronized fun enqueue(items: List<JSONObject>, account: String, priority: Int = 0, expectedGeneration: Long = generation): Boolean {
        if (expectedGeneration != generation) return false
        migrate(account)
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            items.take(100).filter { it.optString("kind") == "track" && it.optBoolean("available", true) && !it.optBoolean("local") }
                .forEach { item ->
                    val song = runCatching { CatalogCodec.importSong(item) }.getOrNull() ?: return@forEach
                    val key = recordingMatchKey(song, account)
                    val clean = JSONObject().apply {
                        listOf("id", "kind", "url", "title", "artist", "album", "durationMs", "explicit", "available", "local")
                            .forEach { name -> if (item.has(name)) put(name, item.get(name)) }
                    }
                    val values = ContentValues().apply {
                        put("key", key); put("account", account); put("item", clean.toString())
                        put("priority", priority.coerceIn(0, 2)); put("touched", now)
                    }
                    db.insertWithOnConflict("songs", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    db.execSQL("UPDATE songs SET item=?, priority=MAX(priority,?), touched=? WHERE key=?",
                        arrayOf(clean.toString(), priority.coerceIn(0, 2), now, key))
                }
            db.execSQL("DELETE FROM songs WHERE key IN (SELECT key FROM songs ORDER BY touched DESC LIMIT -1 OFFSET 20000)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return true
    }

    @Synchronized fun candidates(song: ImportSong, account: String): List<JSONObject> {
        migrate(account)
        return readableDatabase.rawQuery("SELECT candidates, matchedAt FROM songs WHERE key=? AND account=?",
            arrayOf(recordingMatchKey(song, account), account)).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getLong(1) < System.currentTimeMillis() - MAX_AGE) emptyList() else
                runCatching { JSONArray(cursor.getString(0)).let { list -> (0 until list.length()).map(list::getJSONObject) } }.getOrDefault(emptyList())
        }
    }
    @Synchronized fun save(task: MatchTask, candidates: List<JSONObject>, verified: Boolean, expectedGeneration: Long = generation) {
        if (expectedGeneration != generation) return
        val values = ContentValues().apply {
            put("candidates", JSONArray(candidates.take(4).map(::recordingCredits)).toString())
            put("verified", if (verified) 1 else 0); put("matchedAt", System.currentTimeMillis())
            put("retryAt", 0); put("attempts", 0)
        }
        val clause = if (verified) "key=? AND account=?" else "key=? AND account=? AND (verified=0 OR matchedAt<${System.currentTimeMillis() - MAX_AGE})"
        writableDatabase.update("songs", values, clause, arrayOf(task.key, task.account))
    }
    @Synchronized fun saveVerified(item: JSONObject, account: String, recording: JSONObject, expectedGeneration: Long) {
        if (expectedGeneration != generation) return
        enqueue(listOf(JSONObject(item.toString()).put("kind", "track")), account, 2)
        val song = CatalogCodec.importSong(item)
        save(MatchTask(recordingMatchKey(song, account), account, item, 0), listOf(recordingCredits(recording).put("catalog", true)), true, expectedGeneration)
    }
    @Synchronized fun reject(song: ImportSong, account: String) {
        writableDatabase.execSQL("UPDATE songs SET candidates='[]', verified=0, matchedAt=0, retryAt=0 WHERE key=? AND account=?",
            arrayOf(recordingMatchKey(song, account), account))
    }
    @Synchronized fun next(account: String): MatchTask? {
        migrate(account)
        val now = System.currentTimeMillis()
        return readableDatabase.rawQuery(NEXT_TASK, arrayOf(account, now.toString(), (now - MAX_AGE).toString())).use { cursor ->
            if (!cursor.moveToFirst()) null else MatchTask(cursor.getString(0), account, JSONObject(cursor.getString(1)), cursor.getInt(2))
        }
    }
    @Synchronized fun failed(task: MatchTask, expectedGeneration: Long) {
        if (expectedGeneration != generation) return
        val backoff = if (task.attempts == 0) 60_000L else if (task.attempts == 1) 15 * 60_000L else 24 * 60 * 60_000L
        writableDatabase.execSQL("UPDATE songs SET attempts=attempts+1, retryAt=? WHERE key=? AND account=? AND (candidates='[]' OR matchedAt<${System.currentTimeMillis() - MAX_AGE})",
            arrayOf(System.currentTimeMillis() + backoff, task.key, task.account))
    }
    @Synchronized fun stats(account: String): MatchStats {
        migrate(account)
        return readableDatabase.rawQuery("SELECT COUNT(*), SUM(candidates!='[]' AND matchedAt>=?), SUM(verified=1 AND matchedAt>=?), SUM(candidates='[]' OR matchedAt<?) FROM songs WHERE account=?",
            arrayOf((System.currentTimeMillis() - MAX_AGE).toString(), (System.currentTimeMillis() - MAX_AGE).toString(),
                (System.currentTimeMillis() - MAX_AGE).toString(), account)).use { cursor ->
            cursor.moveToFirst()
            MatchStats(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2), cursor.getInt(3),
                listOf("", "-wal", "-shm", "-journal").sumOf { File(context.getDatabasePath(NAME).path + it).length() })
        }
    }
    @Synchronized fun clear() {
        generation++
        writableDatabase.delete("songs", null, null)
        writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        writableDatabase.execSQL("VACUUM")
        legacy.delete(); File(legacy.parentFile, "${legacy.name}.tmp").delete()
    }
    companion object {
        const val SCHEMA = "CREATE TABLE songs (key TEXT PRIMARY KEY, account TEXT NOT NULL, item TEXT NOT NULL, candidates TEXT NOT NULL DEFAULT '[]', verified INTEGER NOT NULL DEFAULT 0, matchedAt INTEGER NOT NULL DEFAULT 0, touched INTEGER NOT NULL DEFAULT 0, priority INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0, retryAt INTEGER NOT NULL DEFAULT 0)"
        const val NEXT_TASK = "SELECT key,item,attempts FROM songs WHERE account=? AND retryAt<=? AND (candidates='[]' OR matchedAt<?) AND item!='{}' ORDER BY priority DESC, touched DESC LIMIT 1"
        private const val NAME = "recording-links.db"
        private const val MAX_AGE = 30L * 24 * 60 * 60 * 1000
        private var instance: RecordingDatabase? = null
        @Synchronized fun get(context: Context) = instance ?: RecordingDatabase(context.applicationContext).also { instance = it }
    }
}

package io.github.lynlioss.klang.spotify

import app.lynli.klang.importing.ImportSong
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Persist validated recording identities, never signed audio URLs or account credentials. */
internal class RecordingMatches(private val file: File, private val capacity: Int = 2_000,
    private val now: () -> Long = System::currentTimeMillis) {
    private val entries = LinkedHashMap<String, JSONObject>(16, .75f, true)
    private var loaded = false

    @Synchronized fun get(song: ImportSong, account: String): JSONObject? {
        read()
        val key = key(song, account)
        val entry = entries[key] ?: return null
        if (entry.optLong("savedAt") < now() - MAX_AGE) { entries.remove(key); return null }
        return entry.optJSONObject("recording")?.let { JSONObject(it.toString()) }
    }

    @Synchronized fun put(song: ImportSong, account: String, info: JSONObject) {
        read()
        val recording = JSONObject()
        listOf("id", "title", "track", "artist", "artists", "album", "duration", "channel", "uploader", "explicit", "catalog")
            .forEach { name -> if (info.has(name)) recording.put(name, info.get(name)) }
        require(recording.getString("id").matches(Regex("[A-Za-z0-9_-]{11}")))
        // These credits were already checked against full extraction. They can fill future omissions.
        recording.put("catalog", true)
        val cacheKey = key(song, account)
        val previous = entries[cacheKey]
        if (previous?.optJSONObject("recording")?.toString() == recording.toString() &&
            now() - previous.optLong("savedAt") < 24 * 60 * 60 * 1000) return
        entries[cacheKey] = JSONObject().put("savedAt", now()).put("recording", recording)
        while (entries.size > capacity) entries.remove(entries.keys.first())
        write()
    }

    @Synchronized fun remove(song: ImportSong, account: String) {
        read(); entries.remove(key(song, account)); write()
    }

    private fun read() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.isFile || file.length() > 4 * 1024 * 1024) return@runCatching
            val saved = JSONObject(file.readText()).getJSONArray("entries")
            for (index in maxOf(0, saved.length() - capacity) until saved.length()) {
                val entry = saved.getJSONObject(index)
                if (entry.optLong("savedAt") >= now() - MAX_AGE) entries[entry.getString("key")] = entry
            }
        }.onFailure { entries.clear() }
    }

    private fun write() {
        // A full disk or an interrupted cache write must never prevent playback.
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(JSONObject().put("entries", JSONArray(entries.map { (key, entry) ->
                JSONObject(entry.toString()).put("key", key)
            })).toString())
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun key(song: ImportSong, account: String): String {
        val input = JSONArray(listOf(account, song.id, song.title, song.artist, song.album, song.durationMs, song.explicit)).toString()
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private companion object { const val MAX_AGE = 30L * 24 * 60 * 60 * 1000 }
}

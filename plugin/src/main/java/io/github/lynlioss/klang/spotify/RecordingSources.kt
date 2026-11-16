package io.github.lynlioss.klang.spotify

import android.content.Context
import android.os.ParcelFileDescriptor
import app.lynli.klang.importing.*
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class RecordingSources(private val context: Context) {
    private val streams = ConcurrentHashMap<String, JSONObject>()
    private val locks = Array(16) { Mutex() }
    private val prefs get() = context.getSharedPreferences("source_preferences", Context.MODE_PRIVATE)
    suspend fun resolve(item: JSONObject, processId: String): JSONObject = locks[(item.getString("id").hashCode() and Int.MAX_VALUE) % locks.size].withLock {
        require(item.optBoolean("available", true) && !item.optBoolean("local")) { "This Spotify item isn't available for matching." }
        val vault = AccountVault.get(context)
        val account = vault.revision("spotify") to vault.revision("qobuz")
        val key = item.getString("id") + ":" + prefs.getString("source", "auto") + ":" + prefs.getInt("qobuzFormat", 27) +
            ":" + account.first + ":" + account.second
        streams[key]?.takeIf { it.optLong("expiresAt") > System.currentTimeMillis() + 60_000 }?.let { return@withLock JSONObject(it.toString()) }
        val preference = prefs.getString("source", "auto")
        val qobuz = if (preference != "youtube") try {
            QobuzSource(context).resolve(JSONObject(item.toString()).put("qobuzFormat", prefs.getInt("qobuzFormat", 27)))
        } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
            if (preference == "qobuz") throw e else null
        } else null
        val result = qobuz ?: if (preference == "qobuz") error("Qobuz has no accessible matching recording for this song.") else youtube(item, processId)
        require(account == (vault.revision("spotify") to vault.revision("qobuz"))) { "The playback account changed. Retry this song." }
        if (streams.size > 200) streams.clear()
        streams[key] = JSONObject(result.toString())
        result
    }
    private suspend fun youtube(item: JSONObject, processId: String): JSONObject {
        runInterruptible { ImportEngine.initialize(context); ImportEngine.refreshExtractor(context) }
        val song = CatalogCodec.importSong(item)
        val catalog = YouTubeCatalog.searchOrEmpty(song)
        val info = withTimeout(300_000) {
            findRecording(song, catalog, searchVideos = {
                runInterruptible { ImportEngine.info("ytsearch10:${song.artist} ${song.title} official audio", processId, flat = true)
                    .optJSONArray("entries")?.let { array -> (0 until array.length()).mapNotNull(array::optJSONObject) }.orEmpty() }
            }, extract = { url -> runInterruptible { ImportEngine.info(url, processId) } })
        }
        val audio = selectOriginalAudio(info)
        val formats = info.getJSONArray("formats")
        val selected = (0 until formats.length()).map(formats::getJSONObject).first { it.optString("format_id") == audio.id }
        val url = selected.getString("url")
        require(URI(url).scheme == "https")
        val expire = URI(url).rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("expire=") }
            ?.substringAfter('=')?.toLongOrNull()?.times(1000) ?: System.currentTimeMillis() + 10 * 60_000
        val headers = selected.optJSONObject("http_headers") ?: info.optJSONObject("http_headers") ?: JSONObject()
        // Account cookies and auth headers are only used in the plugin, never exported as playback credentials.
        val safeHeaders = JSONObject()
        listOf("User-Agent", "Referer", "Origin").forEach { name -> headers.optString(name).takeIf { it.isNotBlank() }?.let { safeHeaders.put(name, it) } }
        return JSONObject().put("url", url).put("headers", safeHeaders).put("source", "youtube")
            .put("format", audio.description).put("expiresAt", expire).put("info", info)
            .put("recordingMetadata", JSONObject().put("id", info.getString("id")).put("title", info.optString("track", info.optString("title")))
                .put("artist", info.optString("artist")).put("durationMs", (info.optDouble("duration", 0.0) * 1000).toLong()))
    }
    suspend fun download(item: JSONObject, output: ParcelFileDescriptor, processId: String,
        progress: (JSONObject) -> Unit): JSONObject {
        val directory = File(context.cacheDir, "source-downloads/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            progress(JSONObject().put("phase", "matching"))
            val stream = resolve(item, processId)
            val info = stream.getJSONObject("info")
            val song = CatalogCodec.importSong(item)
            runInterruptible { ImportEngine.initialize(context) }
            tagInfo(info, song)
            val audio = selectOriginalAudio(info)
            val source = File(directory, "source.json").apply { writeText(info.toString()) }
            val request = ImportEngine.request().apply {
                addOption("--load-info-json", source.absolutePath); addOption("--no-playlist")
                addOption("-f", audio.id); addOption("--postprocessor-args", STREAM_COPY_ARGUMENTS)
                addOption("--extract-audio"); addOption("--audio-format", "best")
                addOption("--embed-metadata"); addOption("--embed-thumbnail"); addOption("--convert-thumbnails", "jpg")
                addOption("--concurrent-fragments", 8); addOption("--http-chunk-size", "1M")
                addOption("--fragment-retries", 5); addOption("--abort-on-unavailable-fragments")
                addOption("--newline"); addOption("--no-mtime"); addOption("-o", File(directory, "audio.%(ext)s").absolutePath)
            }
            var last = 0L
            runInterruptible {
                YoutubeDL.execute(request, processId) { percent, eta, line ->
                    val tagging = listOf("[ExtractAudio]", "[Metadata]", "[EmbedThumbnail]").any(line::startsWith)
                    val now = System.currentTimeMillis()
                    if (now - last >= 300 || tagging) {
                        last = now
                        progress(JSONObject().put("phase", if (tagging) "tagging" else "downloading")
                            .put("progress", (percent / 100).coerceIn(0f, 1f)).put("eta", eta).put("audioFormat", stream.optString("format")))
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val file = directory.listFiles()?.firstOrNull { it.name.startsWith("audio.") && it.extension in listOf("flac", "m4a", "opus", "ogg", "mp3") && it.length() > 0 }
                ?: error("The recording download didn't produce a playable file.")
            require(file.length() <= 512L * 1024 * 1024) { "The recording exceeds the download size limit." }
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { destination -> file.inputStream().use { it.copyTo(destination) } }
            return JSONObject().put("extension", file.extension).put("song", CatalogCodec.importJson(item))
                .put("audioFormat", stream.optString("format")).put("recording", info.optString("webpage_url"))
        } finally { directory.deleteRecursively() }
    }
}

package io.github.lynlioss.klang.spotify

import android.content.Context
import android.os.ParcelFileDescriptor
import app.lynli.klang.importing.*
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID

internal class RecordingSources private constructor(private val context: Context) {
    // Accounts/catalog bind this service before a song is tapped. Unpack the fallback runtime then.
    private val runtime = CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
        runInterruptible { ImportEngine.initializePlayback(context) }
    }
    private val streams = LinkedHashMap<String, JSONObject>(16, .75f, true)
    private val matches = RecordingMatches(File(context.filesDir, "playback/recording-matches.json"))
    private val locks = RecordingLocks()
    private val prefs get() = context.getSharedPreferences("source_preferences", Context.MODE_PRIVATE)
    suspend fun resolve(item: JSONObject, processId: String, refresh: Boolean = false): JSONObject {
        require(item.optBoolean("available", true) && !item.optBoolean("local")) { "This Spotify item isn't available for matching." }
        val vault = AccountVault.get(context)
        val account = vault.revision("spotify") to vault.revision("qobuz")
        val preference = playbackPreference(prefs.getString("source", null), vault.get("qobuz_token") != null)
        val format = prefs.getInt("qobuzFormat", 27)
        val key = item.getString("id") + ":" + preference + ":" + format +
            ":" + account.first + ":" + account.second
        return locks.withRecording(key) {
            synchronized(streams) {
                if (refresh) streams.remove(key)
                streams[key]?.takeIf { it.optLong("expiresAt") > System.currentTimeMillis() + 60_000 }
                    ?.let { return@withRecording JSONObject(it.toString()) }
            }
            val result = withTimeout(45_000) {
                val qobuz = if (preference != "youtube") try {
                    QobuzSource(context).resolve(JSONObject(item.toString()).put("qobuzFormat", format))
                } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
                    if (preference == "qobuz") throw e else null
                } else null
                qobuz ?: if (preference == "qobuz") error("Qobuz has no accessible matching recording for this song.")
                    else youtube(item, processId, account.first)
            }
            require(account == (vault.revision("spotify") to vault.revision("qobuz"))) { "The playback account changed. Retry this song." }
            synchronized(streams) {
                streams[key] = JSONObject(result.toString())
                while (streams.size > 128) streams.remove(streams.keys.first())
            }
            result
        }
    }
    private suspend fun youtube(item: JSONObject, processId: String, account: String): JSONObject {
        val song = CatalogCodec.importSong(item)
        var info: JSONObject? = null
        val saved = matches.get(song, account)
        if (saved != null) {
            try {
                info = findRecording(song, listOf(saved), searchVideos = { emptyList() },
                    extract = { url -> fastExtract(url, processId) })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { matches.remove(song, account) }
        }
        if (info == null) {
            val catalog = try { withTimeout(5_000) { YouTubeCatalog.searchOrEmpty(song) } }
                catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); emptyList() }
            info = findRecording(song, catalog, searchVideos = {
                runtime.await()
                playbackInfo("ytsearch5:${song.artist} ${song.title} official audio", processId, flat = true)
                    .optJSONArray("entries")?.let { array -> (0 until array.length()).mapNotNull(array::optJSONObject) }.orEmpty()
            }, extract = { url -> fastExtract(url, processId) })
        }
        val recording = requireNotNull(info)
        matches.put(song, account, recording)
        val audio = selectOriginalAudio(recording)
        val formats = recording.getJSONArray("formats")
        val selected = (0 until formats.length()).map(formats::getJSONObject).first { it.optString("format_id") == audio.id }
        val url = selected.getString("url")
        require(URI(url).scheme == "https")
        val expire = URI(url).rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("expire=") }
            ?.substringAfter('=')?.toLongOrNull()?.times(1000) ?: System.currentTimeMillis() + 10 * 60_000
        val headers = selected.optJSONObject("http_headers") ?: recording.optJSONObject("http_headers") ?: JSONObject()
        // Account cookies and auth headers are only used in the plugin, never exported as playback credentials.
        val safeHeaders = JSONObject()
        listOf("User-Agent", "Referer", "Origin").forEach { name -> headers.optString(name).takeIf { it.isNotBlank() }?.let { safeHeaders.put(name, it) } }
        return JSONObject().put("url", url).put("headers", safeHeaders).put("source", "youtube")
            .put("format", audio.description).put("expiresAt", expire).put("info", recording)
            .put("cacheKey", recordingCacheKey(recording.getString("id"), selected))
            .put("recordingMetadata", JSONObject().put("id", recording.getString("id")).put("title", recording.optString("track", recording.optString("title")))
                .put("artist", recording.optString("artist")).put("durationMs", (recording.optDouble("duration", 0.0) * 1000).toLong()))
    }
    /** Native HTTPS audio can resolve before a Python process starts. Both paths keep recording checks. */
    private suspend fun fastExtract(url: String, processId: String): JSONObject = supervisorScope {
        val direct = async { runCatching { withTimeout(2_500) { YouTubeAudio.info(url.substringAfter("v=")) } } }
        val fallback = async {
            runCatching {
                delay(200)
                runtime.await()
                playbackInfo(url, processId)
            }
        }
        try {
            kotlinx.coroutines.selects.select {
                direct.onAwait { result -> result.getOrNull() ?: fallback.await().getOrThrow() }
                fallback.onAwait { result -> result.getOrNull() ?: direct.await().getOrThrow() }
            }
        } finally {
            direct.cancel()
            if (fallback.isActive) { fallback.cancel(); ImportEngine.stop(processId) }
        }
    }
    private suspend fun playbackInfo(url: String, processId: String, flat: Boolean = false): JSONObject {
        try { return withTimeout(18_000) { runInterruptible { ImportEngine.info(url, processId, flat, playback = true) } } }
        catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw java.io.IOException("The recording request timed out.")
        }
        finally { ImportEngine.stop(processId) }
    }
    suspend fun download(item: JSONObject, output: ParcelFileDescriptor, processId: String,
        progress: (JSONObject) -> Unit): JSONObject {
        val directory = File(context.cacheDir, "source-downloads/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            progress(JSONObject().put("phase", "matching"))
            val stream = resolve(item, processId)
            var info = stream.getJSONObject("info")
            val song = CatalogCodec.importSong(item)
            runInterruptible { ImportEngine.initialize(context) }
            runInterruptible { ImportEngine.refreshExtractor(context) }
            // The downloader needs yt-dlp's complete post-processing fields, unlike Media3 playback.
            if (info.optBoolean("nativePlayback")) info = runInterruptible {
                ImportEngine.info(info.getString("webpage_url"), processId)
            }
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
    companion object {
        // Klang unbinds after each RPC. Keep temporary stream URLs for subsequent seeks.
        private var instance: RecordingSources? = null
        @Synchronized fun get(context: Context) = instance ?: RecordingSources(context.applicationContext).also { instance = it }
    }
}

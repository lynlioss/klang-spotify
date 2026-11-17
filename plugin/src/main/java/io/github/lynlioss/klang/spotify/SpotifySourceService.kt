package io.github.lynlioss.klang.spotify

import android.app.Service
import android.content.Intent
import android.os.*
import app.lynli.klang.importing.ImportEngine
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** API 1 service. Catalog, source matching and downloads execute entirely under this APK's UID. */
class SpotifySourceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private lateinit var catalog: SpotifyCatalog
    private lateinit var sources: RecordingSources
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val output = message.data.getParcelable("output", ParcelFileDescriptor::class.java)
            if (packageManager.getPackagesForUid(message.sendingUid)?.contains(HOST) != true) { output?.close(); return }
            val reply = message.replyTo ?: run { output?.close(); return }
            val token = message.data.getString("token")?.takeIf { it.length in 1..100 } ?: run { output?.close(); return }
            val key = "${message.sendingUid}:$token"
            if (message.what == 3) { jobs.remove(key)?.cancel(); ImportEngine.stop(key); output?.close(); return }
            if (message.what !in listOf(1, 2, 20) || jobs.containsKey(key) || jobs.size >= 12) { output?.close(); return }
            val command = message.what
            val payload = message.data.getString("json").orEmpty()
            if (payload.length > 400_000) { output?.close(); send(reply, 12, token, "Request is too large"); return }
            val death = IBinder.DeathRecipient { jobs.remove(key)?.cancel(); ImportEngine.stop(key) }
            try { reply.binder.linkToDeath(death, 0) } catch (_: RemoteException) { output?.close(); return }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = when (command) {
                        1 -> withTimeout(300_000) { catalog.preview(payload) }
                        2 -> sources.download(JSONObject(payload), requireNotNull(output), key) { progress -> send(reply, 11, token, progress.toString()) }
                        else -> {
                            val request = JSONObject(payload)
                            val operation = request.getString("operation")
                            val args = request.optJSONObject("args") ?: JSONObject()
                            if (operation == "stream.prepare") {
                                val items = args.getJSONArray("items")
                                require(items.length() <= 100)
                                BackgroundMatches.offer(this@SpotifySourceService, (0 until items.length()).map(items::getJSONObject), 2)
                                JSONObject().put("ok", true)
                            } else if (operation == "stream.resolve") withTimeout(50_000) {
                                sources.resolve(args.getJSONObject("item"), key, args.optBoolean("refresh")).also { it.remove("info") }
                            }
                            else withTimeout(85_000) { catalog.call(operation, args) }
                        }
                    }
                    result(reply, token, result)
                } catch (e: TimeoutCancellationException) {
                    ImportEngine.stop(key); send(reply, 12, token, "The service took too long. Retry this song or refresh the page.")
                } catch (e: CancellationException) { ImportEngine.stop(key); throw e }
                catch (e: LinkageError) { send(reply, 12, token, "The audio engine couldn't start. Update the Spotify plugin.") }
                catch (e: Exception) { send(reply, 12, token, userError(e)) }
                finally { output?.close(); jobs.remove(key); runCatching { reply.binder.unlinkToDeath(death, 0) } }
            }
            jobs[key] = job; job.start()
        }
    })
    override fun onCreate() { super.onCreate(); catalog = SpotifyCatalog(this); sources = RecordingSources.get(this); BackgroundLibrary.schedule(this) }
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() { jobs.keys.forEach(ImportEngine::stop); scope.cancel(); super.onDestroy() }
    private fun send(reply: Messenger, code: Int, token: String, json: String) {
        runCatching { reply.send(Message.obtain(null, code).apply { data = Bundle().apply { putString("token", token); putString("json", json) } }) }
    }
    private suspend fun result(reply: Messenger, token: String, result: JSONObject) {
        val bytes = result.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 16 * 1024 * 1024) { "Response is too large" }
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            reply.send(Message.obtain(null, 10).apply { data = Bundle().apply { putString("token", token); putParcelable("metadata", pipe[0]) } })
            pipe[0].close()
            val cancellation = currentCoroutineContext().job.invokeOnCompletion { runCatching { pipe[1].close() } }
            try { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) } } finally { cancellation.dispose() }
        } finally { pipe.forEach { runCatching { it.close() } } }
    }
    private fun userError(e: Exception): String = when (e) {
        is moe.rukamori.archivetune.spotify.Spotify.SpotifyException -> when (e.statusCode) {
            401 -> "Spotify login expired. Log in again in the plugin's settings."
            403 -> "Spotify denied this request for your account or this playlist."
            429 -> "Spotify is limiting requests. Try again in ${e.retryAfterSec.coerceAtLeast(1)} seconds."
            412 -> "Spotify changed its web API. Refresh or update the plugin."
            else -> "Spotify couldn't complete the request (${e.statusCode})."
        }
        is IllegalArgumentException, is IllegalStateException -> e.message?.take(300).orEmpty().ifBlank { "The source couldn't complete the request." }
        else -> "The music service couldn't complete the request. Check your connection and retry."
    }
    companion object { const val HOST = "io.github.lynlioss.klang" }
}

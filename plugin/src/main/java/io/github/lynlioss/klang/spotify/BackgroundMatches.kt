package io.github.lynlioss.klang.spotify

import android.content.Context
import androidx.work.*
import app.lynli.klang.importing.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Public catalog matching uses a durable queue; playback requests suspend the background worker. */
internal object BackgroundMatches {
    private const val WORK = "spotify-recording-links"
    private val foreground = AtomicInteger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistence = kotlinx.coroutines.sync.Mutex()
    fun playbackStarted() { foreground.incrementAndGet() }
    fun playbackFinished() { foreground.decrementAndGet() }
    suspend fun <T> catalogWork(block: suspend () -> T): T = coroutineScope {
        while (foreground.get() > 0) delay(100)
        val request = async { block() }
        try {
            while (!request.isCompleted) {
                if (foreground.get() > 0) { request.cancelAndJoin(); throw PreparationYielded() }
                delay(100)
            }
            request.await()
        } finally { request.cancel() }
    }
    fun observe(context: Context, result: JSONObject, priority: Int = 0) {
        val list = result.optJSONArray("items") ?: return
        val account = AccountVault.get(context).revision("spotify")
        val database = RecordingDatabase.get(context)
        val generation = database.generation
        val items = (0 until list.length()).mapNotNull(list::optJSONObject)
        if (items.none { it.optString("kind") == "track" }) return
        // SQLite and WorkManager startup cannot delay returning a catalog page to Klang.
        scope.launch {
            persistence.lock()
            try { if (AccountVault.get(context).revision("spotify") == account && database.generation == generation) offer(context, items, priority, generation) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Playback can still match the song on demand. */ }
            finally { persistence.unlock() }
        }
    }
    fun offer(context: Context, items: List<JSONObject>, priority: Int, expectedGeneration: Long = RecordingDatabase.get(context).generation) {
        if (items.none { it.optString("kind") == "track" && it.optBoolean("available", true) }) return
        val account = AccountVault.get(context).revision("spotify")
        if (!RecordingDatabase.get(context).enqueue(items, account, priority, expectedGeneration)) return
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RecordingMatchWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build())
    }
    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(WORK) }
    class PreparationYielded : Exception()
}

/** One search at a time, with a short budget, so thousands of liked songs never flood the network. */
class RecordingMatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val database = RecordingDatabase.get(applicationContext)
        val vault = AccountVault.get(applicationContext)
        val account = vault.revision("spotify")
        val generation = database.generation
        var processed = 0
        val deadline = System.currentTimeMillis() + 4 * 60_000
        try {
            while (!isStopped && System.currentTimeMillis() < deadline && vault.revision("spotify") == account && database.generation == generation) {
                val task = database.next(account) ?: return@withContext if (database.stats(account).pending > 0) Result.retry() else Result.success()
                try {
                    val song = CatalogCodec.importSong(task.item)
                    val candidates = BackgroundMatches.catalogWork {
                        withTimeout(5_000) { YouTubeCatalog.searchOrEmpty(song) }
                    }.filter { recordingScore(song, it) >= .8 }.sortedByDescending { recordingScore(song, it) }.take(4)
                    if (candidates.isEmpty()) database.failed(task, generation) else database.save(task, candidates, verified = false, expectedGeneration = generation)
                } catch (_: BackgroundMatches.PreparationYielded) {
                    delay(100)
                } catch (cancelled: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw cancelled
                    database.failed(task, generation)
                } catch (_: Exception) {
                    if (vault.revision("spotify") == account) database.failed(task, generation)
                }
                processed++
                delay(500)
            }
            if (vault.revision("spotify") != account || database.generation != generation) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (processed > 0) Result.retry() else Result.failure() }
    }
}

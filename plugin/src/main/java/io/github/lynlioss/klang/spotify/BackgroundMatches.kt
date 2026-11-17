package io.github.lynlioss.klang.spotify

import android.content.Context
import androidx.work.*
import app.lynli.klang.importing.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Public catalog matching uses a durable queue; playback requests suspend the background worker. */
internal object BackgroundMatches {
    private const val WORK = "spotify-recording-links"
    private val foreground = AtomicInteger()
    private val epoch = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistence = kotlinx.coroutines.sync.Mutex()
    private val scheduling = kotlinx.coroutines.sync.Mutex()
    fun playbackStarted() { foreground.incrementAndGet() }
    fun playbackFinished() { foreground.decrementAndGet() }
    suspend fun <T> catalogWork(block: suspend () -> T): T = coroutineScope {
        if (foreground.get() > 0) throw PreparationYielded()
        val request = async { block() }
        try {
            while (!request.isCompleted) {
                if (foreground.get() > 0) { request.cancelAndJoin(); throw PreparationYielded() }
                withTimeoutOrNull(100) { request.join() }
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
        start(context)
    }
    fun start(context: Context) {
        val app = context.applicationContext
        val database = RecordingDatabase.get(app)
        val generation = database.generation
        val captured = epoch.get()
        scope.launch {
            scheduling.lock()
            try {
                val manager = WorkManager.getInstance(app)
                if (database.generation != generation || epoch.get() != captured) return@launch
                // Coalesce page discoveries into at most a running worker and one continuation.
                // The continuation closes the enqueue/worker-completion race.
                val unfinished = runInterruptible { manager.getWorkInfosForUniqueWork(WORK).get() }.filter { !it.state.isFinished }
                val retrying = unfinished.isNotEmpty() && unfinished.none { it.state == WorkInfo.State.RUNNING } && unfinished.any { it.runAttemptCount > 0 }
                if (!retrying && unfinished.size >= 2) return@launch
                runInterruptible {
                    manager.enqueueUniqueWork(WORK, if (retrying) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                        OneTimeWorkRequestBuilder<RecordingMatchWorker>()
                            .setInputData(workDataOf("generation" to generation, "account" to AccountVault.get(app).revision("spotify")))
                            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build()).result.get()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A later library page can restart background matching. */ }
            finally { scheduling.unlock() }
        }
    }
    fun cancel(context: Context) { epoch.incrementAndGet(); WorkManager.getInstance(context).cancelUniqueWork(WORK) }
    class PreparationYielded : Exception()
}

/** Four independent searches share a durable queue and immediately yield to foreground playback. */
class RecordingMatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val database = RecordingDatabase.get(applicationContext)
        val vault = AccountVault.get(applicationContext)
        val account = vault.revision("spotify")
        val generation = database.generation
        if (inputData.getLong("generation", generation) != generation || inputData.getString("account")?.let { it != account } == true)
            return@withContext Result.success()
        val deadline = System.currentTimeMillis() + 8 * 60_000
        fun active() = !isStopped && System.currentTimeMillis() < deadline && vault.revision("spotify") == account && database.generation == generation
        try {
            coroutineScope {
                List(4) { async {
                    while (active()) {
                        val task = database.claimMatches(account, 1, generation).firstOrNull() ?: break
                        try {
                            val song = CatalogCodec.importSong(task.item)
                            val candidates = BackgroundMatches.catalogWork {
                                withTimeout(8_000) { YouTubeCatalog.searchOrEmpty(song) }
                            }.map { it to recordingScore(song, it) }.filter { it.second >= .8 }
                                .sortedByDescending { it.second }.take(4).map { it.first }
                            if (active()) {
                                if (candidates.isEmpty()) database.failed(task, generation)
                                else database.save(task, candidates, verified = false, expectedGeneration = generation)
                            }
                        } catch (_: BackgroundMatches.PreparationYielded) {
                            delay(100)
                        } catch (cancelled: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            database.failed(task, generation)
                        } catch (_: Exception) {
                            if (active()) database.failed(task, generation)
                        } finally { database.release(task, generation) }
                    }
                } }.awaitAll()
            }
            if (vault.revision("spotify") != account || database.generation != generation || database.stats(account).pending == 0) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}

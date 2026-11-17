package io.github.lynlioss.klang.spotify

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.spotify.Spotify
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Discover the whole account without depending on which library pages the user opens. */
internal object BackgroundLibrary {
    private const val WORK = "spotify-library-cache"
    private const val PERIODIC = "spotify-library-cache-refresh"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduling = Mutex()
    private val epoch = AtomicLong()
    @Volatile private var last: Triple<String, Long, Long>? = null

    fun schedule(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val captured = epoch.get()
        scope.launch {
            try {
                scheduling.withLock {
                    if (captured != epoch.get()) return@withLock
                    val vault = AccountVault.get(app)
                    if (vault.get("sp_dc") == null) return@withLock
                    val account = vault.revision("spotify")
                    val generation = RecordingDatabase.get(app).generation
                    val now = System.currentTimeMillis()
                    val previous = last
                    if (!force && previous?.first == account && previous.second == generation && now - previous.third < 10 * 60_000) return@withLock
                    val manager = WorkManager.getInstance(app)
                    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
                    manager.enqueueUniqueWork(WORK, if (force) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<LibraryScanWorker>().setConstraints(constraints)
                            .setInputData(workDataOf("account" to account, "force" to force, "generation" to generation))
                            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build())
                    manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                        PeriodicWorkRequestBuilder<LibraryScanWorker>(24, TimeUnit.HOURS).setInitialDelay(24, TimeUnit.HOURS)
                            .setInputData(workDataOf("force" to true, "generation" to generation))
                            .setConstraints(constraints).build())
                    last = Triple(account, generation, now)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Catalog requests can try scheduling again. */ }
        }
    }
    fun cancel(context: Context) {
        epoch.incrementAndGet()
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        last = null
    }
}

/** Cursors and retries survive worker budgets, process restarts and individual inaccessible playlists. */
class LibraryScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val vault = AccountVault.get(applicationContext)
        if (vault.get("sp_dc") == null) return@withContext Result.success()
        val account = vault.revision("spotify")
        if (inputData.getString("account")?.let { it != account } == true) return@withContext Result.success()
        val database = RecordingDatabase.get(applicationContext)
        val generation = database.generation
        if (inputData.getLong("generation", generation) != generation) return@withContext Result.success()
        val deadline = System.currentTimeMillis() + 8 * 60_000
        val catalog = SpotifyCatalog(applicationContext)
        fun active() = !isStopped && System.currentTimeMillis() < deadline && vault.revision("spotify") == account && database.generation == generation
        try {
            database.seedLibrary(account, generation, force = runAttemptCount == 0 && inputData.getBoolean("force", false))
            BackgroundMatches.start(applicationContext)
            coroutineScope {
                List(3) { async {
                    while (active()) {
                        val task = database.claimLibrary(account, 1, generation).firstOrNull() ?: break
                        try {
                            val page = BackgroundMatches.catalogWork { withTimeout(30_000) { catalog.libraryPage(task) } }
                            if (!active()) break
                            val items = page.getJSONArray("items").let { list -> (0 until list.length()).map(list::getJSONObject) }
                            val next = if (page.isNull("nextOffset")) null else page.getInt("nextOffset")
                            database.saveLibraryPage(task, items, next, generation)
                            BackgroundMatches.start(applicationContext)
                        } catch (_: BackgroundMatches.PreparationYielded) {
                            delay(100)
                        } catch (cancelled: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            database.failed(task, generation)
                        } catch (error: Exception) {
                            if (active()) {
                                if (task.kind in setOf("playlist", "album") && error is Spotify.SpotifyException && error.statusCode in setOf(403, 404))
                                    database.saveLibraryPage(task, emptyList(), null, generation)
                                else database.failed(task, generation,
                                    if (error is Spotify.SpotifyException && error.statusCode == 429) error.retryAfterSec.coerceAtLeast(30) * 1_000
                                    else if (task.attempts > 2) 24 * 60 * 60_000L else 60_000)
                            }
                        } finally { database.release(task, generation) }
                    }
                } }.awaitAll()
            }
            BackgroundMatches.start(applicationContext)
            if (vault.revision("spotify") != account || database.generation != generation || !database.libraryPending(account)) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}

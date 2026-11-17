package io.github.lynlioss.klang.spotify

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BackgroundMatchesTest {
    @Test fun foregroundPlaybackCancelsSpeculativeCatalogSearch() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val request = async {
            try {
                BackgroundMatches.catalogWork {
                    started.complete(Unit)
                    try { delay(10_000) } finally { canceled.complete(Unit) }
                }
                false
            } catch (_: BackgroundMatches.PreparationYielded) { true }
        }
        withTimeout(2_000) { started.await() }
        BackgroundMatches.playbackStarted()
        try {
            withTimeout(2_000) { canceled.await(); assertTrue(request.await()) }
        } finally { BackgroundMatches.playbackFinished(); request.cancel() }
    }
}

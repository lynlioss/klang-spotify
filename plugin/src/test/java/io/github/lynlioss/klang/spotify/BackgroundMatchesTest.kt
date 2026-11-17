package io.github.lynlioss.klang.spotify

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BackgroundMatchesTest {
    @Test fun simultaneousMatchingRequestsYieldTogetherToPlayback() = runBlocking {
        val started = (0 until 4).map { CompletableDeferred<Unit>() }
        val requests = started.map { gate -> async {
            try {
                BackgroundMatches.catalogWork { gate.complete(Unit); awaitCancellation() }
                false
            } catch (_: BackgroundMatches.PreparationYielded) { true }
        } }
        try {
            withTimeout(2_000) { started.forEach { it.await() } }
            BackgroundMatches.playbackStarted()
            try { withTimeout(2_000) { assertTrue(requests.awaitAll().all { it }) } }
            finally { BackgroundMatches.playbackFinished() }
        } finally { requests.forEach { it.cancel() } }
    }
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

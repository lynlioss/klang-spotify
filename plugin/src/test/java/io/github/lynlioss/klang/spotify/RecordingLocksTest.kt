package io.github.lynlioss.klang.spotify

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RecordingLocksTest {
    @Test fun hashCollisionsDoNotBlockOtherSongsAndCancellationReleasesTheLock() = runBlocking {
        withTimeout(5_000) {
            val locks = RecordingLocks()
            val started = CompletableDeferred<Unit>()
            val first = launch { locks.withRecording("Aa") { started.complete(Unit); awaitCancellation() } }
            started.await()
            assertEquals("Aa".hashCode(), "BB".hashCode())
            assertEquals("second", locks.withRecording("BB") { "second" })
            val waiting = async { locks.withRecording("Aa") { "after cancellation" } }
            yield()
            assertFalse(waiting.isCompleted)
            first.cancelAndJoin()
            assertEquals("after cancellation", waiting.await())
        }
    }
}

package io.github.lynlioss.klang.spotify

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Unrelated recordings never wait on the same hash bucket. Release idle locks after cancellation. */
internal class RecordingLocks {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withRecording(key: String, block: suspend () -> T): T {
        val entry = synchronized(this) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        try { return entry.mutex.withLock { block() } }
        finally { synchronized(this) { if (--entry.users == 0) entries.remove(key) } }
    }
}

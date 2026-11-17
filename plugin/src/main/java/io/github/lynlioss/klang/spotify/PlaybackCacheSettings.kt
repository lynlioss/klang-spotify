package io.github.lynlioss.klang.spotify

import android.content.Context
import android.net.Uri

internal data class PlaybackCacheStats(val matching: MatchStats, val audioBytes: Long?, val audioSongs: Int?) {
    val totalBytes get() = matching.bytes + (audioBytes ?: 0)
}

internal class PlaybackCacheSettings(private val context: Context, bridge: String?) {
    // This plugin's settings can be launched independently; an older host may have no bridge.
    private val uri = bridge?.let(Uri::parse)?.takeIf {
        it.scheme == "content" && it.authority == "io.github.lynlioss.klang.source-cache"
    }
    fun stats(): PlaybackCacheStats {
        val account = AccountVault.get(context).revision("spotify")
        val local = RecordingDatabase.get(context).stats(account)
        val host = uri?.let { runCatching { context.contentResolver.call(it, "cache.stats", "spotify", null) }.getOrNull() }
        return PlaybackCacheStats(local, host?.getLong("audioBytes"), host?.getInt("audioSongs"))
    }
    fun clear(): PlaybackCacheStats {
        BackgroundMatches.cancel(context)
        RecordingDatabase.get(context).clear()
        RecordingSources.get(context).clearCache()
        uri?.let { context.contentResolver.call(it, "cache.clear", "spotify", null) }
        return stats()
    }
}

package io.github.lynlioss.klang.spotify

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import moe.rukamori.archivetune.spotify.*
import moe.rukamori.archivetune.spotify.models.SpotifyUser
import java.io.File
import java.util.UUID

internal object SpotifySession {
    private val mutex = Mutex()
    private val accountChanges = Mutex()
    private val mutations = Mutex()
    private val requests = Semaphore(4)
    private var expiresAt = 0L
    private fun clear() { expiresAt = 0; Spotify.accessToken = null }
    private fun prepare(context: Context) {
        if (Spotify.hashRefresher == null) Spotify.hashRefresher = SpotifyHashRefresher(
            SpotifyHashDiscovery(fetch = { SourceHttp.text(it) }), File(context.filesDir, "spotify-operation-hashes.json"))
        Spotify.logger = null
    }
    private suspend fun <T> changeAccount(block: suspend () -> T): T = accountChanges.withLock {
        var acquired = 0
        try {
            repeat(4) { requests.acquire(); acquired++ }
            mutex.withLock { block() }
        } finally { repeat(acquired) { requests.release() } }
    }
    private fun profileValues(me: SpotifyUser) = mapOf("spotify_user_id" to me.id,
        "spotify_profile" to (me.displayName ?: me.id),
        "spotify_avatar" to me.images.filter { it.url.startsWith("https://") }.minByOrNull { kotlin.math.abs((it.width ?: 160) - 160) }?.url)
    suspend fun connect(context: Context, dc: String, key: String) = changeAccount {
        prepare(context)
        val token = SpotifyAuth.fetchAccessToken(dc, key).getOrThrow()
        Spotify.accessToken = token.accessToken
        val me = try { Spotify.me().getOrThrow().also { require(it.id.isNotBlank()) } } catch (e: Exception) { clear(); throw e }
        try {
            AccountVault.get(context).putAll(profileValues(me) + mapOf("sp_dc" to dc, "sp_key" to key,
                "spotify_generation" to UUID.randomUUID().toString()))
        } catch (error: Exception) { clear(); throw error }
        expiresAt = token.accessTokenExpirationTimestampMs
    }.also { BackgroundLibrary.schedule(context, force = true) }
    suspend fun refreshProfile(context: Context) = use(context) {
        AccountVault.get(context).putAll(profileValues(Spotify.me().getOrThrow()))
    }
    suspend fun disconnect(context: Context) = changeAccount {
        AccountVault.get(context).putAll(mapOf("sp_dc" to null, "sp_key" to null, "spotify_user_id" to null,
            "spotify_profile" to null, "spotify_avatar" to null, "spotify_generation" to UUID.randomUUID().toString()))
        BackgroundLibrary.cancel(context)
        BackgroundMatches.cancel(context)
        clear()
    }
    // Catalog pages run concurrently; account changes wait for every request, including mutations.
    suspend fun <T> use(context: Context, mutation: Boolean = false, block: suspend () -> T): T {
        if (mutation) return mutations.withLock { use(context, block = block) }
        return requests.withPermit {
            val token = mutex.withLock { authenticate(context); Spotify.accessToken }
            try { block() } catch (e: Spotify.SpotifyException) {
                // Read operations may be retried by the caller. Never replay a playlist mutation automatically.
                if (e.statusCode == 401) mutex.withLock { if (Spotify.accessToken == token) clear() }
                throw e
            }
        }
    }
    private suspend fun authenticate(context: Context) {
        if (Spotify.accessToken != null && expiresAt > System.currentTimeMillis() + 60_000) return
        val vault = AccountVault.get(context)
        val cookie = vault.get("sp_dc") ?: error("Log in to Spotify in this plugin's settings.")
        prepare(context) // Upstream diagnostics include token prefixes and playlist contents.
        val token = SpotifyAuth.fetchAccessToken(cookie, vault.get("sp_key").orEmpty()).getOrThrow()
        Spotify.accessToken = token.accessToken
        expiresAt = token.accessTokenExpirationTimestampMs
    }
}

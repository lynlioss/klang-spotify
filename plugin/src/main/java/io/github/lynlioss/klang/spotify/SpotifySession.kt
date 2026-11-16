package io.github.lynlioss.klang.spotify

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.spotify.*
import java.io.File
import java.util.UUID

internal object SpotifySession {
    private val mutex = Mutex()
    private var expiresAt = 0L
    private fun clear() { expiresAt = 0; Spotify.accessToken = null }
    private fun prepare(context: Context) {
        if (Spotify.hashRefresher == null) Spotify.hashRefresher = SpotifyHashRefresher(
            SpotifyHashDiscovery(fetch = { SourceHttp.text(it) }), File(context.filesDir, "spotify-operation-hashes.json"))
        Spotify.logger = null
    }
    suspend fun connect(context: Context, dc: String, key: String) = mutex.withLock {
        prepare(context)
        val token = SpotifyAuth.fetchAccessToken(dc, key).getOrThrow()
        Spotify.accessToken = token.accessToken
        val me = try { Spotify.me().getOrThrow().also { require(it.id.isNotBlank()) } } catch (e: Exception) { clear(); throw e }
        AccountVault.get(context).putAll(mapOf("sp_dc" to dc, "sp_key" to key, "spotify_user_id" to me.id,
            "spotify_profile" to (me.displayName ?: me.id), "spotify_generation" to UUID.randomUUID().toString()))
        expiresAt = token.accessTokenExpirationTimestampMs
    }
    suspend fun disconnect(context: Context) = mutex.withLock {
        AccountVault.get(context).putAll(mapOf("sp_dc" to null, "sp_key" to null, "spotify_user_id" to null,
            "spotify_profile" to null, "spotify_generation" to UUID.randomUUID().toString()))
        clear()
    }
    // Serialize account changes with catalog requests so a mutation cannot cross accounts.
    suspend fun <T> use(context: Context, block: suspend () -> T): T = mutex.withLock {
        authenticate(context)
        try { block() } catch (e: Spotify.SpotifyException) {
            // Read operations may be retried by the caller. Never replay a playlist mutation automatically.
            if (e.statusCode == 401) clear()
            throw e
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

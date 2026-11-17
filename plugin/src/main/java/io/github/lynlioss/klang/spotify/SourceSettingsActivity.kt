@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package io.github.lynlioss.klang.spotify

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.format.Formatter
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/** Opened from the mod's detail settings. The browser and secrets live in this APK. */
class SourceSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("source_preferences", MODE_PRIVATE)
        if (intent.hasExtra("klang.theme")) preferences.edit()
            .putString("theme", intent.getStringExtra("klang.theme"))
            .putBoolean("dynamicColor", intent.getBooleanExtra("klang.dynamicColor", false)).apply()
        val theme = preferences.getString("theme", "system") ?: "system"
        val dark = theme == "dark" || theme == "system" &&
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val systemBars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
        enableEdgeToEdge(statusBarStyle = systemBars, navigationBarStyle = systemBars)
        setContent {
            SourceTheme(this, theme, preferences.getBoolean("dynamicColor", false)) {
                val background = MaterialTheme.colorScheme.surface.toArgb()
                SideEffect { window.setBackgroundDrawable(ColorDrawable(background)) }
                Surface(Modifier.fillMaxSize()) { SettingsContent() }
            }
        }
    }
    @Composable private fun SettingsContent() {
        val prefs = remember { getSharedPreferences("source_preferences", MODE_PRIVATE) }
        val scope = rememberCoroutineScope()
        var profile by remember { mutableStateOf<String?>(null) }
        var qobuzProfile by remember { mutableStateOf<String?>(null) }
        var login by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var source by remember { mutableStateOf(playbackPreference(prefs.getString("source", null), false)) }
        var qobuzExpanded by remember { mutableStateOf(false) }
        var quality by remember { mutableIntStateOf(prefs.getInt("qobuzFormat", 27)) }
        var appId by remember { mutableStateOf("") }
        var appSecret by remember { mutableStateOf("") }
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        val cacheSettings = remember { PlaybackCacheSettings(this@SourceSettingsActivity, intent.getStringExtra("klang.cacheBridge")) }
        var cache by remember { mutableStateOf<PlaybackCacheStats?>(null) }
        var clearingCache by remember { mutableStateOf(false) }
        var cacheMessage by remember { mutableStateOf<String?>(null) }
        suspend fun refresh() = withContext(Dispatchers.IO) {
            val vault = AccountVault.get(this@SourceSettingsActivity)
            Triple(vault.get("spotify_profile"), vault.get("qobuz_profile"), vault.get("qobuz_app_id").orEmpty())
        }
        LaunchedEffect(Unit) {
            try {
                val stored = refresh(); profile = stored.first; qobuzProfile = stored.second; appId = stored.third
                source = playbackPreference(prefs.getString("source", null), qobuzProfile != null)
                prefs.edit().putString("source", source).apply()
            }
            catch (_: Exception) { error = "Couldn't open the encrypted account store. Reinstall the plugin to reset it." }
        }
        LaunchedEffect(login) {
            if (!login) while (isActive) {
                try { cache = withContext(Dispatchers.IO) { cacheSettings.stats() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { cacheMessage = "Couldn't read cache storage. Try again." }
                delay(5_000)
            }
        }
        BackHandler(login) { login = false }
        if (login) {
            SpotifyLoginScreen(busy, { login = false }, ::clearBrowserData) { dc, key ->
                if (!busy) {
                    busy = true; error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { SpotifySession.connect(this@SourceSettingsActivity, dc, key) }
                            profile = refresh().first; login = false
                        } catch (_: CancellationException) { throw CancellationException() }
                        catch (_: Exception) { error = "Spotify couldn't verify this login. Finish signing in, then try again."; login = false }
                        finally { clearBrowserData(); busy = false }
                    }
                }
            }
            return
        }
        Scaffold(topBar = {
            TopAppBar(title = { Text("Spotify Sync", style = MaterialTheme.typography.titleLargeEmphasized) },
                navigationIcon = { IconButton({ finish() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to Klang") } })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .widthIn(max = 720.dp).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Your music,\ntogether.", style = MaterialTheme.typography.displaySmallEmphasized)
                Text("Connect once. Your songs, albums and playlists appear throughout Klang.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Icon(painterResource(R.drawable.spotify), null, Modifier.padding(12.dp).size(28.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Text("Spotify", style = MaterialTheme.typography.titleLargeEmphasized)
                                Text(profile?.let { "Connected as $it" } ?: "Ready to connect", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (profile != null) Icon(Icons.Rounded.CheckCircle, "Connected", tint = MaterialTheme.colorScheme.primary)
                        }
                        Button({ busy = true; error = null; scope.launch {
                            try { clearCookies(); login = true }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = "Couldn't open Spotify login. Try again." }
                            finally { busy = false }
                        } }, Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium, enabled = !busy) {
                            Icon(painterResource(R.drawable.spotify), null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                            Text(if (profile == null) "Connect Spotify" else "Switch account")
                        }
                        if (profile != null) TextButton({ scope.launch {
                            busy = true
                            try {
                                withContext(Dispatchers.IO) { AccountVault.get(this@SourceSettingsActivity).spotifyLogout() }
                                clearBrowserData(); profile = null
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = "Couldn't disconnect Spotify. Try again." }
                            finally { busy = false }
                        } }, enabled = !busy) { Icon(Icons.Rounded.Logout, null); Spacer(Modifier.width(8.dp)); Text("Disconnect") }
                    }
                }
                Text("Playback source", style = MaterialTheme.typography.titleLargeEmphasized)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PlaybackChoice("YouTube Music", "Ready to play · no account needed", Icons.Rounded.PlayCircle,
                        source == "youtube", !busy) { source = "youtube"; prefs.edit().putString("source", source).apply() }
                    PlaybackChoice("Qobuz", qobuzProfile?.let { "Connected as $it" } ?: "Connect an account for lossless audio",
                        Icons.Rounded.GraphicEq, source != "youtube", !busy) {
                        if (qobuzProfile == null) qobuzExpanded = true
                        else { source = "auto"; prefs.edit().putString("source", source).apply() }
                    }
                }
                if (source != "youtube" && qobuzProfile != null) Surface(shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("YouTube Music fallback", style = MaterialTheme.typography.titleMediumEmphasized)
                            Text("Use it when Qobuz has no matching song", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(source == "auto", { source = if (it) "auto" else "qobuz"; prefs.edit().putString("source", source).apply() })
                    }
                }
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.GraphicEq, null, Modifier.size(28.dp))
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Text("Qobuz account", style = MaterialTheme.typography.titleMediumEmphasized)
                                Text(qobuzProfile ?: "Optional · account access required", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton({ qobuzExpanded = !qobuzExpanded }) {
                                Icon(if (qobuzExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    if (qobuzExpanded) "Hide Qobuz settings" else "Show Qobuz settings")
                            }
                        }
                        AnimatedVisibility(qobuzExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Use your Qobuz account and its matching app credentials.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(appId, { appId = it }, label = { Text("Qobuz app ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(appSecret, { appSecret = it }, label = { Text("Matching app secret") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(email, { email = it }, label = { Text("Qobuz email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, label = { Text("Qobuz password") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        Button({ busy = true; error = null; scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val vault = AccountVault.get(this@SourceSettingsActivity)
                                    val secret = appSecret.trim().ifBlank {
                                        require(vault.get("qobuz_app_id") == appId.trim()) { "Enter the secret matching the new app ID." }
                                        vault.get("qobuz_app_secret").orEmpty()
                                    }
                                    QobuzSource(this@SourceSettingsActivity).login(email.trim(), password, appId.trim(), secret)
                                }
                                password = ""; appSecret = ""; qobuzProfile = refresh().second
                                source = "auto"; prefs.edit().putString("source", source).apply(); qobuzExpanded = false
                            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message?.take(200) }
                            finally { busy = false }
                        } }, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !busy && appId.isNotBlank() && email.isNotBlank() && password.isNotBlank()) {
                            Icon(Icons.Rounded.Login, null); Spacer(Modifier.width(8.dp)); Text("Connect Qobuz")
                        }
                        if (qobuzProfile != null) TextButton({ scope.launch {
                            busy = true
                            try {
                                withContext(Dispatchers.IO) { AccountVault.get(this@SourceSettingsActivity).qobuzLogout() }
                                qobuzProfile = null; source = "youtube"; prefs.edit().putString("source", source).apply()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = "Couldn't disconnect Qobuz. Try again." }
                            finally { busy = false }
                        } }, enabled = !busy) { Icon(Icons.Rounded.Logout, null); Spacer(Modifier.width(8.dp)); Text("Disconnect Qobuz") }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(6 to "CD quality", 7 to "24-bit / 96 kHz", 27 to "24-bit / 192 kHz").forEach { (value, label) ->
                                FilterChip(quality == value, { quality = value; prefs.edit().putInt("qobuzFormat", value).apply() }, label = { Text(label) })
                            }
                        }
                        }
                        }
                    }
                }
                Text("Playback cache", style = MaterialTheme.typography.titleLargeEmphasized)
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Storage, null, Modifier.size(28.dp))
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Text(cache?.let { Formatter.formatFileSize(this@SourceSettingsActivity, it.totalBytes) } ?: "Checking storage…",
                                    style = MaterialTheme.typography.headlineSmallEmphasized)
                                Text("Recording links and buffered audio", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        cache?.let { stats ->
                            Text("${stats.matching.matched} songs linked · ${stats.matching.pending} waiting",
                                style = MaterialTheme.typography.titleMediumEmphasized)
                            Text("${stats.matching.verified} recordings verified · ${stats.matching.songs} songs in the database",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider()
                            Text("Recording database: ${Formatter.formatFileSize(this@SourceSettingsActivity, stats.matching.bytes)}",
                                style = MaterialTheme.typography.bodyMedium)
                            Text(stats.audioBytes?.let { "Buffered audio: ${Formatter.formatFileSize(this@SourceSettingsActivity, it)} · ${stats.audioSongs} songs" }
                                ?: "Update Klang to see and clear its buffered audio.", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("Liked songs and search results link in the background. Nearby queue songs also buffer ahead for faster playback.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilledTonalButton({
                            clearingCache = true; cacheMessage = null
                            scope.launch {
                                try {
                                    cache = withContext(Dispatchers.IO) { cacheSettings.clear() }
                                    cacheMessage = "Playback cache cleared. Your downloads and account are kept."
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { cacheMessage = "Couldn't clear all cached audio. Try again." }
                                finally { clearingCache = false }
                            }
                        }, Modifier.fillMaxWidth(), enabled = !clearingCache && cache != null) {
                            if (clearingCache) LoadingIndicator(Modifier.size(24.dp)) else Icon(Icons.Rounded.DeleteSweep, null)
                            Spacer(Modifier.width(8.dp)); Text(if (clearingCache) "Clearing cache…" else "Clear playback cache")
                        }
                        cacheMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (busy) LoadingIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Text("Downloads keep the matched recording on your device for offline listening.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable private fun PlaybackChoice(title: String, subtitle: String, icon: ImageVector,
        selected: Boolean, enabled: Boolean, select: () -> Unit) {
        Surface(onClick = select, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(28.dp))
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                }
                RadioButton(selected, null)
            }
        }
    }

    private fun clearBrowserData() {
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
        WebStorage.getInstance().deleteAllData()
    }
    private suspend fun clearCookies() = suspendCancellableCoroutine { continuation ->
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            if (continuation.isActive) continuation.resume(Unit)
        }
    }
}

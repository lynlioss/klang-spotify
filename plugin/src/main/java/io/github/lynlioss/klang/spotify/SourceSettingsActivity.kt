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
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
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
        var avatar by remember { mutableStateOf<String?>(null) }
        var qobuzProfile by remember { mutableStateOf<String?>(null) }
        var qobuzReady by remember { mutableStateOf(false) }
        var login by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var source by remember { mutableStateOf(playbackPreference(prefs.getString("source", null), false)) }
        var qobuzExpanded by remember { mutableStateOf(false) }
        var quality by remember { mutableIntStateOf(prefs.getInt("qobuzFormat", 27)) }
        var appId by remember { mutableStateOf("") }
        var appSecret by remember { mutableStateOf("") }
        var savedAppSecret by remember { mutableStateOf(false) }
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        val cacheSettings = remember { PlaybackCacheSettings(this@SourceSettingsActivity, intent.getStringExtra("klang.cacheBridge")) }
        var cache by remember { mutableStateOf<PlaybackCacheStats?>(null) }
        var clearingCache by remember { mutableStateOf(false) }
        var cacheMessage by remember { mutableStateOf<String?>(null) }
        suspend fun refresh() = withContext(Dispatchers.IO) {
            val vault = AccountVault.get(this@SourceSettingsActivity)
            vault.snapshot(listOf("spotify_profile", "spotify_avatar", "qobuz_profile", "qobuz_app_id", "qobuz_app_secret", "qobuz_token"))
        }
        suspend fun loadAccounts() {
            val stored = refresh()
            profile = stored["spotify_profile"]; avatar = stored["spotify_avatar"]
            qobuzProfile = stored["qobuz_profile"]; appId = stored["qobuz_app_id"].orEmpty()
            qobuzReady = listOf("qobuz_app_id", "qobuz_app_secret", "qobuz_token").all { !stored[it].isNullOrBlank() }
            savedAppSecret = !stored["qobuz_app_secret"].isNullOrBlank()
            source = playbackPreference(prefs.getString("source", null), qobuzReady)
            prefs.edit().putString("source", source).apply()
        }
        LaunchedEffect(Unit) {
            try {
                loadAccounts()
                BackgroundLibrary.schedule(this@SourceSettingsActivity)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Couldn't open the encrypted account store. Reinstall the plugin to reset it." }
            if (profile != null) try {
                withContext(Dispatchers.IO) { withTimeout(10_000) { SpotifySession.refreshProfile(this@SourceSettingsActivity) } }
                val stored = refresh()
                profile = stored["spotify_profile"]; avatar = stored["spotify_avatar"]
            } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
            catch (_: Exception) { /* Stored account information remains available offline. */ }
        }
        LaunchedEffect(login) {
            if (!login) while (isActive) {
                try { cache = withContext(Dispatchers.IO) { cacheSettings.stats() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { cacheMessage = "Couldn't read cache storage. Try again." }
                delay(2_000)
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
                            loadAccounts(); login = false
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
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SpotifyAvatar(avatar)
                                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                    Text(profile ?: "Spotify", style = MaterialTheme.typography.titleMediumEmphasized,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (profile != null) "Spotify connected" else "Connect your account", style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (profile != null) Icon(Icons.Rounded.CheckCircle, "Connected", tint = MaterialTheme.colorScheme.primary)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Button({ busy = true; error = null; scope.launch {
                                    try { clearCookies(); login = true }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { error = "Couldn't open Spotify login. Try again." }
                                    finally { busy = false }
                                } }, Modifier.weight(1f).heightIn(min = 48.dp), shapes = ButtonDefaults.shapes(), enabled = !busy) {
                                    Icon(if (profile == null) Icons.Rounded.Login else Icons.Rounded.SwitchAccount, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                                    Text(if (profile == null) "Connect Spotify" else "Switch account")
                                }
                                if (profile != null) FilledTonalIconButton({ scope.launch {
                                    busy = true
                                    try {
                                        withContext(Dispatchers.IO) { AccountVault.get(this@SourceSettingsActivity).spotifyLogout() }
                                        clearBrowserData(); profile = null; avatar = null
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { error = "Couldn't disconnect Spotify. Try again." }
                                    finally { busy = false }
                                } }, enabled = !busy) { Icon(Icons.Rounded.Logout, "Disconnect Spotify") }
                            }
                        }
                    }
                    Text("Playback", style = MaterialTheme.typography.titleMediumEmphasized)
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PlaybackChoice("YouTube Music", "No account needed", Icons.Rounded.PlayCircle,
                            source == "youtube", !busy, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 4.dp)) {
                            source = "youtube"; prefs.edit().putString("source", source).apply()
                        }
                        PlaybackChoice("Qobuz", if (qobuzReady) "Lossless audio" else "Set up an account below",
                            Icons.Rounded.GraphicEq, source != "youtube" && qobuzReady, !busy && qobuzReady,
                            RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 20.dp, bottomEnd = 20.dp)) {
                            if (qobuzReady) { source = "auto"; prefs.edit().putString("source", source).apply() }
                        }
                    }
                    if (source != "youtube" && qobuzReady) Surface(shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("YouTube Music fallback", style = MaterialTheme.typography.titleMediumEmphasized)
                            }
                            Switch(source == "auto", { source = if (it) "auto" else "qobuz"; prefs.edit().putString("source", source).apply() })
                        }
                    }
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.GraphicEq, null, Modifier.size(28.dp))
                                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                    Text("Qobuz account", style = MaterialTheme.typography.titleMediumEmphasized)
                                    Text(if (qobuzReady) qobuzProfile ?: "Connected" else "Setup required", style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!qobuzReady && !qobuzExpanded) FilledTonalButton({ qobuzExpanded = true }, enabled = !busy) { Text("Set up") }
                                else IconButton({ qobuzExpanded = !qobuzExpanded }, enabled = !busy) {
                                    Icon(if (qobuzExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                        if (qobuzExpanded) "Hide Qobuz settings" else "Show Qobuz settings")
                                }
                            }
                            AnimatedVisibility(qobuzExpanded) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                            password = ""; appSecret = ""; loadAccounts(); qobuzExpanded = false
                                        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message?.take(200) }
                                        finally { busy = false }
                                    } }, Modifier.fillMaxWidth().heightIn(min = 48.dp), shapes = ButtonDefaults.shapes(),
                                        enabled = !busy && appId.isNotBlank() && (appSecret.isNotBlank() || savedAppSecret) && email.isNotBlank() && password.isNotBlank()) {
                                        Icon(Icons.Rounded.Login, null); Spacer(Modifier.width(8.dp)); Text("Connect Qobuz")
                                    }
                                    if (qobuzReady) TextButton({ scope.launch {
                                        busy = true
                                        try {
                                            withContext(Dispatchers.IO) { AccountVault.get(this@SourceSettingsActivity).qobuzLogout() }
                                            qobuzProfile = null; qobuzReady = false; savedAppSecret = false; source = "youtube"; prefs.edit().putString("source", source).apply()
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
                    Text("Playback cache", style = MaterialTheme.typography.titleMediumEmphasized)
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Storage, null, Modifier.size(28.dp))
                                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                    Text(cache?.let { Formatter.formatFileSize(this@SourceSettingsActivity, it.totalBytes) } ?: "Checking storage…",
                                        style = MaterialTheme.typography.headlineSmallEmphasized)
                                }
                            }
                            cache?.let { stats ->
                                Text("${stats.matching.matched} linked · ${stats.matching.pending} waiting",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (stats.scanning || stats.matching.pending > 0) {
                                    if (stats.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    else LinearProgressIndicator(progress = { stats.matching.matched.toFloat() / stats.matching.songs.coerceAtLeast(1) },
                                        modifier = Modifier.fillMaxWidth())
                                }
                            }
                            TextButton({
                                clearingCache = true; cacheMessage = null
                                scope.launch {
                                    try {
                                        cache = withContext(Dispatchers.IO) { cacheSettings.clear() }
                                        cacheMessage = "Cache cleared"
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { cacheMessage = "Couldn't clear all cached audio. Try again." }
                                    finally { clearingCache = false }
                                }
                            }, Modifier.align(Alignment.End).heightIn(min = 48.dp), enabled = !clearingCache && !busy && cache != null) {
                                if (clearingCache) LoadingIndicator(Modifier.size(24.dp)) else Icon(Icons.Rounded.DeleteSweep, null)
                                Spacer(Modifier.width(8.dp)); Text(if (clearingCache) "Clearing…" else "Clear cache")
                            }
                            cacheMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (busy) LoadingIndicator()
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    Spacer(Modifier.height(8.dp))
            }
            }
        }
    }

    @Composable private fun PlaybackChoice(title: String, subtitle: String, icon: ImageVector,
        selected: Boolean, enabled: Boolean, shape: RoundedCornerShape, select: () -> Unit) {
        val fill by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "Playback source")
        val content = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        Surface(modifier = Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = select),
            shape = shape, color = fill, contentColor = if (enabled) content else content.copy(alpha = .38f)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(24.dp))
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                }
                RadioButton(selected, null, enabled = enabled)
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

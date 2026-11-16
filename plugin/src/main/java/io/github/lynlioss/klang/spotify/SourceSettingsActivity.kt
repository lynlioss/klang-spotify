@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package io.github.lynlioss.klang.spotify

import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import moe.rukamori.archivetune.spotify.SpotifyAuth
import java.net.URI
import kotlin.coroutines.resume

/** Opened from the mod's detail settings. The browser and secrets live in this APK. */
class SourceSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = getSharedPreferences("source_preferences", MODE_PRIVATE)
        if (intent.hasExtra("klang.theme")) preferences.edit()
            .putString("theme", intent.getStringExtra("klang.theme"))
            .putBoolean("dynamicColor", intent.getBooleanExtra("klang.dynamicColor", false)).apply()
        setContent {
            SourceTheme(this, preferences.getString("theme", "system")!!, preferences.getBoolean("dynamicColor", false)) {
                SettingsContent()
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
        var source by remember { mutableStateOf(prefs.getString("source", "auto")!!) }
        var quality by remember { mutableIntStateOf(prefs.getInt("qobuzFormat", 27)) }
        var appId by remember { mutableStateOf("") }
        var appSecret by remember { mutableStateOf("") }
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        suspend fun refresh() = withContext(Dispatchers.IO) {
            val vault = AccountVault.get(this@SourceSettingsActivity)
            Triple(vault.get("spotify_profile"), vault.get("qobuz_profile"), vault.get("qobuz_app_id").orEmpty())
        }
        LaunchedEffect(Unit) {
            try { val stored = refresh(); profile = stored.first; qobuzProfile = stored.second; appId = stored.third }
            catch (_: Exception) { error = "Couldn't open the encrypted account store. Reinstall the plugin to reset it." }
        }
        BackHandler(login) { login = false }
        if (login) {
            SpotifyLogin(busy, { login = false }) { dc, key ->
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
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .widthIn(max = 720.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton({ finish() }) { Text("Back to Klang") }
                Text("Spotify", style = MaterialTheme.typography.headlineLargeEmphasized)
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(profile ?: "Bring your library", style = MaterialTheme.typography.titleLargeEmphasized)
                        Text("Your Spotify library and playlists, played in Klang with matching recordings.", style = MaterialTheme.typography.bodyMedium)
                        Button({ busy = true; scope.launch {
                            try { clearCookies(); login = true } finally { busy = false }
                        } }, enabled = !busy) { Text(if (profile == null) "Log in with Spotify" else "Switch Spotify account") }
                        if (profile != null) TextButton({ scope.launch {
                            withContext(Dispatchers.IO) { AccountVault.get(this@SourceSettingsActivity).spotifyLogout() }
                            clearBrowserData(); profile = null
                        } }, enabled = !busy) { Text("Log out") }
                    }
                }
                Text("Playback source", style = MaterialTheme.typography.titleLargeEmphasized)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "Qobuz, then YouTube Music", "youtube" to "YouTube Music", "qobuz" to "Qobuz").forEach { (value, label) ->
                        FilterChip(source == value, { source = value; prefs.edit().putString("source", value).apply() }, label = { Text(label) })
                    }
                }
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Qobuz", style = MaterialTheme.typography.titleLargeEmphasized)
                        Text(qobuzProfile ?: "Connect your account for lossless recordings.", style = MaterialTheme.typography.bodyMedium)
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
                            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message?.take(200) }
                            finally { busy = false }
                        } }, enabled = !busy && appId.isNotBlank() && email.isNotBlank() && password.isNotBlank()) { Text("Connect Qobuz") }
                        if (qobuzProfile != null) TextButton({ scope.launch {
                            withContext(Dispatchers.IO) { AccountVault.get(this@SourceSettingsActivity).qobuzLogout() }
                            qobuzProfile = null
                        } }, enabled = !busy) { Text("Disconnect Qobuz") }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(6 to "CD quality", 7 to "24-bit / 96 kHz", 27 to "24-bit / 192 kHz").forEach { (value, label) ->
                                FilterChip(quality == value, { quality = value; prefs.edit().putInt("qobuzFormat", value).apply() }, label = { Text(label) })
                            }
                        }
                    }
                }
                if (busy) LoadingIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Text("Downloads keep the matched recording on your device for offline listening.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable private fun SpotifyLogin(busy: Boolean, back: () -> Unit, connected: (String, String) -> Unit) {
        var browser by remember { mutableStateOf<WebView?>(null) }
        DisposableEffect(Unit) { onDispose {
            browser?.let { it.stopLoading(); it.clearCache(true); it.clearHistory(); it.destroy() }; browser = null
            clearBrowserData()
        } }
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(back) { Text("Back") }
                TextButton({ browser?.let { readCookies(it.url, connected) } }, enabled = !busy) { Text("Finish login") }
            }
            if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(factory = { context ->
                WebView(context).apply {
                    browser = this
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                    settings.allowFileAccess = false; settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            request.isForMainFrame && URI(request.url.toString()).let {
                                it.scheme != "https" || it.host !in setOf("accounts.spotify.com", "open.spotify.com", "www.spotify.com", "spotify.com")
                            }
                        override fun onPageFinished(view: WebView, url: String) { readCookies(url, connected) }
                    }
                    loadUrl(SpotifyAuth.LOGIN_URL)
                }
            }, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
    private fun readCookies(url: String?, connected: (String, String) -> Unit) {
        if (url == null || runCatching { URI(url).host }.getOrNull() != "open.spotify.com") return
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie("https://open.spotify.com").orEmpty().split(';').mapNotNull {
            val parts = it.trim().split('=', limit = 2); if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
        cookies["sp_dc"]?.takeIf { it.isNotBlank() }?.let { connected(it, cookies["sp_key"].orEmpty()) }
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

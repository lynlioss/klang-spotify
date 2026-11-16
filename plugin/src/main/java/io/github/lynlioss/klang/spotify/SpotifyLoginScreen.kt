@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package io.github.lynlioss.klang.spotify

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import moe.rukamori.archivetune.spotify.SpotifyAuth

/** Native chrome stays themed; Spotify's page is laid out in a full-size browser container. */
@Composable
internal fun SpotifyLoginScreen(busy: Boolean, back: () -> Unit, closed: () -> Unit,
    connected: (String, String) -> Unit) {
    var browser by remember { mutableStateOf<SpotifyLoginBrowser?>(null) }
    var loading by remember { mutableStateOf(true) }
    var progress by remember { mutableIntStateOf(0) }
    var attempt by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    val currentBusy = rememberUpdatedState(busy)
    val currentConnected = rememberUpdatedState(connected)
    val currentClosed = rememberUpdatedState(closed)
    val background = MaterialTheme.colorScheme.surface.toArgb()

    fun goBack() { if (browser?.goBack() != true) back() }
    BackHandler { goBack() }
    // This is a network timeout chosen for this login flow, not a Material motion token.
    LaunchedEffect(attempt, loading) {
        if (loading) {
            delay(45_000)
            browser?.stopLoading()
            loading = false
            failure = "Spotify's login page took too long to load. Check your connection and retry."
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.surface, topBar = {
        TopAppBar(title = { Text("Spotify login") }, navigationIcon = {
            TextButton({ goBack() }) { Text("Back") }
        }, actions = {
            TextButton({
                hint = if (browser?.finishLogin() == true) null else "Finish signing in on Spotify's page first."
            }, enabled = !busy && !loading && failure == null) { Text("Finish login") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (busy) {
                LinearWavyProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Verifying Spotify login" })
                Text("Connecting your Spotify account…", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium)
            } else if (loading) {
                LinearWavyProgressIndicator(progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Loading Spotify login page" })
            }
            hint?.let { Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(factory = { context ->
                    SpotifyLoginBrowser(context, background,
                        started = { loading = true; progress = 0; failure = null; hint = null; attempt++ },
                        changed = { progress = it },
                        finished = { loading = false },
                        failed = { loading = false; failure = it },
                        canSubmit = { !currentBusy.value },
                        connected = { dc, key -> currentConnected.value(dc, key) }).also {
                        browser = it
                        it.start()
                    }
                }, onRelease = {
                    it.destroy()
                    if (browser === it) browser = null
                    currentClosed.value()
                }, modifier = Modifier.fillMaxSize())
                failure?.let { message ->
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Couldn't open Spotify", style = MaterialTheme.typography.titleLargeEmphasized)
                            Spacer(Modifier.height(16.dp))
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(24.dp))
                            Button({ failure = null; loading = true; browser?.retry() }, enabled = !busy) { Text("Retry") }
                        }
                    }
                }
            }
        }
    }
}

/** Reuses YumaPlayer's full-size container, browser UA and sign-in popup approach. */
private class SpotifyLoginBrowser(context: Context, private val browserBackground: Int,
    private val started: () -> Unit, private val changed: (Int) -> Unit,
    private val finished: () -> Unit, private val failed: (String) -> Unit,
    private val canSubmit: () -> Boolean, private val connected: (String, String) -> Unit) : FrameLayout(context) {
    private val main = createWebView()
    private var popup: WebView? = null
    private var submitted = false
    private var destroyed = false
    private var loadFailed = false
    private val active get() = popup ?: main

    init { addView(main, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
    fun start() {
        loadFailed = false
        started()
        main.loadUrl(SpotifyAuth.LOGIN_URL)
    }
    fun stopLoading() { active.stopLoading() }
    fun retry() {
        popup?.let(::closePopup)
        submitted = false
        main.stopLoading()
        main.clearCache(true)
        start()
    }
    fun goBack(): Boolean = when {
        active.canGoBack() -> { active.goBack(); true }
        popup != null -> { closePopup(requireNotNull(popup)); true }
        else -> false
    }
    fun finishLogin(): Boolean = captureCookies(force = true)
    fun destroy() {
        if (destroyed) return
        destroyed = true
        popup?.let { removeView(it); it.destroyForLogin() }
        popup = null
        removeView(main)
        main.destroyForLogin()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(browserBackground)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = SpotifyLoginPolicy.browserUserAgent(WebSettings.getDefaultUserAgent(context))
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && navigate(view, request.url.toString())
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = navigate(view, url)
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (view !== active || url == "about:blank" || destroyed) return
                loadFailed = false
                started()
                captureCookies()
            }
            override fun onPageCommitVisible(view: WebView, url: String?) {
                if (view === active && !loadFailed && !destroyed) finished()
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (view === active && url != "about:blank" && !loadFailed && !destroyed) {
                    finished()
                    captureCookies()
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail(view, when (error.errorCode) {
                    ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT -> "Check your connection and retry Spotify login."
                    else -> "Spotify's page couldn't load. Retry, or update Android System WebView and Chrome."
                })
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) fail(view, when (errorResponse.statusCode) {
                    403 -> "Spotify couldn't open this sign-in page. Retry using your Spotify email."
                    429 -> "Spotify is limiting sign-in requests. Wait a moment, then retry."
                    else -> "Spotify couldn't open the login page (${errorResponse.statusCode}). Retry in a moment."
                })
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                fail(view, "A secure connection to Spotify couldn't be established. Check your connection and device date.")
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view === active && !destroyed) changed(newProgress.coerceIn(0, 100))
            }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                if (destroyed || popup != null) return false
                val child = createWebView()
                popup = child
                addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                child.bringToFront()
                child.requestFocus()
                loadFailed = false
                started()
                transport.webView = child
                resultMsg.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) { if (window === popup) closePopup(window) }
        }
    }

    private fun navigate(view: WebView, url: String): Boolean {
        if (SpotifyLoginPolicy.spotifyOrigin(url) != null && captureCookies()) return true
        if (url == "about:blank" || SpotifyLoginPolicy.canNavigate(url)) return false
        val fallback = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            .getStringExtra("browser_fallback_url") }.getOrNull()
        if (fallback != null && SpotifyLoginPolicy.canNavigate(fallback)) view.loadUrl(fallback)
        else if (!captureCookies()) fail(view, "This sign-in link couldn't open. Retry and choose your Spotify email.")
        return true
    }
    private fun captureCookies(force: Boolean = false): Boolean {
        if (destroyed || !canSubmit() || (submitted && !force)) return false
        val manager = CookieManager.getInstance()
        val origins = SpotifyLoginPolicy.cookieOrigins.toMutableSet()
        SpotifyLoginPolicy.spotifyOrigin(active.url)?.let(origins::add)
        val cookies = SpotifyLoginPolicy.cookies(origins.map { manager.getCookie(it).orEmpty() })
        val dc = cookies["sp_dc"] ?: return false
        submitted = true
        manager.flush()
        connected(dc, cookies["sp_key"].orEmpty())
        return true
    }
    private fun fail(view: WebView, message: String) {
        if (view !== active || destroyed) return
        loadFailed = true
        failed(message)
    }
    private fun closePopup(view: WebView) {
        popup = null
        removeView(view)
        view.destroyForLogin()
        loadFailed = false
        finished()
        captureCookies()
    }
    private fun WebView.destroyForLogin() {
        stopLoading()
        clearCache(true)
        clearHistory()
        (parent as? ViewGroup)?.removeView(this)
        destroy()
    }
}

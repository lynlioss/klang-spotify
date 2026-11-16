package io.github.lynlioss.klang.spotify

import java.net.URI

/** Browser navigation and cookie parsing are independent of Android's WebView callbacks. */
internal object SpotifyLoginPolicy {
    val cookieOrigins = listOf("https://open.spotify.com", "https://accounts.spotify.com", "https://spotify.com")

    // Match YumaPlayer's browser UA while retaining the installed WebView's Chrome version.
    fun browserUserAgent(default: String): String = default.replace("; wv", "")
        .replace(Regex("Version/\\d+\\.\\d+\\s?"), "")

    fun canNavigate(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.port in setOf(-1, 443)
    }.getOrDefault(false)

    fun spotifyOrigin(url: String?): String? = runCatching {
        val target = url ?: return null
        val uri = URI(target)
        val host = uri.host?.lowercase() ?: return null
        if (canNavigate(target) && (host == "spotify.com" || host.endsWith(".spotify.com"))) "https://$host" else null
    }.getOrNull()

    fun cookies(headers: Iterable<String>): Map<String, String> = buildMap {
        for (header in headers) for (part in header.split(';')) {
            val entry = part.trim()
            val separator = entry.indexOf('=')
            if (separator > 0) {
                val name = entry.substring(0, separator).trim()
                if (name in setOf("sp_dc", "sp_key")) {
                    val value = entry.substring(separator + 1).trim()
                    if (value.isNotEmpty()) put(name, value)
                }
            }
        }
    }
}

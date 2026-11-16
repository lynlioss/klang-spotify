package io.github.lynlioss.klang.spotify

import org.junit.Assert.*
import org.junit.Test

class SpotifyLoginPolicyTest {
    @Test fun browserAgentKeepsInstalledChromeVersionAndRemovesWebViewMarkers() {
        val webview = "Mozilla/5.0 (Linux; Android 16; Pixel 9; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/143.0.7499.53 Mobile Safari/537.36"
        val browser = SpotifyLoginPolicy.browserUserAgent(webview)
        assertFalse(browser.contains("; wv"))
        assertFalse(browser.contains("Version/4.0"))
        assertTrue(browser.contains("Chrome/143.0.7499.53 Mobile Safari/537.36"))
        assertEquals(browser, SpotifyLoginPolicy.browserUserAgent(browser))
    }
    @Test fun accountRedirectsAndIdentityProvidersStayInsideTheBrowser() {
        listOf("https://accounts.spotify.com/de/login?continue=https%3A%2F%2Fopen.spotify.com%2F",
            "https://open.spotify.com/", "https://accounts.google.com/o/oauth2/auth",
            "https://appleid.apple.com/auth/authorize", "https://www.facebook.com/login.php").forEach {
            assertTrue(it, SpotifyLoginPolicy.canNavigate(it))
        }
    }
    @Test fun rejectsNonWebSchemesCleartextAndCredentialsInRedirectUrls() {
        listOf("spotify:track:example", "intent://accounts.spotify.com/#Intent;scheme=https;end",
            "http://accounts.spotify.com/login", "https://user:secret@accounts.spotify.com/login",
            "file:///data/private", "javascript:alert(1)", "https://accounts.spotify.com:8443/login", "malformed url").forEach {
            assertFalse(it, SpotifyLoginPolicy.canNavigate(it))
        }
    }
    @Test fun credentialsAreReadOnlyFromRealSpotifyOrigins() {
        assertEquals("https://accounts.spotify.com", SpotifyLoginPolicy.spotifyOrigin("https://accounts.spotify.com/de/login"))
        assertEquals("https://open.spotify.com", SpotifyLoginPolicy.spotifyOrigin("https://open.spotify.com/?login=1"))
        listOf(null, "https://spotify.com.evil.example", "https://evilspotify.com", "https://accounts.google.com",
            "http://spotify.com", "https://user@spotify.com").forEach { assertNull(SpotifyLoginPolicy.spotifyOrigin(it)) }
    }
    @Test fun capturesCookiesAcrossSpotifyOriginsAndPreservesEqualsInValues() {
        val cookies = SpotifyLoginPolicy.cookies(listOf("sp_t=tracking; malformed; sp_dc=synthetic=value==; sp_key=key-one",
            "sp_dc=; sp_key=key-two; other=value"))
        assertEquals(mapOf("sp_dc" to "synthetic=value==", "sp_key" to "key-two"), cookies)
        assertTrue(SpotifyLoginPolicy.cookies(listOf("sp_dc=; sp_key=; tracking=only")).isEmpty())
    }
}

# Public Spotify API reference

This report uses Spotify developer documentation retrieved on 2026-11-16. [The source manifest](../evidence/official-sources.json) records URLs, timestamps, response status, and content hashes. These public contracts are separate from the Android internal routes in the previous report.

## OAuth

Authorization Code with PKCE is the documented flow for public clients that cannot keep a client secret. Generate a verifier and its S256 challenge, send the challenge and state to `https://accounts.spotify.com/authorize`, validate the callback state, then exchange the returned code at `https://accounts.spotify.com/api/token` with the verifier, client ID, grant type, and matching redirect URI. Refresh uses the same token endpoint with `grant_type=refresh_token` and the refresh token. A replacement refresh token, when present, must replace the old value.

Redirect URI documentation requires HTTPS except explicit loopback IP literals, for which HTTP is permitted. `localhost` is not allowed. Exact matching matters; loopback has documented dynamic-port handling. These rules should be checked against the actual developer app registration.

The Android SDK also documents package-name and signing-certificate fingerprint registration. App Remote authorization and public Web API authorization are related integrations with different responsibilities; do not assume a first-party stored credential is a public OAuth credential.

## Search

GET `https://api.spotify.com/v1/search` takes `q` and comma-separated `type` values. Documented types are album, artist, playlist, track, show, episode, and audiobook. Filters include artist, album, track, year, ISRC, UPC, genre, and supported tag filters. `market` is an ISO country code; a valid user token's account country takes precedence. Responses contain separate paging objects by content type.

The current reference lists default limit 5, maximum 10, and offset through 1000. Use parameter encoding once at the HTTP client layer. Search returns catalog/availability metadata, not a raw full-track audio stream. `include_external=audio` changes externally hosted content's playability representation; it does not grant rights or fetch media bytes.

## Catalog, library, and playlists

| Operation | Public route family | Notes |
| --- | --- | --- |
| Track/album/artist/show/episode/audiobook lookup | `/v1/{type}/{id}` | Availability and optional fields vary |
| Search | `/v1/search` | Current development-mode limits above |
| Current user | `/v1/me` | Country/email/product fields may be absent in development mode |
| User playlists | `/v1/me/playlists` | Scope and app access restrictions apply |
| Playlist metadata/items | `/v1/playlists/{id}`, `/items` | Current development mode restricts contents to owned/collaborative playlists |
| Library save/remove/check | `/v1/me/library`, `/contains` | Generic URI-based routes replace entity-specific writes/checks for development mode |
| Active playback/devices | `/v1/me/player`, `/devices` | Account scopes and playback eligibility apply |
| Start/resume/pause/seek/next/previous | `/v1/me/player/...` | Controls Spotify playback; does not return encoded audio |

The route table summarizes the documented platform, not a tested live API client. Read each endpoint's current scope and account requirements before implementation. Do not assume every historical route remains available to a newly registered app.

## 2026 development-mode changes

The February migration guide says restrictions applied to new development apps on February 11 and existing development apps on March 9, 2026. The app owner needs Premium. The current quota page permits five allowlisted authenticated users; older apps can retain grandfathered counts. The guide notes the client-ID limit changed again in July 2026, so the older one-client-ID rule is no longer current.

Development mode replaces content-specific library writes/checks with generic URI routes, renames playlist `/tracks` to `/items`, removes batch metadata retrieval, and removes several browse/other-user routes. Search drops from older limits to a maximum of 10. Profile fields including country/email/product and various catalog fields may be absent. Extended-quota apps have a different compatibility policy according to the migration guide.

Rate limiting and quota buckets can return 429. Respect Retry-After where supplied. A user can authorize successfully yet receive 403 if not allowlisted in development mode. Clients need to handle these account and quota constraints.

## Playback options

The Android App Remote SDK lets another app control the installed Spotify app; Spotify owns playback, caching, audio focus, and network traffic. The Web Playback SDK creates a browser Connect device and requires eligible Premium. Web API player routes control an active Spotify device. None of these documented integrations gives a general public raw-audio endpoint for a native external decoder.

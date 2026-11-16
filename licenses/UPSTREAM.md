# Reused source

YumaPlayer / ArchiveTune Spotify core: https://github.com/MuwMx/YumaPlayer, commit a9a39edd479a8eaf6279c7aac37cbdc97f9f28f9. GPL-3.0; original source headers retained. Vendored in spotify-core. Local changes are tracked by Git.

Spotify login browser: adapted from the same YumaPlayer revision's `SpotifyLoginSheet.kt` and `WebAuthSessionCleaner.kt`. The plugin reuses its full-size FrameLayout, clean Chrome user agent, popup lifecycle and cookie-origin approach, with Klang's native theme and loading/error handling.

Klang Music Import: https://github.com/lynlioss/music-import-klang, GPL-3.0. Metadata, recording matching, original-format selection and downloader helpers reused in plugin/src/main/java/app/lynli/klang/importing. Runtime dependency license/source notices are in music-import.

No Spotify APK code or binary is included in the plugin.

Spotify mark: reused from Klang, originally from Simple Icons (CC0). https://simpleicons.org/ and https://github.com/simple-icons/simple-icons/blob/develop/LICENSE.md.

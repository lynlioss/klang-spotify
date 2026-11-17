# Spotify Sync for Klang

An independently updated Android plugin for [Klang](https://github.com/lynlioss/klang). Spotify supplies your account, catalog and playlists; matching recordings stream from YouTube Music or your connected Qobuz account. The plugin handles service APIs, account storage, recording matching and audio downloads. Klang supplies playback, its Material 3 Expressive interface and a reusable Plugin API 1 music source host.

The implementation reuses [YumaPlayer's Spotify core](https://github.com/MuwMx/YumaPlayer) and Klang Music Import's recording and download engine. It does not contain Spotify's APK code, decrypt Spotify audio or act as an official Spotify client.

## Features

- Spotify search across songs, albums, artists and playlists; home recommendations, liked songs, saved albums, followed artists and playlist folders.
- Albums and artist releases, user playlists, playlist creation, names/descriptions, public/private settings, collaborative settings, cover images, following and copying.
- Add/remove/reorder playlist entries using Spotify's playlist UIDs, preserving repeated songs. Editing controls require ownership or explicit current-user permission.
- YouTube Music recording search and original audio formats; Qobuz matching by ISRC or recording identity and signed FLAC URLs using your account's app credentials.
- Downloads through Klang's existing queue, duplicate checks, notifications and MediaStore publication. Tiny Spotify/On Device badges and “Copy Spotify link” actions.
- Spotify login in the plugin's account settings, with AES-256-GCM/Tink and Android Keystore storage. Account credentials remain in the plugin.

Spotify Premium is not required by this plugin for YouTube playback. Qobuz playback depends on the connected account's access, region and matching app credentials. A matching recording may be unavailable; previews and low-confidence recordings are rejected. Changes to third-party web APIs can require a plugin update.

## Install

Use a Klang build containing the API 1 music source extension. Both APKs target Android 16 and newer; the plugin currently packages ARM64 only.

1. Open Klang → Settings → Plugins → Recommended plugins → **Spotify Sync** → Install.
2. Finish the Android APK installer, return to Klang and enable the plugin.
3. Open the plugin's detail page → **Accounts & playback settings** → **Connect Spotify**.
4. Your Spotify music appears in Klang’s Home, Search and Library pages. YouTube Music is the default; connect Qobuz in account settings to use lossless recordings, with optional YouTube fallback.

For Qobuz, enter your email/password and your app ID with its matching secret. Passwords are used to obtain a session token and are not saved. The app pair and token are encrypted; no shared account/token pool is included. Spotify browser cookies and local browser storage are cleared when the login screen closes; only the verified session is retained in the encrypted vault.

Account settings follow Klang’s Material 3 Expressive layout, with Spotify connection first and optional Qobuz settings in an expandable card. Spotify login uses a full-screen browser inside the plugin, including sign-in popups. The header follows Klang's theme, page loading shows progress, and connection failures offer Retry. After signing in, the plugin captures the Spotify session automatically; **Finish login** checks it again if needed.

Tap a song's download button to keep the matched recording on device. Downloads use the source's existing codec and apply Spotify catalog tags and artwork. Available offline songs play from Klang's MediaStore file, including after restarting Klang. The Spotify link remains available from the song menu.

The stable [GitHub release](https://github.com/lynlioss/klang-spotify/releases/latest) supplies `plugin.apk`, `plugin.json` and their SHA-256 files. Android verifies signing continuity when updating. CI artifacts are validation builds; install the signed release for normal use.

## Build and release

Builds, tests and lint run in [GitHub CI](https://github.com/lynlioss/klang-spotify/actions/workflows/checks.yml) on commits to `main`. CI uses Java 21 for Android 16 database regression tests; release builds and bytecode target Java 17. Android platform 37.2 and build tools 37.0.0 are required. The core is a separate JVM module.

```bash
python3 scripts/check-plugin.py
python3 scripts/test-import-packaging.py
./gradlew :spotify-core:test :plugin:testDebugUnitTest :plugin:lintRelease :plugin:assembleRelease --no-daemon
python3 scripts/verify-import-packaging.py plugin/build/outputs/apk/release/plugin-release.apk --mapping plugin/build/outputs/mapping/release/mapping.txt
```

The manual Release workflow requires passing Checks on the same `main` commit and repository secrets `PLUGIN_KEYSTORE_B64` and `PLUGIN_KEYSTORE_PASSWORD`. The alias is `klang-spotify`; preserve the private key for future updates. Release builds require that signing configuration. Update `version` in both manifests and `versionName`/`versionCode` before another release.

Playback uses a SQLite database of account-scoped Spotify-to-YouTube recording links. An automatic library scan walks every page of liked songs, saved albums and saved playlists, including followed playlists inside folders. Three catalog workers discover songs while four matching workers search YouTube Music concurrently, without a per-song delay. Library cursors, retries and distinct work claims survive restarts; scans refresh daily and after library changes. Queue songs take priority and background requests yield to playback. Candidate links are checked against full recording metadata/audio before playback, and validated links survive restarts for 30 days. Existing databases and the previous JSON cache migrate automatically. Signed audio URLs stay in memory.

Klang prepares visible songs plus the next ten and previous two queue songs in the actual shuffle/repeat order. Four readers fetch bounded 768 KiB audio prefixes concurrently for up to twelve nearby songs after current playback is ready. Received audio stays in a separate 192 MiB cache. Compact Material 3 Expressive settings show one storage total and linked/waiting progress, use the Spotify profile photo when available, and keep Qobuz disabled until its account setup is complete. Clear cache removes links and buffered audio while retaining downloads and account credentials. The host cache bridge requires an updated Klang build.

Matching device files take priority for Spotify songs, including inside provider playlists and restored queues. Search and library rows merge while Spotify IDs and playlist occurrences remain available for provider actions. Cached catalog pages render before background network refreshes, with more rows loaded on scroll. Artwork is reused by cover identity. Cold service requests still take time, and Klang shows its expressive loading indicator while preparing or buffering. Daily extractor updates remain outside playback. The APK uses Android’s default application icon; the Spotify mark remains in Klang’s plugin UI.

The tests exercise catalog metadata, playlist identity/permissions, matching, Qobuz signatures and preview rejection. CI checks release ZIP reflection and ARM64 runtime packaging. Authenticated Spotify/Qobuz login, playback, seeking, background playback and airplane-mode downloads still need device verification; passing compilation does not establish current service behavior. Spotify's collaborative/public/cover endpoints also depend on server permissions. This repository makes no claim of Spotify approval or Terms of Service compliance.

## Community plugins and research

See [API.md](API.md) for the generic **Plugin API 1** music source protocol, including catalog pages, accounts, mutations, temporary streams and downloads. The host extension retains API version 1 and accepts independently installed service plugins.

The original Spotify Android research is preserved under [docs](docs/README.md). APKs, raw decompilation and tool downloads remain ignored in `analysis/`; the plugin uses independently reused source described in [licenses/UPSTREAM.md](licenses/UPSTREAM.md).

## License

GPL-3.0. Upstream headers and dependency attribution are retained in [licenses](licenses/UPSTREAM.md). Source archives accompany published plugin releases.

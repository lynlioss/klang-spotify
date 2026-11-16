# Klang music source plugins — Plugin API 1

Music sources are separately installed Android APKs using `apiVersion: 1` and `type: "musicSource"`. API 1's lyric, import and native extension contracts continue to work. Service APIs, accounts, matching and downloading run under the plugin's UID. Klang renders native Material 3 Expressive catalog screens and owns playback, queues, caches and file publication.

## Manifest and discovery

Publish `plugin.json` and embed the same JSON as the service's `klang.plugin` raw resource. The exported service advertises `io.github.lynlioss.klang.MUSIC_SOURCE_V1`. Add:

```json
"musicSource": {
  "package": "com.example.music",
  "service": "com.example.music.SourceService",
  "settingsActivity": "com.example.music.AccountSettingsActivity",
  "badge": "music", "linkLabel": "Example",
  "capabilities": ["catalog.home", "catalog.search", "catalog.library", "catalog.lookup", "stream.resolve", "accounts"],
  "hosts": ["music.example.com"], "schemes": ["example"]
}
```

Package, service and optional settings activity must agree with the APK. `badge` is `spotify`, `music`, `cloud` or `device`; `linkLabel` labels link actions. Unknown capabilities are rejected. Account settings open from the plugin detail page. Optional `klang.theme` (`system`/`dark`/`light`) and `klang.dynamicColor` Intent extras let the activity match the host's appearance.

For `offline.download`, also declare `musicImport` with the same package/service and advertise `io.github.lynlioss.klang.MUSIC_IMPORT_V1`. Downloads reuse [Music Import API 1](https://github.com/lynlioss/music-import-klang/blob/main/API.md). The host packages no plugin APK or Python/FFmpeg runtime.

## Messenger envelope

Klang binds explicitly to the service. Each request uses `Message.what = 20`, a fresh opaque `Bundle["token"]`, a reply Messenger, and JSON in `Bundle["json"]`:

```json
{"operation":"search","args":{"query":"artist song","kind":"track","offset":0,"limit":50}}
```

The plugin checks `Message.sendingUid` against its supported host package and identifies work by `(uid, token)`. The host validates the installed plugin UID and request token on responses. Reply with `what = 10`, the token, and either bounded JSON or a `ParcelFileDescriptor` in `Bundle["metadata"]`. After sending the read descriptor, close its local copy and write UTF-8 JSON to the paired pipe. Metadata is limited to 16 MiB. `what = 12` returns a short user-readable failure in `json`; never return secrets or raw diagnostics.

`what = 3` with the same token cancels work. Cancel jobs on Binder death, close descriptors and terminate extractor processes. The host unbinds after completion. Catalog requests time out after 90 seconds; stream resolution after 330 seconds. This implementation accepts up to 12 active requests and 400,000 characters of request JSON.

## Catalog pages

`home`, `search`, `library` and `lookup` require `catalog.home`, `catalog.search`, `catalog.library` and `catalog.lookup`. Arguments include `offset` and `limit`; `search` adds `query`/`kind`, `library` adds `kind`, and `lookup` adds opaque `id`.

```json
{
  "title":"Playlist", "offset":0, "total":120, "nextOffset":50,
  "entity":{"id":"opaque-playlist","kind":"playlist","title":"Playlist","owned":true,"editable":true},
  "items":[{
    "id":"opaque-track", "kind":"track", "title":"Song", "artist":"Artist",
    "album":"Album", "albumArtist":"Album artist", "durationMs":210000,
    "url":"https://music.example.com/song", "artwork":"https://images.example.com/cover.jpg",
    "available":true, "saved":false, "uid":"playlist-entry-1",
    "albumId":"opaque-album", "artists":[{"id":"opaque-artist","name":"Artist"}]
  }]
}
```

Kinds are `track`, `album`, `artist`, `playlist` and `folder`. IDs are opaque strings up to 1024 characters; titles, artist and album strings up to 1000. Duration is milliseconds, bounded to one day. Pages contain at most 1000 entities. `nextOffset` is null when complete, otherwise greater than this page's `offset` and less than `total`. Empty pages may advance past unavailable entries. Klang fetches whole collections for playback/copy/download, bounded to 10,000 entries, preserving duplicate track positions. `uid` identifies each occurrence for playlist mutations.

Optional track fields include `isrc`, `releaseDate`, `trackNumber`, `discNumber`, `explicit`, `local` and service metadata. The host sends track JSON back as opaque playback/download input. Playlist fields include `description`, `public`, `collaborative`, `snapshot`, `owned` and `editable`. `editable` is a current-user permission, never inferred merely from membership or collaboration. Other fields include `subtitle`, `artwork`, `url`, `total`, `releaseDate` and `albumType`.

Klang creates stable `klang-source://<plugin-id>/<encoded-entity-id>` media identities and retains metadata separately from stream URLs. Bounded pages are cached for offline browsing. Offline mappings persist across restarts. Host-only offline content URIs are removed from metadata returned to plugins.

## Accounts

`accounts` requires the `accounts` capability and returns quickly without network access. Return a stable opaque `revision` that changes after login, switching accounts or logout; Klang clears cached pages when it changes. Optional fields include connection booleans and a display `profile`. Revisions must contain no token, cookie, password or email. `account.logout` clears the plugin-owned session. Offline files remain available.

## Mutations

| Operation | Capability | Arguments |
| --- | --- | --- |
| `library.save`, `library.remove` | `library.write` | `ids`: entity IDs |
| `playlist.create` | `playlist.create` | `name`, `description`, `public` |
| `playlist.edit` | `playlist.edit` | `id`, changed `name`/`description`/`public`/`collaborative` |
| `playlist.add` | `playlist.items` | `id`, ordered track `ids` |
| `playlist.remove` | `playlist.items` | `id`, `items`: `{id, uid}` occurrences |
| `playlist.move` | `playlist.items` | `id`, `uids`, `beforeUid`: occurrence or null for end |
| `playlist.follow`, `playlist.unfollow` | `playlist.follow` | `id`, optional `public` |
| `playlist.cover` | `playlist.cover` | `id`, `imageBase64`: JPEG ≤250 KB before encoding |

Creation returns `{"entity":{...}}`; other mutations return `{"ok":true}`. `playlist.collaboration` enables the collaborative control for owned playlists. The host guards duplicate submissions and batches additions into 100 IDs. The plugin rechecks editing rights and avoids replay after ambiguous failures. Large copies may partially complete if a later batch fails; refresh before retrying. Successful mutations invalidate cached pages.

## Temporary playback

`stream.resolve` requires that capability. Arguments contain `item`, the retained track JSON. Return:

```json
{"url":"https://audio.example.com/file?expires=1800000000","headers":{"User-Agent":"Example"},"expiresAt":1800000000000,"format":"FLAC","source":"example","recordingMetadata":{"id":"matched-recording"}}
```

URLs must be HTTPS with a host and no userinfo, within 16,384 characters. Up to 20 headers are allowed, without CR/LF, `Host`, `Content-Length` or `Connection`. Resolve on demand and cache only while valid. Account cookies stay in the plugin. This plugin exports only User-Agent/Referer/Origin when required for YouTube streams. Stream responses are transient and never persisted by the host.

Media3 resolves on its loader thread immediately before opening/seeking. Offline content takes precedence and can play with the plugin disabled or unavailable. Return a complete playable recording or a useful failure; never label previews as full streams.

## Downloads and distribution

`offline.download` reuses Music Import's `what = 1` (link preview), `2` (download to a host output descriptor), `3` (cancel), `10` (result), `11` (progress), and `12` (failure). Download input is retained track JSON. Return `extension`, final `song` metadata with the same opaque ID, `audioFormat`, and optional `recording` link. Permitted containers are FLAC, M4A, Opus, Ogg and MP3. Klang owns duplicate checks, scheduling, notifications, cancellation and atomic MediaStore publication; the plugin receives no filesystem paths.

Stable GitHub releases attach `plugin.json`, `plugin.json.sha256`, `plugin.apk` and `plugin.apk.sha256`. The release tag is `v<manifest-version>` and APK package/version must match. Preserve the signing key, plugin ID and repository URL across updates.

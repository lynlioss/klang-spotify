# Search and other internal APIs

## Extracted catalog

The [endpoint catalog](../evidence/api/endpoints.md) contains 750 request declarations from the completed per-DEX Java output. [The JSON catalog](../evidence/api/endpoints.json) retains each declaration, route, HTTP method, source class, line, and generated-source SHA-256. Duplicate routes can correspond to different interfaces or result types. The count is declarations, not unique deployed endpoints.

HTTP annotation names are obfuscated. The extraction maps them using the inspected Retrofit parser `p.we70` in `classes2.dex`: `x740` is GET, `ngr0` is POST, `pgr0` is PUT, `xzo` is DELETE, `rfr0` is PATCH, `o260` is HEAD, and `qwp0` is OPTIONS. This avoids guessing method semantics from an annotation's name.

The catalog covers catalog views, playlists, library, profile/social, podcasts, concerts, payment-related routes, recommendations, messaging, and other app modules. It is not a complete catalog of all native HTTP, Cosmos, WebSocket, or generated RPC requests. Native literal routes and services in recovered descriptors supplement it. Relative request paths need the base URL configured by the corresponding service factory.

## Android search interface

`p.jx51`, from the original `android-search-onlineproto` module, defines these routes.

| Method | Route | Response or operation |
| --- | --- | --- |
| GET | `searchview/v3/search` | `MainViewResponse`; RxJava and coroutine declarations |
| GET | `searchview/v3/native` | `NativeViewResponse` inside response wrapper |
| GET | `searchview/v3/autocomplete` | `AutocompleteViewResponse` inside response wrapper |
| POST | `searchview/v3/delete-recent-query-suggestion` | Completes removal request |

These declarations accept query maps and, for the read routes, dynamic header maps. They specify `Accept: application/protobuf`. The deletion route takes query parameters rather than an explicit message body in this interface. `p.m0b1` separately exposes autocomplete for the song-DNA module.

`p.mq51.A`, `p.uuq.r`, and `p.ytp.s` create the search interface through `RetrofitMaker.createWebgateService`. This links it to the app's authenticated Webgate request machinery. The base `spclient.wg.spotify.com` literal and the bearer/client-token interceptors establish the surrounding network stack; an annotated relative path alone does not prove the precise live host selected by discovery/configuration.

## Pagination and response structure

The `p.hc11.P` search wrapper explicitly reads `page_token`. A first-page branch checks it against an empty string and can use a `cw51` wrapper; subsequent branches select a search service according to a configured value. `MainViewResponse` has hits at field 1, recommendations at 2, next-page token at 4, banner content at 5, flat filters at 10, and filter catalog at 12.

This proves token-based pagination in the Android search path. It does not justify substituting the public Web API's `offset` and `limit` contract for this private route. The complete parameter map is assembled elsewhere in the obfuscated app and remains a target for deeper tracing.

Search result messages include albums, artists, playlists, tracks, shows, episodes, audiobooks, concerts, profiles, genres, snippets, filters, related searches, and gating decisions. Rich response structure supports a personalized screen contract rather than just a list of track metadata.

## Offline search

The recovered `search_service.proto` defines `OfflineSearchService.Query`. Its request has a query string, limit, and include-chapters flag. Its response contains offline albums, audio episodes, playlists, and tracks. This is an internal Esperanto service, not evidence of a network endpoint. Offline result schemas include URI, title, image URI, artist/show information, explicit flags, and premium-only flags where applicable.

## Other confirmed routes

| Route | Evidence | Role |
| --- | --- | --- |
| `pitoken/spotify.pitoken.v1.VerifyService/Verify` | `p.dmt0`, POST protobuf | Play Integrity verification |
| `user-geo-points/v1/location` | `p.xwi1`, POST protobuf | Location update |
| `user-geo-points/v1/location/consent` | `p.wwi1`, GET | Read backend consent |
| `.../consent/grant`, `.../consent/revoke` | `p.wwi1`, POST protobuf | Change backend consent |
| `storage-resolve/v2/files/audio/` | Native literal and recovered response | Resolve audio storage/CDN location |
| `sessiontransfer/v1/token` | Native literal cluster | Session handoff token |
| `https://clienttoken.spotify.com/v1/clienttoken` | Native literal and full descriptor | Client-token acquisition |
| `https://login5.spotify.com/v3/login`, `/v4/login` | Native literals and type metadata | First-party authentication |
| Widevine audio/video license routes | DEX literals and media DRM code | Protected media licensing |

Native route entries do not have a confirmed HTTP method unless separately traced. No request examples with copied first-party credentials, signed claims, or content-decryption keys are provided. The repository documents the observed contracts and remaining gaps.

## Evidence links

Evidence: [HTTP annotation parser](../evidence/source/normal__p_we70.java.txt), [Android search interface](../evidence/source/normal__p_jx51.java.txt), [pagination wrapper](../evidence/source/jadx-dex11__p__hc11.java.txt), [main search response](../evidence/source/jadx-dex11__com__spotify__searchview__proto__MainViewResponse.java.txt), and [offline search service](../evidence/native-protos/search_service.proto).

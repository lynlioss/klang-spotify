# Streaming and data delivery

## Metadata and file selection

The Android app uses native player/connectivity code through JNI and Esperanto transports. The recovered metadata schema identifies track and episode audio files by binary file IDs and format enums. Formats include Ogg Vorbis 96/160/320, MP3 variants, AAC 24/48, xHE-AAC, FLAC, 24-bit FLAC, and additional PHONO/PETREL values.

`AudioFilesExtensionResponse` contains repeated extended files, each with a metadata `AudioFile` and average bitrate, plus track/album normalization parameters and an audio ID. Native logs refer to Vorbis decoder setup, FLAC decoder states, and audio file selection. These support selectable encodings and normalization. They do not prove every format is enabled for every account or device.

## Storage resolution

The native literal `storage-resolve/v2/files/audio/` occurs at file offset `0x3955f9`. The recovered `StorageResolveResponse` has `result` at field 1, repeated `cdnurl` strings at field 2, and binary `fileid` at field 4. Result values are CDN=0, STORAGE=1, and RESTRICTED=3.

This is direct schema evidence that resolution can return CDN locations or a restricted outcome. The exact complete request path, query parameters, and signing/authorization inputs are not established by the route prefix alone. No CDN URL was obtained or tested in this analysis.

## Receiving streaming bytes

Native strings include range-reply errors, `download_range_not_satisfiable`, `download_http_no_range_response_no_content_length`, range-size fields, and prefetch/download messages. Together with the CDN response schema, these support HTTP file/range delivery with validation of server response semantics. The app has a prefetch subsystem and logs cancellation of previous head-file downloads and failures to save a key.

A supported sequence is metadata/file selection, storage resolution, byte delivery, buffering/prefetch, and decoding. The pass does not establish fixed chunk sizes, buffer duration, a universal CDN host, cache filename layout, or a precise seek-to-byte formula. Those values must come from the matching native functions or an authorized runtime capture.

## Streaming versus control traffic

The native library contains `wss://dealer.g2.spotify.com:443` at offset `0x393317`, Dealer message parsing, socket/Dealer transitions, and Connect state schemas. Those describe a control/notification channel. They do not identify the WebSocket as the transport for encoded music bytes.

`https://apresolve.spotify.com/v2` appears at `0x396d5a` and supports access-point discovery. The build also contains HLS master/audio playlist templates under `spclient.wg.spotify.com/sneaktables/v2/hls/4/...`. HLS presence establishes another media path in the binary; it does not make all Spotify music streams HLS.

## Encryption and DRM

Native strings refer to sample/video decryption, decryptor readiness, and missing keys. The DEX contains Widevine audio and video license endpoints. The video/offline subsystem contains MediaDrm/ExoPlayer license handling, key-set IDs, offline license expiry, and errors for missing, expired, or failed licenses.

This pass does not prove a single encryption scheme applies to every audio format, nor that the traditional music path always uses Widevine. It does not recover usable content keys. A private CDN URL or a file ID is not evidence of independently playable, unprotected audio.

## Offline behavior

Recovered offline messages represent download sessions, per-context progress, device quotas, free/max licenses, resource preparation, offline-playback permission, and expiry information. The DEX includes `OfflineLicenseInfoEntity` storing media ID and key-set ID. License preparation and renewal errors are explicit.

This supports managed downloads with session and license bookkeeping. It does not establish the current account quota, expiration duration, downloaded media container, or offline revalidation interval. Those values are response/configuration dependent.

## What the public platform exposes

The Web API supplies catalog metadata and playback commands. The Android App Remote SDK delegates playback, caching, and network traffic to the installed Spotify app. The Web Playback SDK creates a browser player. None of those facts establishes a public raw-audio endpoint suitable for Klang's native local-file player. See [the public API report](08-public-api.md).

## Evidence links

Evidence: [audio metadata](../evidence/native-protos/metadata.proto), [extended audio files](../evidence/native-protos/audio_files_extension.proto), [storage resolution response](../evidence/native-protos/storage-resolve.proto), [streaming literals](../evidence/native/orbit-streaming-strings.txt), [crypto literals](../evidence/native/orbit-crypto-strings.txt), and [offline state schema](../evidence/native-protos/offline.proto).

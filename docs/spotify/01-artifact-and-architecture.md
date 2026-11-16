# Artifact and architecture

## Target identity

The user supplied the APK set through the URL recorded in [the container inventory](../evidence/spotify-container.json). The container has SHA-256 `14fa0de9c6eebdaa623ab7ff6e1963671a6751323a98757af02ccfee7c9745dd` and is 60,159,314 bytes. It contains four APKs.

| APK | Bytes | Role |
| --- | ---: | --- |
| `base.apk` | 101,256,400 | Manifest, 15 DEX files, core resources and assets |
| `split_config.arm64_v8a.apk` | 34,145,588 | Twelve ARM64 shared libraries |
| `split_config.en.apk` | 1,069,465 | English resources |
| `split_config.xxhdpi.apk` | 545,870 | Density-specific resources |

[Badging](../evidence/spotify-badging.txt) identifies `com.spotify.music`, version `9.2.0.2053`, version code `148387875`, minimum API 24, and target/compile API 37. The base has 4,092 ZIP entries. The archive inventory records every file's size and hash.

All four APKs pass `apksigner verify`. Their certificate results are retained individually. The base passes v2 and v3 signing and source-stamp verification. A certificate subject naming Spotify is not, by itself, independent proof of provenance. This analysis did not compare the fingerprint against a second independently downloaded Spotify release.

## Code distribution

The DEX tables contain 108,190 class definitions and 530,572 string-table entries. Counts include framework and third-party dependencies, generated protobufs, and obfuscated application classes. They do not mean every class executes in a session.

Java names preserve useful package boundaries for authentication, connectivity, player bridges, search schemas, location, offline handling, and video playback. Much implementation code is renamed into `p.*`. Kotlin metadata often retains the original source module and parameter names. Generated protobuf field constants preserve message field numbers even when accessor names are obfuscated.

`liborbit-jni-spotify.so` is 25,749,664 bytes. It contains the native connectivity, Login5, token exchange, metadata, player, download, offline, and Connect code. Java `Native*` classes expose native creation/destruction and transports. `NativeLogin5SetupImpl.create` accepts a Login5 configuration, timer manager, and native application scope. `PrefsCredentialsStorage.create` returns native credential storage. These declarations identify a Java/native boundary rather than a Java-only network implementation.

The other libraries include C++ runtime, Crashlytics, ComScore, graphics and image processing, Rive, and Ravelin's root checker. [The native inventory](../evidence/spotify-native-inventory.json) records hashes and disassembler results for all twelve.

## Evidence rules

An inspected method establishes that the build contains that branch. A protobuf schema establishes representable fields, not that every field is populated. A native string establishes a literal in the binary, not that its path is active. Public developer documentation describes Spotify's external platform and cannot fill gaps in the Android app's private protocol.

Reports use these distinctions throughout. [Reviewed source excerpts](../evidence/source-index.json), [native string excerpts](../evidence/native/orbit-auth-strings.txt), and [candidate native instruction references](../evidence/native/orbit-endpoint-xrefs.json) retain traceable evidence. The native reference scanner checks ADRP/ADD instruction pairs and does not establish complete data flow. Disassembler labels can name the nearest surviving symbol rather than the true function, especially in stripped binaries. No runtime session, account, network capture, feature-flag fetch, or server-side implementation was available. Build-specific conclusions should not be generalized to older or newer releases without comparison.

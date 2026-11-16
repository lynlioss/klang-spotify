# klang-spotify

Static analysis of Spotify for Android 9.2.0.2053. This repository contains research, evidence, and reproducible analysis tools, kept separate from Klang.

The analyzed set contains `com.spotify.music`, version code `148387875`, a base APK and ARM64, English, and xxhdpi splits. All four APK signatures verify. The base signer certificate has SHA-256 `6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd`. Verification establishes consistency with that certificate; its identity was not compared with a separately obtained release.

Start with [the report index](docs/README.md). Reports cover sign-in, sessions, country and location, streaming delivery, internal search and other APIs, app authenticity, and defensive mechanisms. Claims distinguish inspected code, recovered message schemas, native strings, and public documentation.

The complete local decompilation is in `analysis/spotify/`. APKs, raw decompiled code, tool downloads, and large disassemblies are ignored by Git. The repository retains analysis scripts, reports, source excerpts with hashes, endpoint declarations, and recovered protobuf descriptors. The [decompilation report](docs/decompilation/README.md) explains output coverage and reproduction.

No Spotify account was used, the application was not installed or executed, and no authenticated Spotify request or runtime traffic capture was performed. Static analysis cannot establish current server behavior or the active values of remote configuration.

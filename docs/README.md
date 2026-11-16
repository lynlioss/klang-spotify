# Spotify analysis reports

The target is Spotify for Android `9.2.0.2053`, inspected on 2026-11-16.

| Report | Contents |
| --- | --- |
| [Artifact and architecture](spotify/01-artifact-and-architecture.md) | Identity, splits, signatures, Java/native boundaries, evidence rules |
| [Sign-in and authentication](spotify/02-sign-in.md) | Login5, credential variants, challenges, OAuth, callbacks |
| [Sessions and token lifecycle](spotify/03-sessions.md) | Stored credentials, bearer and client tokens, retries, logout, session transfer |
| [Country and location](spotify/04-country-and-location.md) | Catalog restrictions, phone country, consent, coordinates, uncertainty about IP/VPN checks |
| [Streaming and delivery](spotify/05-streaming.md) | Metadata, file selection, storage resolution, CDN/range delivery, decoding, offline and DRM paths |
| [Search and other internal APIs](spotify/06-internal-apis.md) | Android search routes, headers, pagination, broad extracted endpoint catalog |
| [Authenticity and defenses](spotify/07-authenticity-and-defenses.md) | Signing, source stamp, TLS configuration, Play Integrity, client challenges, root checks |
| [Public API reference](spotify/08-public-api.md) | Documented OAuth and catalog/playback APIs, current development-mode changes |
| [Open questions and runtime plan](spotify/09-open-questions.md) | What remains unproven and how to verify it |
| [Decompilation and reproduction](decompilation/README.md) | Tools, coverage, local output paths, limitations |
| [Attachment correction](supplied-apk/README.md) | Original Uptodown attachment and empty Catbox download |

Evidence paths are relative to the repository. `evidence/source-index.json` maps reviewed classes to exact DEX files, original generated source paths, SHA-256 hashes, and retained excerpts. `evidence/native-protos/index.json` maps native schemas to binary offsets. Native string offsets are hexadecimal file offsets, not instruction addresses. Java fallback output preserves instructions and registers; it is not compilable Java source.

# Attachment correction

The first attachment was named `uptodown-com.spotify.music.apk` but its contents identify `com.uptodown`, Uptodown App Store 7.45, version code 745. It is 15,798,356 bytes and has SHA-256 `918aae7b5e38647d933d50e90525f29512d393a91e03ce8a494d58ba530b21a5`.

The signer subject is Uptodown Technologies SL. Its certificate SHA-256 is `822b9ca12b534ebcf426632221d951bfc60eb08f9f0cf2839c321b0685c2e8a4`. The archive contains four DEX files and Uptodown native libraries. Badging, certificate results, and archive hashes are retained under `docs/evidence/supplied-*`. Initial partial decoding is retained locally under `analysis/apktool` and `analysis/jadx`; it was stopped when the actual Spotify artifact arrived.

The first replacement URL, `https://files.catbox.moe/3jo3f5.apks`, returned HTTP 200 with `Content-Length: 0` and zero bytes through two HTTP clients. A later user-provided UploadThing URL supplied the valid Spotify set recorded in `spotify-container.json`.

No behavior from the Uptodown attachment is attributed to Spotify in these reports.

# Open questions and next analysis steps

The repository contains a completed static decompilation pass. It does not contain a runtime Spotify session. The following questions remain material.

| Question | Current evidence | Next observation needed |
| --- | --- | --- |
| Which Login5 protocol is active? | Constructor defaults to v3; both v3/v4 endpoints exist | Actual initialized configuration and request trace |
| Complete first-party login wire schema? | Java Esperanto fields and native compact v3/v4 metadata | Native serializer reconstruction and independent field validation |
| How are saved credentials encrypted? | Native preferences factory and encrypted auth-blob schema | Native storage/crypto call chain and app-private serialization |
| How are integrity verdicts enforced? | Verify request and validation configuration | Runtime verdict handling and visible denial behavior |
| Which client challenges does Android receive? | Full shared challenge schema | Authorized client-token response observation |
| How does the app select audio formats? | Metadata enums, bitrate and normalization fields | Product state, quality settings, device configuration and selection call chain |
| Exact music encryption and key flow? | Decryption/key messages, license paths | Native key/decoder call chain per media format |
| What are CDN chunk/cache/seek policies? | CDN response and range/prefetch evidence | Request headers, timing and cache behavior during authorized playback |
| Is GeoIP used for music as well as spoken/kids flows? | Episode/show option and kids mismatch types | Runtime content/product-specific checks |
| Does VPN affect access? | Android transport constants | Policy branch and server response evidence |
| Complete internal search parameters? | Query/header maps, page-token behavior and response fields | Follow parameter-map construction and authorized search requests |
| Is there API certificate pinning? | No pin-set in XML; native trust-related literals | Java/native TLS implementation and certificate-validation call chain |

An authorized runtime plan should use a dedicated test device and the user's own account. Record version, installed splits, account product, consent choices, and remote configuration before interpreting a result. Keep passwords, bearer tokens, refresh tokens, one-time codes, integrity tokens, and media keys out of logs and reports. Start with application logs, OS network metadata, observable behavior, and interfaces exposed by the app. Do not infer server internals from a single response.

Compare cold launch, restored session, explicit logout, search pagination, market-restricted content, network change, and online/offline playback separately. For each result, record the trigger, observed client branch, response category, and uncertainty. Repeating a behavior across builds/accounts is necessary before describing it as a general Spotify rule.

The static pass requires no further credentials. Runtime analysis would need a device/emulator and a user-owned Spotify test account. If a secret is needed later, request it through the private secret-entry tool rather than chat.

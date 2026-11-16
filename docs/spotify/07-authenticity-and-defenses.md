# App authenticity and defensive mechanisms

## APK signature and provenance

The base and three splits all pass signature verification. The base signer subject names Spotify in Stockholm. Its certificate SHA-256 is `6505b181933344f93893d586e399b94616183f04349cb572a9e81a3335e28ffd`; SHA-1 is `d6a6dced4a85f24204bf9505ccc1fce114cadb32`. The base verifies with APK signing schemes v2/v3 and a Google source stamp. The results are retained in `docs/evidence/*-signature.txt`.

APK signatures bind package contents to the signer and let Android enforce signing continuity for ordinary updates. Source-stamp verification is an additional packaging/provenance signal. This analysis did not obtain an independent known-good release to verify the signer identity, nor does a valid APK signature prove a current server trusts a runtime session.

## Manifest and transport configuration

The manifest has `debuggable=false`, `allowBackup=false`, signature-protected internal broadcast permissions, and explicit exported components. Its network-security configuration permits cleartext in the base configuration but denies it for a list of domains, including Spotify, Spotify.net, Spotifyinternal.com, and scdn.co plus their subdomains.

The XML has no pin-set declaration. That establishes no pin set in this configuration file; it does not establish that native or Java code has no pinning or custom certificate verification. Native cast/device certificate error strings exist and should not be generalized to every API connection. The ordinary platform TLS trust path and any native trust implementation need separate tracing.

## Native binary hardening

The ARM64 Orbit library has ELF load segments, GNU_RELRO, BIND_NOW/NOW dynamic flags, a non-executable GNU_STACK entry, and stack-check imports. These are build-hardening indicators. They do not replace account authorization, DRM, or server-side integrity evaluation. All twelve native libraries have metadata and full instruction disassembly in local analysis output.

## Client-token challenges

The recovered client-token HTTP schema carries client version and ID, current token, device ID, Android OS/API/model/brand/manufacturer, display metrics, installer name, and optional extended/derived device data. The response can grant a token with domain and expiration limits or issue challenges with an opaque state.

Challenge enums include client-secret HMAC, evaluated JavaScript, and hashcash. Challenge parameter and answer messages are fully retained in the recovered descriptors. Presence in the shared schema does not prove Android receives every challenge type. The backend's challenge selection and rejection policy remain unknown.

The Java client-token interceptor recognizes missing, invalid, and expired client tokens. These support a distinct client-validation layer alongside user bearer authentication. A successful client token does not by itself establish account entitlement to any content.

## Play Integrity

The app includes Google's Standard Integrity client code and Spotify's verification endpoint. `VerifyRequest` has token field 1, correlation ID field 2, and package name field 3. `p.mts0.n` builds that request and submits it through `p.dmt0`. The relative POST route is `pitoken/spotify.pitoken.v1.VerifyService/Verify`, with protobuf content/accept headers and identity content encoding. `VerifyResponse` has a response string at field 1.

Remote configuration has `android-integrity:employee_validation_enabled`, `...:time_tracking_enabled`, and `...:validation_enabled`. In the inspected configuration factory `p.el4`, employee/time-tracking defaults are false and validation defaults to true. Overrides can alter these values. The Google SDK's bundled error tables describe Play Store/service/API failures; they are library support, not proof every failure blocks Spotify playback.

An opaque integrity token can convey platform assessment to a server. This APK does not reveal the backend's complete verdict policy, token binding rules, replay window, or whether enforcement differs across product flows. Those details cannot be inferred from the three request fields.

## Root and fraud signals

`librootChecker.so` exports Ravelin JNI entry points for root checks, debug logging, and Magisk detection. Its strings include `/proc/self/mounts`, `magisk`, `core/img`, and `core/mirror`. ARM64 disassembly shows filesystem and string-search calls in those functions. The Java `RootCheckerNative` loads the library and exposes a path-array root check. The inspected wrapper does not expose every exported native method, so an exported symbol alone is not proof of invocation.

The inspected `p.cun1` Java implementation checks known root-management/dangerous packages, binary existence, system properties, mount data, build tags, and native results. Its package name is obfuscated, but the native binding identifies Ravelin. Payment/commerce-safety modules also contain fraud-data collection types. These are evidence of risk-signal collection. They do not prove rooted devices are universally denied music playback.

## reCAPTCHA and account policy

First-party authentication has reCAPTCHA input fields and the `mobile_client/authentication` action. Login5 types contain code, interaction, and hashcash challenges and retry/error categories. Metadata/offline schemas contain subscription/catalogue restrictions, restricted storage outcomes, and license state. These operate at different stages and should not be collapsed into a single authenticity check.

No protection was disabled, token forged, attestation replayed, or DRM bypassed. The remaining task is observation and call tracing of the existing mechanisms, not a claim that static evidence supplies a working replacement client.

## Evidence links

Evidence: [base signature verification](../evidence/base-signature.txt), [manifest](../evidence/Spotify-AndroidManifest.xml), [network policy](../evidence/network_security_config.xml), [client-token challenges](../evidence/native-protos/spotify__client_token__v0__client_token_http.proto), [integrity interface](../evidence/source/normal__p_dmt0.java.txt), [integrity configuration](../evidence/source/normal__p_el4.java.txt), [Java root checks](../evidence/source/normal__p_cun1.java.txt), and [native root-check disassembly](../evidence/native/librootChecker.so.disassembly.txt).

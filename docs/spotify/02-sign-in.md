# Sign-in and authentication

## Login5 boundary

The primary DEX contains `Login5Configuration` with mutable `clientId`, `deviceId`, and `protocolVersion`. Its constructor defaults the protocol to 3. The native library contains both `https://login5.spotify.com/v3/login` at file offset `0x3bfcae` and `https://login5.spotify.com/v4/login` at `0x3bfcf8`. Nearby content-type literals are `application/x-protobuf`.

This supports both protocol implementations in the build. The constructor default does not establish which version a configured production session uses. The Java factory passes configuration into native Login5 code through `NativeLogin5SetupImpl.create`; the wire request itself is implemented in the native library.

## Credential variants

`EsAuthenticateCredentials.AuthenticateCredentials` is an internal Esperanto message. Its field constants identify these credential alternatives. Exact generated declarations are retained in [the field catalog](../evidence/authentication-fields.json).

| Field | Variant | Meaning visible in schema/code |
| ---: | --- | --- |
| 1 | Password | A hint string used by the higher-level flow |
| 2 | One-time token | Token string |
| 3 | Phone number | Number, ISO country code, country calling code |
| 4 | Unencrypted stored credentials | Username and reusable credential bytes |
| 5 | Samsung sign-in | Authorization code, redirect URI, token endpoint URL |
| 6 | Parent/child credentials | Child ID plus parent stored credentials |
| 7 | Google sign-in | Authorization code, redirect URI, ID token |
| 8 | Apple sign-in | Authorization code, redirect URI, bundle ID |
| 9 | Facebook token | Facebook UID, access token, ID token |
| 10 | Legacy v3 password | ID and password |
| 11 | Native password | ID and password fields |
| 12 | Naver sign-in | Authorization code and redirect URI |
| 13 | Email address | Email identifier |
| 14 | Authn platform credential | Separate authentication-platform branch |

Treat provider presence as support in this build, not a guarantee that all providers appear in every country's UI. See retained credential field tables for exact constants.

These are internal Java/native messages. Their field numbers must not be assumed to match Login5's external wire messages. Native strings separately identify `spotify.login5.v3.*` and `spotify.login5.v4.*` classes and fields.

## Request and result model

`EsAuthenticateRequest.AuthenticateRequest` has credentials at field 1, interaction callback URI at 2, UI locale at 3, reCAPTCHA information at 4, and flow ID at 5. This means the app can carry flow identity and challenge context alongside the user's chosen credential.

`EsAuthenticateResult.AuthenticateResult` represents success at field 1, failure at 2, code required at 3, signup required at 4, interaction required at 5, and authn platform required at 6. Success carries username at field 1, access token at 2, and auth blob at 3. Code-required types include proceed and resend request/result variants. Interaction-required and authn-platform-required branches also have proceed messages.

The state progression supported by these schemas is credential submission, success or a required interaction, continuation, then success or failure. The existence of challenge types does not show when the backend selects each challenge.

## Challenges

The primary DEX contains `RecaptchaAction(action=mobile_client/authentication)`. Authentication requests can include `EsRecaptchaInfo.RecaptchaInfo`. Native field strings show Login5 v4 reCAPTCHA site-key/token data and code challenge/solution messages. Native v3/v4 type-name strings include hashcash challenge and solution types.

The native binary also lists errors for invalid credentials, invalid arguments, too many attempts, try again later, timeout, unsupported login protocol, and unsupported authentication method. Those literals describe failure categories; exact backend thresholds and lockout duration are unknown.

## Browser callbacks and OAuth

The manifest declares verified-link filters under `auth-callback.spotify.com`, including Android music login/signup paths. The primary DEX contains `https://auth-callback.spotify.com/r/android/music/login`. Native literals include `https://accounts.spotify.com/oauth2/v2/auth`, `https://login.app.spotify.com/`, and token exchange at `https://accounts.spotify.com/api/token`.

The callback URI and HTTP route strings do not establish the full CSRF/state or PKCE implementation for first-party login. Those controls require tracing the corresponding callback handlers and native request builders or observing an authorized runtime session. The external OAuth PKCE flow is documented separately in [the public API report](08-public-api.md).

## Limits of this pass

No password, token, client secret, or logged-in session was collected. The native scanner recovered many full descriptors, but the Login5 v3/v4 messages in this binary also use compact type/field metadata that the FileDescriptorProto scanner does not reconstruct. The report therefore preserves Java Esperanto field tables and native offsets without presenting an invented complete Login5 wire schema.

## Evidence links

Evidence: [Login5 configuration](../evidence/source/jadx-primary__com__spotify__authentication__login5__Login5Configuration.java.txt), [native setup bridge](../evidence/source/jadx-primary__com__spotify__authentication__login5setupimpl__NativeLogin5SetupImpl.java.txt), [authentication field catalog](../evidence/authentication-fields.json), and [native authentication literals](../evidence/native/orbit-auth-strings.txt).

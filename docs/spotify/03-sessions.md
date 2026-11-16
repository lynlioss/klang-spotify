# Sessions and token lifecycle

## Distinct authentication objects

The app contains at least three separate kinds of authentication data: a user access token, reusable stored credentials/auth blobs, and a client token. The user bearer token identifies a logged-in account to protected requests. Stored credentials support reauthentication. The client token is acquired from the client-token service and represents client/device context. The same request can carry both bearer and client-token headers.

## Stored credentials

`PrefsCredentialsStorage.create(Prefs, String)` returns `NativeCredentialsStorage`. The native library contains `autologin.username`, `autologin.saved_credentials`, and stored-credential change messages. Recovered schemas define `EncryptedStoredCredentials` with username field 1, auth blob bytes at 2, and device ID at 3. `UnencryptedStoredCredentials` has username at 1 and stored-credential bytes at 2. `AuthBlob` can contain these forms.

The names do not prove a particular encryption algorithm or Android Keystore policy. Neither a plaintext-storage claim nor an AES/Keystore claim follows from these schemas alone. The cryptographic implementation and actual preference serialization need deeper native call tracing. Android backup is disabled in the inspected manifest, which limits ordinary backup exposure but does not describe the auth blob format.

## Bearer token attachment and retries

The inspected `p.v7c` interceptor checks request metadata and HTTPS host membership. Its explicit hosts include `spclient.wg.spotify.com`, `exp.wg.spotify.com`, `wgint.spotify.net`, `image-upload.spotify.com`, `api.spotify.com`, `api-partner.spotify.com`, and `partnerapi.spotify.net`. A tagged request may use another `.spotify.com` host. It does not overwrite a nonempty existing `Authorization` header.

The interceptor asks the token provider for an access token with a 10,000 ms timeout, then adds `Authorization: Bearer <token>`. On a 401, it closes the response body and retries once when the response has no `client-token-error` header. A retry flag prevents recursion beyond that retry. A client-token error goes through its separate handling path.

Native logs include `Token not cached, or expired`, `Loading token from network`, successful authentication, stored-credential change, and failed-authentication messages. These support caching and refresh behavior but do not establish a fixed access-token lifetime. Lifetimes should come from the actual token response, not a hardcoded assumption.

## Client-token lifecycle

The recovered HTTP client-token schema includes a granted token, expiration in seconds, refresh-after seconds, and a list of permitted domains. Its serialization schema stores absolute expiration and refresh timestamps plus domains. The HTTP endpoint is `https://clienttoken.spotify.com/v1/clienttoken` at native file offset `0x3bf698`.

`p.mog` attaches client-token data through a provider. A 401 with `client-token-error: EXPIRED_CLIENTTOKEN` closes the response, invalidates the cached token, obtains another token, and retries. The handler also recognizes `MISSING_CLIENTTOKEN`, `INVALID_CLIENTTOKEN`, and related errors on 400/401/403 paths. Failures to acquire a token can become a synthetic 503 response.

Client-token expiration is separate from the user access token. A general 401 cannot safely be interpreted as a user logout without inspecting the error category. The schemas show expiration and refresh policy supplied by the service; the policy's live values were not measured.

## Logout and transfer

Native literals include `/api/logout/v1`, `X-Request-Id`, and messages about removal of stored credentials during logout and account shutdown. Java types expose authenticated scope construction/destruction. These indicate lifecycle cleanup but do not prove server-wide revocation of every device session.

`sessiontransfer/v1/token` appears at offset `0x3c0080`, followed by JSON content type, no-store cache policy, token/expiry fields, and an OTT login URL under `accounts.spotify.com/login/ott/v2#token=`. The binary also contains device-authorization URLs. These identify session handoff mechanisms without establishing token usability outside their intended transfer flow.

No live logout, account switching, token rotation, concurrent refresh, or session transfer was executed. The reports retain implementation evidence and leave server invalidation rules open.

## Evidence links

Evidence: [bearer interceptor](../evidence/source/normal__p_v7c.java.txt), [client-token interceptor](../evidence/source/normal__p_mog.java.txt), [credential storage bridge](../evidence/source/normal__com_spotify_connectivity_connectivitysdkcredentialsstorage_PrefsCredentialsStorage.java.txt), [client-token contract](../evidence/native-protos/spotify__client_token__v0__client_token_http.proto), [token serialization](../evidence/native-protos/client_token.proto), and [auth-blob schema](../evidence/native-protos/es_auth_blob.proto).

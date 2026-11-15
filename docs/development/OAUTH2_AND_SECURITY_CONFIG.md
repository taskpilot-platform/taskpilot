# OAuth2 Request State & Security Config (Task P1.1)

## OAuth2 Authorization Request Store (`V43`)
- Client cookies no longer carry Java-serialized `OAuth2AuthorizationRequest` payloads.
- Cookies are opaque per-flow entries (`oauth2_req_<8-hex-state-hash>=<handle>.<browserNonce>`) scoped to `/login/oauth2/code` (`HttpOnly`, `SameSite=Lax`, `Secure` controlled by `application.security.oauth2.cookie-secure`). Legacy root-path `oauth2_auth_request` cookies are cleared on load/save/remove without deserialization.
- Per-browser concurrent pending tab cookies are bounded (`application.security.oauth2.max-pending-cookies-per-browser`, default `5`).
- Pending requests are persisted in PostgreSQL `public.oauth2_authorization_requests` (`V43__oauth2_authorization_requests.sql`) using SHA-256 hashes of `handle`, `browserNonce`, and `state`, bound to `provider`, `redirect_uri`, `purpose` (`LOGIN` / `LINK`), and optional `target_user_id`.
- PKCE (`code_verifier`, `code_challenge`, `code_challenge_method`) and OIDC `nonce` / attributes are stored in a bounded structured JSON allowlist payload.
- Default TTL is `300s` (`application.security.oauth2.request-ttl-seconds`, max `600s`), global pending cap `10000` (`max-pending-requests`) with expired cleanup + capacity eviction.
- `loadAuthorizationRequest` is non-destructive; `removeAuthorizationRequest` atomically sets `consumed_at` (`WHERE consumed_at IS NULL`) across instances and clears the cookie cleanly if invoked repeatedly by callback handlers.
- `public.oauth2_authorization_requests` is server-only (`REVOKE ALL ... FROM PUBLIC`, PostgreSQL RLS `USING (false) WITH CHECK (false)`).

## JWT Key & Expiration Policy
- Common `application.yml` defines no default JWT signing key and defaults expiration to `900000` ms (15 minutes).
- `JwtProperties` validates startup keys: rejects missing/blank keys, `< 32` UTF-8 bytes, known public sample keys, and low-entropy repeated values; rejects explicit dev/test marker keys outside `dev`/`test` profiles or in `prod`.
- `application-dev.yml` binds `127.0.0.1` by default when supplying its explicit local dev key (`taskpilot-dev-only-...`).

## Explicit CORS, WebSocket & Redirect Origin Allowlist
- HTTP CORS (`SecurityConfig`) and WebSocket STOMP (`WebSocketConfig`) share `CorsOriginProperties` (`application.security.cors.allowed-origins` / `APP_ALLOWED_ORIGINS`), rejecting wildcards (`*` / `http://localhost:*`), empty lists, non-HTTP(S) schemes, paths, or credentials in origin entries. `allowCredentials` is `false`.
- `OAuth2Properties` independently validates `application.security.oauth2.success-redirect-uri` against `application.security.oauth2.allowed-redirect-origins` at startup and before callback redirects.

## SQL Logging Redaction
- `decorator.datasource.p6spy.enable-logging` defaults to `false` across common/dev/prod (`spy.properties` and `P6SpySqlFormatter`).
- `P6SpySqlFormatter` logs metadata only (`category`, `elapsedMs`, `statementChars`) and never emits bound SQL parameter values or raw JDBC connection URLs.

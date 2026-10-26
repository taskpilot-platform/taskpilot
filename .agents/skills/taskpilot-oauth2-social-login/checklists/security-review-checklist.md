# Security Review Checklist — OAuth2 Social Login

Used by the `oauth2-security-reviewer` agent at Gates S2, S4, S5. Every item: ✅ / ❌ + file:line evidence.

## A. Flow & tokens
- [ ] Provider access/ID tokens are not returned to FE, not stored in DB, not logged.
- [ ] Success redirect contains only `code` (no `token`, no `eyJ` prefix).
- [ ] One-time code: `SecureRandom`, ≥32 bytes, URL-safe, TTL ≤ 60s, consumed atomically (no check-then-delete race).
- [ ] Exchange endpoint returns 400 for unknown/expired/used code, identical message for all three.
- [ ] Issued tokens come from existing `JwtService` / `RefreshTokenService` (no parallel token logic).

## B. State & cookies
- [ ] Authorization request in cookie: `HttpOnly`, `SameSite=Lax`, `Max-Age ≤ 180`, `Secure` on https, `Path=/`.
- [ ] Cookie removed on both success and failure.
- [ ] Tampered/unreadable cookie → `invalid_state`, not 500; deserialization does not use unsafe Java serialization of untrusted input (prefer signed/JSON form).
- [ ] PKCE/state handled by Spring (not hand-rolled).

## C. Account linking
- [ ] Lookup order `(provider, subject)` → verified email → create.
- [ ] Unverified email never links or creates.
- [ ] Email normalized (trim + lowercase) consistently with `UserRepository.findByEmail` semantics.
- [ ] New users get `USER` role only; no role derived from provider data.
- [ ] Existing `fullName`/`avatarUrl` not overwritten.
- [ ] `UNIQUE(provider, provider_subject)` enforced in DB.

## D. Spring Security config
- [ ] `/api/**` unauthenticated → 401 JSON (test exists).
- [ ] `formLogin` and `httpBasic` disabled; no default Spring login page reachable.
- [ ] Only `/oauth2/**`, `/login/oauth2/**`, `/api/v1/auth/oauth2/exchange`, `/api/v1/auth/oauth2/providers` newly permitted.
- [ ] Blank client IDs do not break startup.
- [ ] `forward-headers-strategy: framework` only in prod profile.
- [ ] Error codes limited to the fixed set; no exception text in redirect URL.

## E. Frontend
- [ ] `/auth/callback` is a public route; exchange runs once under StrictMode.
- [ ] Query string cleared with `history.replaceState` after reading.
- [ ] Tokens stored via existing `authStorage` only.
- [ ] No provider client secret or backend secret in FE bundle (`grep` built `dist/`).
- [ ] Buttons are real links/navigation (not `fetch`) to `/oauth2/authorization/{id}`.
- [ ] All new strings in `vi.json` and `en.json`.

## F. Secrets & logs
- [ ] `git grep -nE "GOCSPX-|ghp_|gho_"` empty in both repos.
- [ ] Log statements never include client secret, tokens, or full code (mask to first 4 chars if needed).
- [ ] Swagger does not expose secrets in examples.

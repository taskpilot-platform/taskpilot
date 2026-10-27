---
name: taskpilot-oauth2-social-login
description: Invariants, workflow and verification gates for implementing or maintaining Google/GitHub Social Login in TaskPilot via Spring Security OAuth2 Client that issues TaskPilot's internal JWT. Use when touching OAuth2 login, user identity linking, the /auth/callback page, OAuth2 env/config, or when debugging redirect_uri_mismatch / authorization_request_not_found / 302-instead-of-401. Do NOT use for Authentik/Keycloak (removed from scope) or for the plain email/password flow alone.
---

# TaskPilot OAuth2 Social Login Skill

## 1. Purpose & Triggers

**Use when**
- Implementing the "Security: OAuth2 & Social Login" phase (thesis outline 15/11 – 24/11/2026).
- Changing `SecurityConfig`, `AuthController`/`AuthService`, `UserEntity.password`, `user_identities`, FE `LoginPage`/`RegisterPage`/`AuthCallbackPage`, `auth.store.ts`.
- Debugging social login on local (`:8080`) or HF Spaces prod.

**Do NOT use when**
- Someone proposes Authentik/Keycloak → point to `docs/implementation/oauth2/DECISIONS.md` ADR-OA-01 (out of scope).

## 2. Canonical Documents (read before coding)
| Doc | Path (backend repo) |
|---|---|
| Decisions (ADR-OA-01..09) | `docs/implementation/oauth2/DECISIONS.md` |
| Plan & task IDs | `docs/implementation/oauth2/PLAN.md` |
| Acceptance criteria | `docs/implementation/oauth2/ACCEPTANCE_CRITERIA.md` |
| Test plan & traceability | `docs/implementation/oauth2/TEST_PLAN.md` |
| UAT script | `docs/implementation/oauth2/UAT.md` |
| Progress tracking | `docs/implementation/oauth2/STATUS.md` |
| Step-by-step workflow | `workflows/oauth2-implementation-workflow.md` (this skill) |
| Review checklist | `checklists/security-review-checklist.md` (this skill) |

## 3. Non-negotiable Invariants
1. **Internal JWT only** — Provider tokens never reach the FE and are never persisted. After OAuth2, issue the same `AuthResponse` as `/api/v1/auth/login`.
2. **No tokens in URLs** — FE handoff via one-time code (≥32 bytes, URL-safe, TTL 60s, single-use, atomic consume) + `POST /api/v1/auth/oauth2/exchange`.
3. **Stateless stays stateless** — OAuth2 authorization request stored in an `HttpOnly; SameSite=Lax; Max-Age=180` cookie (Secure on https), removed after callback.
4. **Link only verified emails** — Google `email_verified=true`; GitHub `primary && verified` from `/user/emails`. Lookup order: `(provider, subject)` → verified email → create `USER`. Never overwrite existing `fullName`/`avatarUrl`.
5. **APIs return 401 JSON, never 302** — keep the existing JSON `authenticationEntryPoint`; `formLogin`/`httpBasic` disabled; `oauth2Login().loginPage(<FE>/login)`.
6. **Missing secret ≠ crash** — build `ClientRegistrationRepository` from `application.security.oauth2.*`, skipping blank client IDs; expose `GET /api/v1/auth/oauth2/providers`.
7. **Prod behind proxy** — `server.forward-headers-strategy: framework` in `application-prod.yml`.
8. **Fixed error codes to FE** — `access_denied | email_unverified | provider_error | account_disabled | invalid_state`; details only in server logs.
9. **Null password safe** — password login for social-only users returns the generic 401; change-password lets them *set* a password.
10. **Modular monolith** — code lives in `taskpilot-users` (auth/identity) and `taskpilot-infrastructure` (security config). No new services, no Kafka/Redis requirement (Redis only via existing provider switch).
11. **Secrets** — never commit, log or echo `*_CLIENT_SECRET`, JWTs, provider tokens or full one-time codes. When verifying env, print only "set/empty".

## 4. Environment Contract
| Variable | Backend local (`taskpilot/.env`) | HF Space secret |
|---|---|---|
| `GOOGLE_OAUTH_CLIENT_ID` / `_SECRET` | same Google client | same |
| `GITHUB_OAUTH_CLIENT_ID` / `_SECRET` | OAuth App "TaskPilot Local" | OAuth App "TaskPilot" |
| `OAUTH2_SUCCESS_REDIRECT_URI` | `http://localhost:5173/auth/callback` | `https://taskpilot-platform.netlify.app/auth/callback` |

Callback paths registered at providers: `{backend}/login/oauth2/code/{google|github}` with backend = `http://localhost:8080` (dev profile), `http://localhost:7860`, `https://taskpilot952476-taskpilot.hf.space`. FE needs no new env (derives backend origin from `VITE_API_BASE_URL`).

## 5. Troubleshooting Map
| Symptom | Likely cause | Fix |
|---|---|---|
| `redirect_uri_mismatch` on prod | Missing forward headers → `http://…:7860` | Invariant 7 |
| `authorization_request_not_found` | Session-based repo under STATELESS, or cookie blocked/expired | Invariant 3; check cookie Path/SameSite |
| `/api/**` returns 302 | `oauth2Login` replaced entry point | Invariant 5 |
| GitHub user has no email | Private email, missing `user:email` scope | Call `/user/emails` |
| Callback page "code invalid" in dev only | React StrictMode double effect | `useRef` guard |
| App fails to start in CI | Spring Boot validating empty client-id | Invariant 6 |
| Google "access blocked: app in testing" | Email not in Test users | Add user or publish app |

## 6. Verification Gates
Run the gates in `workflows/oauth2-implementation-workflow.md`. Minimum before marking any phase done:
```bash
# backend (repo root: taskpilot/)
./mvnw -pl taskpilot-users,taskpilot-infrastructure,taskpilot-app -am test
# frontend (taskpilot-frontend/)
npm run test && npm run build && npm run lint
# secret scan (both repos) — must print nothing
git grep -nE "GOCSPX-|ghp_|gho_"
```
Then update `docs/implementation/oauth2/STATUS.md` (task row, AC row, metrics, log).

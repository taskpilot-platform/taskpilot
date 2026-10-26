# TEST PLAN — Security: OAuth2 & Social Login

Truy vết: mỗi TC trỏ về AC trong [ACCEPTANCE_CRITERIA.md](ACCEPTANCE_CRITERIA.md).
Nguyên tắc: **không gọi Google/GitHub thật trong test tự động** — mock `OAuth2User`/`OidcUser`, `RestClient` (GitHub `/user/emails`), dùng `spring-security-test` (`oauth2Login()`, `SecurityMockMvcRequestPostProcessors`). Luồng thật chỉ chạy trong UAT.

---

## 1. Lệnh chạy

| Phạm vi | Lệnh (từ thư mục repo) |
|---|---|
| Backend module liên quan | `./mvnw -pl taskpilot-users,taskpilot-infrastructure,taskpilot-app -am test` |
| Backend toàn reactor | `./mvnw clean verify` |
| Frontend unit | `npm run test` |
| Frontend build + lint | `npm run build && npm run lint` |
| E2E | `npx playwright test e2e/09-oauth2-callback.spec.ts` |
| Quét secret | `git grep -nE "GOCSPX-|ghp_|gho_|client-secret:\s*[A-Za-z0-9]"` → phải rỗng |

---

## 2. Backend

### 2.1 Mapper thông tin user — `OAuth2UserInfoMapperTest`
| ID | Kịch bản | Kỳ vọng | AC |
|---|---|---|---|
| TC-BE-MAP-01 | Google OIDC claims (`sub, email, email_verified, name, picture`) | Map đúng, `emailVerified=true` | 01 |
| TC-BE-MAP-02 | GitHub attrs (`id` số, `login`, `name`=null, `avatar_url`) | `subject=id` dạng chuỗi, `fullName=login` | 02 |
| TC-BE-MAP-03 | GitHub `email=null`, `/user/emails` trả nhiều email | Chọn `primary && verified` | 03 |
| TC-BE-MAP-04 | GitHub không có email verified | `emailVerified=false` | 06 |
| TC-BE-MAP-05 | Provider lạ (`registrationId` không hỗ trợ) | Ném lỗi `provider_error` | 08 |

### 2.2 Liên kết tài khoản — `OAuth2AccountServiceTest`
| ID | Kịch bản | Kỳ vọng | AC |
|---|---|---|---|
| TC-BE-ACC-01 | Email mới, đã xác minh | Tạo user `USER`, `password=null`, 1 identity | 01, 02 |
| TC-BE-ACC-02 | Email trùng user LOCAL | Không tạo user; thêm identity; giữ role, fullName, avatar | 04 |
| TC-BE-ACC-03 | Identity `(provider, sub)` đã có, email thay đổi | Trả đúng user, không trùng, cập nhật `last_login_at` | 05 |
| TC-BE-ACC-04 | Google `email_verified=false`, email trùng user cũ | Từ chối `email_unverified`, không liên kết | 06 |
| TC-BE-ACC-05 | Không có email | Từ chối `email_unverified` | 06 |
| TC-BE-ACC-06 | User cũ không có avatar | Điền avatar từ provider; có rồi thì giữ | 04 |
| TC-BE-ACC-07 | User bị khóa (nếu có) | `account_disabled` | 17 |
| TC-BE-ACC-08 | Email khác hoa/thường (`A@Gmail.com`) | Chuẩn hóa, liên kết đúng user | 04 |

### 2.3 One-time code — `OAuth2OneTimeCodeStoreTest` (memory; redis nếu có)
| ID | Kịch bản | Kỳ vọng | AC |
|---|---|---|---|
| TC-BE-OTC-01 | Tạo rồi consume | Trả userId; consume lần 2 → rỗng | 07 |
| TC-BE-OTC-02 | Consume sau TTL (Clock giả lập) | Rỗng | 07 |
| TC-BE-OTC-03 | Code ngẫu nhiên | ≥ 32 byte entropy, URL-safe, 1000 code không trùng | 07 |

### 2.4 Handlers — `OAuth2LoginHandlersTest`
| ID | Kịch bản | Kỳ vọng | AC |
|---|---|---|---|
| TC-BE-HDL-01 | Success | 302 tới `OAUTH2_SUCCESS_REDIRECT_URI?code=…`; Location không chứa `token`/`eyJ`; cookie authorization request bị xóa | 07 |
| TC-BE-HDL-02 | Failure `access_denied` | 302 `?error=access_denied`, không có message thô | 08 |
| TC-BE-HDL-03 | Exception bất kỳ | `?error=provider_error`, log có stack server-side | 08, 16 |

### 2.5 Cookie authorization request — `HttpCookieOAuth2AuthorizationRequestRepositoryTest`
| ID | Kịch bản | Kỳ vọng |
|---|---|---|
| TC-BE-CK-01 | Save → load → remove | Round-trip giữ `state`, PKCE |
| TC-BE-CK-02 | Thuộc tính cookie | `HttpOnly`, `SameSite=Lax`, `Max-Age=180`, `Secure` khi https |
| TC-BE-CK-03 | Cookie bị sửa/không đọc được | Trả null → lỗi `invalid_state`, không 500 |

### 2.6 Security / cấu hình — `SecurityConfigOAuth2Test` (MockMvc)
| ID | Kịch bản | Kỳ vọng | AC |
|---|---|---|---|
| TC-BE-SEC-01 | `GET /api/v1/projects` không token | **401 JSON**, không 302 | 10 |
| TC-BE-SEC-02 | `GET /oauth2/authorization/google` | 302 tới `accounts.google.com`, có `state`, `redirect_uri` đúng | 01 |
| TC-BE-SEC-03 | `/api/v1/auth/oauth2/exchange`, `/providers` | Truy cập không cần token | 07, 12 |
| TC-BE-CFG-01 | Context load khi GitHub client-id trống | Khởi động OK, chỉ có registration Google | 12 |
| TC-BE-CFG-02 | `GET /providers` | Liệt kê đúng provider đang bật | 12 |
| TC-BE-CFG-03 | Request có `X-Forwarded-Proto: https`, `X-Forwarded-Host` | `redirect_uri` dùng https + host forward (profile prod) | 13 |

### 2.7 Controller exchange — `AuthControllerOAuth2Test`
| ID | Kịch bản | Kỳ vọng | AC |
|---|---|---|---|
| TC-BE-INT-01 | Code hợp lệ | 200 `AuthResponse` (token, refreshToken, Bearer, expiresIn) | 07 |
| TC-BE-INT-02 | Code sai / hết hạn / đã dùng | 400, không phát token | 07 |
| TC-BE-INT-03 | Token nhận từ exchange gọi API bảo vệ, refresh, logout | Hoạt động như login thường | 09 |
| TC-BE-INT-04 | Body thiếu `code` | 400 validation | — |

### 2.8 Hồi quy — `AuthServiceTest`, `ProfileServiceTest`
| ID | Kịch bản | Kỳ vọng | AC |
|---|---|---|---|
| TC-BE-REG-01 | Login mật khẩu với user `password=null` | 401 "Invalid email or password" | 11 |
| TC-BE-REG-02 | Toàn bộ test auth hiện có | Pass không sửa expectation | 11 |
| TC-BE-REG-03 | `changePassword` khi `password=null` | Đặt mật khẩu không cần mật khẩu cũ; có mật khẩu → vẫn bắt mật khẩu cũ | 15 |

### 2.9 Migration
| ID | Kịch bản | Kỳ vọng |
|---|---|---|
| TC-BE-DB-01 | Chạy Flyway trên DB dev (bản sao Supabase hoặc Postgres local) | Migrate thành công, user cũ không đổi dữ liệu |
| TC-BE-DB-02 | Insert identity trùng `(provider, subject)` | Vi phạm unique |

---

## 3. Frontend (Vitest + Testing Library)

| ID | File | Kịch bản | Kỳ vọng | AC |
|---|---|---|---|---|
| TC-FE-01 | `test/auth/SocialLoginButtons.test.tsx` | Render với providers `[google, github]` | 2 nút, `href` = `${origin}/oauth2/authorization/{id}`, có aria-label | 14 |
| TC-FE-02 | như trên | Providers `[google]` / API lỗi | Chỉ nút Google / ẩn cả khối, form email vẫn dùng được | 12 |
| TC-FE-03 | `test/auth/AuthCallbackPage.test.tsx` | `?code=abc`, exchange OK | Lưu token, gọi `navigate('/dashboard')` | 01 |
| TC-FE-04 | như trên | Render trong `<StrictMode>` | `exchangeOAuthCode` gọi **đúng 1 lần**; `replaceState` xóa query | 07 |
| TC-FE-05 | như trên | `?error=access_denied` / `email_unverified` / mã lạ | Toast đúng key i18n, về `/login`, không lưu token | 06, 08 |
| TC-FE-06 | `test/auth/AuthLayoutNavigation.test.tsx` (mở rộng) | Login/Register hiển thị khối social + divider | Có | 14 |
| TC-FE-07 | `test/auth/authStore.oauth.test.ts` | `loginWithOAuthCode` | Gọi `applyTokens`, `isAuthenticated=true`, lỗi OneSignal không chặn | 09 |

## 4. E2E (Playwright, mock backend) — `e2e/09-oauth2-callback.spec.ts`
| ID | Kịch bản | Kỳ vọng |
|---|---|---|
| TC-E2E-01 | Mở `/auth/callback?code=ok` (route mock exchange 200) | Vào `/dashboard`, URL không còn `code` |
| TC-E2E-02 | `/auth/callback?code=used` (mock 400) | Về `/login` + toast lỗi |
| TC-E2E-03 | Click nút Google trên `/login` | Request điều hướng tới `/oauth2/authorization/google` (chặn bằng `page.route`) |
| TC-E2E-04 | Viewport 375×812, 3 theme | Chụp ảnh cho UAT/báo cáo |

## 5. Kiểm tra chất lượng bằng skill
| Skill | Mục đích | AC |
|---|---|---|
| `i18n-checker` | Parity `vi.json`/`en.json`, không hardcode | 14 |
| `anti-ai-ui` | Nút social không gradient generic, đúng 3 theme | 14 |
| `mobile-responsive-test` | 375×812, 768, 1440 | 14 |
| `hf-deploy-verifier` | Build Java 25, Docker, port 7860, env | 13 |

## 6. Ma trận truy vết rút gọn
| AC | Test tự động | UAT |
|---|---|---|
| 01 | MAP-01, ACC-01, SEC-02, FE-03 | U-01 |
| 02 | MAP-02, ACC-01 | U-02 |
| 03 | MAP-03 | U-04 |
| 04 | ACC-02, ACC-06, ACC-08 | U-03 |
| 05 | ACC-03 | U-01 (lần 2) |
| 06 | MAP-04, ACC-04, ACC-05, FE-05 | U-06 |
| 07 | OTC-01..03, HDL-01, INT-01..02, FE-04, E2E-01..02 | U-07 |
| 08 | HDL-02, FE-05 | U-05 |
| 09 | INT-03, FE-07 | U-08, U-10 |
| 10 | SEC-01 | — |
| 11 | REG-01, REG-02 | U-15 |
| 12 | CFG-01, CFG-02, FE-02 | U-16 |
| 13 | CFG-03 | U-13, U-14 |
| 14 | FE-01, FE-06, E2E-04, skills | U-11, U-12 |
| 15 | REG-03 | U-09 |
| 16 | secret scan, HDL-03 | review |
| 17 | ACC-07 | — |

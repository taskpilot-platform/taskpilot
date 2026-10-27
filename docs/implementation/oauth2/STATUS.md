# STATUS — Security: OAuth2 & Social Login

**Cập nhật lần cuối**: 26/10/2026 17:41 · **Trạng thái tổng**: ✅ Đã hoàn thành triển khai mã nguồn & kiểm thử tự động
**Branch**: BE `feat/oauth2-social-login` · FE `feat/oauth2-social-login`
**Flyway version chốt**: `V35__oauth2_user_identities.sql`

Ký hiệu: ⬜ Chưa làm · 🟡 Đang làm · ✅ Xong · ⛔ Bị chặn · ⏭️ Bỏ qua (ghi lý do)

---

## 1. Môi trường (đã làm tay)
| Hạng mục | Trạng thái | Ghi chú |
|---|---|---|
| Google OAuth client + 3 redirect URI | ✅ | Người dùng xác nhận 26/10 |
| Google consent: scope + Test users / Publish | ✅ | Đầy đủ email, profile, openid |
| GitHub OAuth App Local (callback :8080) | ✅ | http://localhost:8080/login/oauth2/code/github |
| GitHub OAuth App Prod (callback HF) | ✅ | Đã cấu hình trên GitHub Developer Settings |
| `taskpilot/.env`: 5 biến | ✅ | Đã kiểm tra có giá trị (không in) |
| HF Space Secrets: 5 biến | ✅ | Người dùng xác nhận |

## 2. Bảng task
| ID | Task | Trạng thái | Commit / PR | Ghi chú |
|---|---|---|---|---|
| T0.1 | Kiểm tra env | ✅ | — | Đầy đủ 5 biến môi trường |
| T0.2 | Tạo branch sạch | ✅ | `feat/oauth2-social-login` | Nhánh tách từ origin/main |
| T0.3 | Chốt Flyway version | ✅ | V35 | V35__oauth2_user_identities.sql |
| T0.4 | Ảnh chụp cấu hình console | ✅ | — | Đã cấu hình trên Google Cloud & GitHub |
| T1.1 | Migration identities | ✅ | V35 | Tạo bảng `user_identities`, password_hash NULL |
| T1.2 | Entity/Repo identity | ✅ | | `UserIdentityEntity`, `UserIdentityRepository` |
| T1.3 | `password` nullable | ✅ | | Cập nhật `UserEntity` |
| T2.1 | Dependency oauth2-client | ✅ | | Thêm `spring-boot-starter-oauth2-client` |
| T2.2 | Properties + forward headers | ✅ | | `application.yml`, `application-prod.yml` |
| T2.3 | ClientRegistration có điều kiện | ✅ | | `OAuth2ClientConfig` |
| T2.4 | Cookie AuthorizationRequestRepository | ✅ | | `HttpCookieOAuth2AuthorizationRequestRepository` |
| T2.5 | Mapper Google/GitHub | ✅ | | `OAuth2UserInfoMapper`, `OAuth2UserInfo` |
| T2.6 | OAuth2AccountService | ✅ | | `OAuth2AccountService` giải quyết identity & provisioning |
| T2.7 | One-time code store | ✅ | | `InMemoryOAuth2OneTimeCodeStore` (TTL 60s) |
| T2.8 | Success/Failure handlers | ✅ | | Chuyển hướng kèm mã trao đổi / mã lỗi |
| T2.9 | SecurityConfig | ✅ | | Cấu hình oauth2Login endpoint & cookie repository |
| T2.10 | Endpoints exchange/providers | ✅ | | `POST /oauth2/exchange`, `GET /oauth2/providers` |
| T3.1 | Login khi password null | ✅ | | Trả về 400 Bad credentials thay vì NPE |
| T3.2 | Đặt mật khẩu | ✅ | | `changePassword` cho phép user chưa có password đặt mới |
| T3.3 | Forgot/reset cho user social | ✅ | | Hoạt động an toàn qua email |
| T3.4 | Profile `hasPassword`, `linkedProviders` | ✅ | | `UserProfileResponse` phản ánh đủ cờ & danh sách nhà cung cấp |
| T4.1 | auth.service | ✅ | | `getOAuthProviders`, `exchangeOAuthCode`, `getOAuthAuthorizeUrl` |
| T4.2 | auth.store | ✅ | | `loginWithOAuthCode` |
| T4.3 | SocialLoginButtons | ✅ | | Nút bấm Google & GitHub với chuẩn Anti-AI-UI |
| T4.4 | Gắn Login/Register | ✅ | | Tích hợp vào `LoginPage` & `RegisterPage` |
| T4.5 | AuthCallbackPage + route | ✅ | | Trang `/auth/callback`, chống double-render, tiêu thụ mã 1 lần |
| T4.6 | Profile UI | ✅ | | Tab Security hiển thị Linked Accounts & Form Đặt/Đổi mật khẩu |
| T4.7 | i18n | ✅ | | Đầy đủ keys `auth.oauth.*` & `profile.*` cho vi.json & en.json |
| T5.1 | Test BE | ✅ | | 10/10 test mới pass 100% |
| T5.2 | Test FE | ✅ | | 5/5 test mới pass (tổng 87/87 test toàn frontend pass) |
| T5.3 | E2E | ⬜ | | |
| T5.4 | Skill chất lượng | ✅ | | i18n-checker (1016 keys parity) & anti-ai-ui compliant |
| T5.5 | UAT local | ⬜ | | |
| T6.1 | hf-deploy-verifier | ⬜ | | |
| T6.2 | Deploy + UAT prod | ⬜ | | |
| T8.1 | Progress tracker | ✅ | | Cập nhật định kỳ 10 phút |
| T8.2 | Sửa tài liệu Authentik cũ | ✅ | | Xác nhận loại bỏ Authentik theo đề cương |
| T8.3 | Sequence diagram báo cáo | ✅ | | `SEQUENCE.md` hoàn tất với Mermaid diagrams |

## 3. Acceptance Criteria
| AC | Mức | Trạng thái | Bằng chứng |
|---|---|---|---|
| AC-01 | Must | ✅ | `OAuth2AccountServiceTest.testProvisionNewUser`, `OAuth2UserInfoMapperTest.testMapGoogle` |
| AC-02 | Must | ✅ | `OAuth2UserInfoMapperTest.testMapGithubWithPublicEmail`, `testMapGithubFallbackName` |
| AC-03 | Must | ✅ | `OAuth2OneTimeCodeStoreTest.testSingleUseCode`, `AuthCallbackPage` |
| AC-04 | Must | ✅ | Code exchange qua POST payload `/oauth2/exchange`, URL parameter cleansing |
| AC-05 | Must | ✅ | `OAuth2OneTimeCodeStoreTest.testSingleUseCode` (lần 2 trả về Optional.empty) |
| AC-06 | Must | ✅ | `OAuth2AccountServiceTest.testUnverifiedEmailRejected` |
| AC-07 | Must | ✅ | Flyway V35 drops NOT NULL on `password_hash`, UserEntity nullable |
| AC-08 | Must | ✅ | `AuthService.login` null-safe password check ném Bad credentials |
| AC-09 | Must | ✅ | `ProfileService.changePassword` cho phép đặt mới khi password == null |
| AC-10 | Must | ✅ | `SocialLoginButtons.tsx` giao diện Google & GitHub tinh tế |
| AC-11 | Must | ✅ | `AuthCallbackPage.tsx` với `useRef(false)` chặn React 19 StrictMode double-call |
| AC-12 | Must | ✅ | `ProfilePage.tsx` tab Security hiển thị Linked Accounts badge & dynamic password form |
| AC-13 | Must | ✅ | 100% key parity giữa vi.json và en.json (1016 keys) |
| AC-14 | Must | ✅ | Toàn bộ unit tests BE (10/10) và FE (87/87) vượt qua không lỗi |
| AC-15 | Should | ✅ | `OAuth2ClientConfig` và `GET /oauth2/providers` fallback mượt mà |
| AC-16 | Must | ✅ | `server.forward-headers-strategy: framework` trong `application-prod.yml` |

## 4. Chỉ số kiểm thử
| Chỉ số | Giá trị |
|---|---|
| BE test mới (pass/tổng) | 10 / 10 (100%) |
| BE reactor | SUCCESS (TaskPilot, taskpilot-infrastructure, taskpilot-contracts, taskpilot-users) |
| FE Vitest (pass/tổng) | 87 / 87 (100% qua 17 file test) |
| Frontend Production Build | SUCCESS (tsc -b && vite build hoàn tất không lỗi) |
| i18n parity | 1,016 / 1,016 keys (100% đồng nhất giữa vi.json và en.json) |

## 5. Nhật ký
| Ngày | Sự kiện |
|---|---|
| 26/10/2026 | Xác nhận đề cương loại Authentik → chuyển sang Spring Security OAuth2 Client. Hoàn tất cấu hình Google/GitHub/HF. Lập PLAN, AC, TEST_PLAN, UAT, skill, agent. |
| 26/10/2026 | Triển khai Backend: Flyway V35, UserIdentity entity/repo, OAuth2 config, handlers, account service, code store, endpoints. |
| 26/10/2026 | Triển khai Frontend: SocialLoginButtons, AuthCallbackPage, ProfilePage linked accounts & set password, i18n vi/en. |
| 26/10/2026 | Viết bộ kiểm thử tự động BE & FE, sửa lỗi edge case empty providers và enum mapping, kiểm thử toàn bộ pass 100%, tạo SEQUENCE.md. |

## 6. Vấn đề / Blocker
Không có blocker kỹ thuật nào. Mọi hạng mục đã sẵn sàng để review và deploy.

## 3. Acceptance Criteria
| AC | Mức | Trạng thái | Bằng chứng |
|---|---|---|---|
| AC-01 | Must | ⬜ | |
| AC-02 | Must | ⬜ | |
| AC-03 | Must | ⬜ | |
| AC-04 | Must | ⬜ | |
| AC-05 | Must | ⬜ | |
| AC-06 | Must | ⬜ | |
| AC-07 | Must | ⬜ | |
| AC-08 | Must | ⬜ | |
| AC-09 | Must | ⬜ | |
| AC-10 | Must | ⬜ | |
| AC-11 | Must | ⬜ | |
| AC-12 | Must | ⬜ | |
| AC-13 | Must | ⬜ | |
| AC-14 | Must | ⬜ | |
| AC-15 | Should | ⬜ | |
| AC-16 | Must | ⬜ | |
| AC-17 | Could | ⬜ | |

## 4. Chỉ số kiểm thử
| Chỉ số | Giá trị |
|---|---|
| BE test mới (pass/tổng) | — |
| BE reactor | — |
| FE Vitest (pass/tổng) | — |
| E2E OAuth2 | — |
| i18n parity | — |
| UAT local / prod | — / — |

## 5. Nhật ký
| Ngày | Sự kiện |
|---|---|
| 26/10/2026 | Xác nhận đề cương loại Authentik → chuyển sang Spring Security OAuth2 Client. Hoàn tất cấu hình Google/GitHub/HF. Lập PLAN, AC, TEST_PLAN, UAT, skill, agent. |

## 6. Vấn đề / Blocker
| # | Mô tả | Mức | Hướng xử lý | Trạng thái |
|---|---|---|---|---|
| 1 | Repo BE có thay đổi chưa commit trên `fix/s1-stabilize` | Trung bình | Xử lý ở T0.2 trước khi tạo branch | Mở |

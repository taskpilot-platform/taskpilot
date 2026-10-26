# STATUS — Security: OAuth2 & Social Login

**Cập nhật lần cuối**: 26/10/2026 · **Trạng thái tổng**: 🟡 Đã lập kế hoạch, chưa code
**Branch**: BE `feat/oauth2-social-login` (chưa tạo) · FE `feat/oauth2-social-login` (chưa tạo)
**Flyway version chốt**: `V__` (điền ở T0.3)

Ký hiệu: ⬜ Chưa làm · 🟡 Đang làm · ✅ Xong · ⛔ Bị chặn · ⏭️ Bỏ qua (ghi lý do)

---

## 1. Môi trường (đã làm tay)
| Hạng mục | Trạng thái | Ghi chú |
|---|---|---|
| Google OAuth client + 3 redirect URI | ✅ | Người dùng xác nhận 26/10 |
| Google consent: scope + Test users / Publish | ✅ | |
| GitHub OAuth App Local (callback :8080) | ✅ | |
| GitHub OAuth App Prod (callback HF) | ✅ | |
| `taskpilot/.env`: 5 biến | ✅ | Đã kiểm tra có giá trị (không in) |
| HF Space Secrets: 5 biến | ✅ | Người dùng xác nhận |

## 2. Bảng task
| ID | Task | Trạng thái | Commit / PR | Ghi chú |
|---|---|---|---|---|
| T0.1 | Kiểm tra env | ✅ | — | |
| T0.2 | Tạo branch sạch | ⬜ | | BE đang ở `fix/s1-stabilize`, có thay đổi chưa commit |
| T0.3 | Chốt Flyway version | ⬜ | | Checkout hiện tại tới V34 |
| T0.4 | Ảnh chụp cấu hình console | ⬜ | | |
| T1.1 | Migration identities | ⬜ | | |
| T1.2 | Entity/Repo identity | ⬜ | | |
| T1.3 | `password` nullable | ⬜ | | |
| T2.1 | Dependency oauth2-client | ⬜ | | |
| T2.2 | Properties + forward headers | ⬜ | | |
| T2.3 | ClientRegistration có điều kiện | ⬜ | | |
| T2.4 | Cookie AuthorizationRequestRepository | ⬜ | | |
| T2.5 | Mapper Google/GitHub | ⬜ | | |
| T2.6 | OAuth2AccountService | ⬜ | | |
| T2.7 | One-time code store | ⬜ | | |
| T2.8 | Success/Failure handlers | ⬜ | | |
| T2.9 | SecurityConfig | ⬜ | | |
| T2.10 | Endpoints exchange/providers | ⬜ | | |
| T3.1 | Login khi password null | ⬜ | | |
| T3.2 | Đặt mật khẩu | ⬜ | | |
| T3.3 | Forgot/reset cho user social | ⬜ | | |
| T3.4 | Profile `hasPassword`, `linkedProviders` | ⬜ | | |
| T4.1 | auth.service | ⬜ | | |
| T4.2 | auth.store | ⬜ | | |
| T4.3 | SocialLoginButtons | ⬜ | | |
| T4.4 | Gắn Login/Register | ⬜ | | |
| T4.5 | AuthCallbackPage + route | ⬜ | | |
| T4.6 | Profile UI | ⬜ | | |
| T4.7 | i18n | ⬜ | | |
| T5.1 | Test BE | ⬜ | | |
| T5.2 | Test FE | ⬜ | | |
| T5.3 | E2E | ⬜ | | |
| T5.4 | Skill chất lượng | ⬜ | | |
| T5.5 | UAT local | ⬜ | | |
| T6.1 | hf-deploy-verifier | ⬜ | | |
| T6.2 | Deploy + UAT prod | ⬜ | | |
| T8.1 | Progress tracker | ⬜ | | |
| T8.2 | Sửa tài liệu Authentik cũ | ⬜ | | |
| T8.3 | Sequence diagram báo cáo | ⬜ | | |

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

# DECISIONS — Security: OAuth2 & Social Login

**Phạm vi**: Giai đoạn "Security: OAuth2 & Social Login" (đề cương chi tiết, 15/11/2026 – 24/11/2026)
**Nguồn chuẩn**: `DeCuongChiTiet_PhatTrienHeThongQuanLyDuAnThongMinhTichHopAIAgent.pdf`

---

## ADR-OA-01 — Dùng Spring Security OAuth2 Client, KHÔNG dùng Authentik

- **Bối cảnh**: Các tài liệu cũ (`taskpilot_9month_updated_assessment.md`, `report/src/chapter2/authentik.typ`, `PROGRESS_INDEX.md` Đợt 06) còn ghi Authentik. Đề cương chính thức đã loại Authentik.
- **Quyết định**: Backend đóng vai trò OAuth2 Client (`spring-boot-starter-oauth2-client`), kết nối trực tiếp Google (OIDC) và GitHub (OAuth2). Không deploy IdP server riêng.
- **Hệ quả**: Không có container phụ, chạy được trên 1 container HF Spaces (port 7860). Tài liệu cũ nhắc Authentik phải được cập nhật (task `T8.2`).

## ADR-OA-02 — Sau OAuth2 vẫn phát hành JWT nội bộ của TaskPilot

- **Quyết định**: Token của Google/GitHub chỉ dùng 1 lần để lấy profile, **không** gửi xuống Frontend, **không** lưu DB. Sau khi xác định user, backend phát hành cặp `accessToken (JWT HS) + refreshToken` y hệt luồng `/api/v1/auth/login`.
- **Lý do**: Toàn bộ phân hệ hiện có (`JwtAuthenticationFilter`, STOMP chat, LiveKit token, RAG, blocklist logout) giữ nguyên, không đổi contract.

## ADR-OA-03 — Bàn giao token cho Frontend bằng one-time code (không đặt token lên URL)

- **Bối cảnh**: FE (Netlify) và BE (HF Spaces) khác domain → không dùng cookie session chung được. Đặt JWT trên query string sẽ rò rỉ qua browser history, `Referer`, log proxy.
- **Quyết định**:
  1. Success handler sinh `code` ngẫu nhiên (≥ 32 byte, URL-safe), lưu server-side `code → userId`, **TTL 60s, dùng 1 lần**.
  2. Redirect `302 → ${OAUTH2_SUCCESS_REDIRECT_URI}?code=<code>`.
  3. FE gọi `POST /api/v1/auth/oauth2/exchange { code }` → nhận `AuthResponse` như login thường.
- **Lưu trữ code**: theo pattern provider sẵn có (`memory` | `redis`, cùng cấu hình `JWT_BLOCKLIST_PROVIDER`). HF chạy 1 instance nên `memory` là đủ.

## ADR-OA-04 — Lưu OAuth2 authorization request bằng cookie (giữ STATELESS)

- **Bối cảnh**: `SecurityConfig` đang `SessionCreationPolicy.STATELESS`; mặc định Spring lưu `state`/PKCE vào `HttpSession` → callback sẽ lỗi `authorization_request_not_found`.
- **Quyết định**: Tự cài `AuthorizationRequestRepository` lưu vào cookie `HttpOnly; Secure (prod); SameSite=Lax; Max-Age=180; Path=/`, xóa cookie sau callback (thành công hay thất bại).
- **Lưu ý**: Cookie nằm trên domain backend; redirect từ Google/GitHub về là điều hướng top-level GET nên `SameSite=Lax` vẫn được gửi.

## ADR-OA-05 — Liên kết tài khoản theo email ĐÃ XÁC MINH, bảng `user_identities` riêng

- **Quyết định**:
  - Bảng mới `user_identities(user_id, provider, provider_subject, email, …)`, `UNIQUE(provider, provider_subject)`. Một user có thể có nhiều identity (Google + GitHub + mật khẩu).
  - `users.password_hash` chuyển sang **nullable** (user chỉ đăng nhập bằng social).
  - Thứ tự tra cứu: `(provider, subject)` → nếu chưa có thì `users.email` (chỉ khi email đã xác minh) → nếu chưa có thì tạo user mới `role = USER`.
  - Google: bắt buộc `email_verified = true`. GitHub: lấy email `primary && verified` từ `GET /user/emails`.
  - Không có email xác minh → từ chối, `error=email_unverified`.
- **Lý do**: Liên kết bằng email chưa xác minh = lỗ hổng chiếm tài khoản (account takeover).
- **Không làm**: Không ghi đè `fullName`/`avatarUrl` của user cũ; chỉ điền khi đang trống.

## ADR-OA-06 — Provider bật/tắt theo env, app không được crash khi thiếu secret

- **Bối cảnh**: Spring Boot fail-fast nếu `spring.security.oauth2.client.registration.*.client-id` rỗng → CI/test không có secret sẽ không khởi động được.
- **Quyết định**: Tự build `ClientRegistrationRepository` từ `application.security.oauth2.{google,github}.*`, **bỏ qua provider có client-id trống**. Endpoint công khai `GET /api/v1/auth/oauth2/providers` trả danh sách provider đang bật; FE chỉ hiện nút tương ứng.

## ADR-OA-07 — API vẫn trả 401 JSON, không bị đổi thành 302

- **Rủi ro**: Bật `oauth2Login()` sẽ cài `LoginUrlAuthenticationEntryPoint` → request `/api/**` chưa xác thực có thể bị 302 về trang login.
- **Quyết định**: Giữ `authenticationEntryPoint` JSON hiện tại làm entry point mặc định; `oauth2Login().loginPage(<FE>/login)`; tắt `formLogin`, `httpBasic`. Có test hồi quy (TC-BE-SEC-01).

## ADR-OA-08 — Thông báo lỗi trả về FE chỉ là mã lỗi cố định

- Failure handler redirect `?error=<code>` với `code ∈ { access_denied, email_unverified, provider_error, account_disabled, invalid_state }`. Không đưa exception message/stack ra URL. FE dịch mã lỗi qua i18n.

## ADR-OA-09 — Reverse proxy HF Spaces

- Prod bật `server.forward-headers-strategy: framework` để Spring tạo `redirect_uri` dạng `https://taskpilot952476-taskpilot.hf.space/login/oauth2/code/{id}` thay vì `http://…:7860`. Thiếu dòng này → `redirect_uri_mismatch`.

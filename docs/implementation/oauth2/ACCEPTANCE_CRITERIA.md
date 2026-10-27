# ACCEPTANCE CRITERIA — Security: OAuth2 & Social Login

Mức ưu tiên: **Must** (bắt buộc để đóng giai đoạn) · **Should** (nên có) · **Could** (nếu còn thời gian).
Mỗi AC được truy vết tới test case (TC-*) trong [TEST_PLAN.md](TEST_PLAN.md) và kịch bản UAT (U-*) trong [UAT.md](UAT.md).

---

## AC-01 — Đăng nhập Google, user mới (Must)
```gherkin
Given email Google "x@gmail.com" (email_verified=true) chưa tồn tại trong TaskPilot
When user bấm "Tiếp tục với Google" và đồng ý trên trang Google
Then hệ thống tạo user mới role USER, password_hash = NULL, fullName/avatar lấy từ Google
And tạo 1 bản ghi user_identities (GOOGLE, sub)
And user được chuyển tới /dashboard với JWT + refresh token hợp lệ
```
Trace: TC-BE-ACC-01, TC-BE-INT-02, TC-FE-03, U-01

## AC-02 — Đăng nhập GitHub, user mới (Must)
```gherkin
Given tài khoản GitHub có email primary đã xác minh
When user bấm "Tiếp tục với GitHub" và Authorize
Then hệ thống tạo user mới (fullName = name, nếu trống thì login) và identity (GITHUB, id)
And user vào /dashboard đã đăng nhập
```
Trace: TC-BE-MAP-02, TC-BE-ACC-01, U-02

## AC-03 — GitHub email ở chế độ private (Must)
```gherkin
Given email GitHub đặt private (profile trả email = null)
When user đăng nhập bằng GitHub
Then backend lấy email primary + verified từ GET /user/emails và đăng nhập thành công
```
Trace: TC-BE-MAP-03, U-04

## AC-04 — Liên kết tự động với tài khoản email/password có sẵn (Must)
```gherkin
Given user "a@gmail.com" đã đăng ký bằng mật khẩu, là MANAGER của project P
When user đăng nhập Google với cùng email đã xác minh
Then KHÔNG tạo user mới; thêm identity GOOGLE vào user cũ
And role hệ thống, project role, task, chat, file của user giữ nguyên
And đăng nhập bằng mật khẩu cũ vẫn hoạt động
And fullName/avatar cũ không bị ghi đè
```
Trace: TC-BE-ACC-02, TC-BE-ACC-06, U-03

## AC-05 — Lần đăng nhập social thứ 2 trở đi (Must)
```gherkin
Given user đã có identity (GOOGLE, sub)
When đăng nhập Google lại (kể cả khi đã đổi email Gmail)
Then hệ thống nhận diện theo (provider, sub), không tạo bản ghi trùng, cập nhật last_login_at
```
Trace: TC-BE-ACC-03

## AC-06 — Từ chối email chưa xác minh (Must · Bảo mật)
```gherkin
Given Provider trả email chưa xác minh (Google email_verified=false, hoặc GitHub không có email verified)
When đăng nhập
Then không tạo/liên kết tài khoản
And chuyển về /auth/callback?error=email_unverified, FE hiển thị thông báo dịch theo ngôn ngữ
```
Trace: TC-BE-ACC-04, TC-BE-ACC-05, U-06

## AC-07 — Token không xuất hiện trên URL; code dùng 1 lần (Must · Bảo mật)
```gherkin
Given đăng nhập social thành công
Then URL redirect về FE chỉ chứa "code" ngẫu nhiên, không chứa JWT/refresh token/token của Provider
And code hết hạn sau 60 giây
And gọi exchange lần 2 với cùng code → HTTP 400, không phát token
And sau khi exchange, FE xóa query khỏi thanh địa chỉ
```
Trace: TC-BE-OTC-01..03, TC-BE-HDL-01, TC-FE-04, U-07

## AC-08 — Hủy / từ chối trên trang Provider (Must)
```gherkin
When user bấm Cancel trên trang consent Google hoặc GitHub
Then quay về /auth/callback?error=access_denied → chuyển /login với thông báo "Bạn đã hủy đăng nhập", không có token nào được lưu
```
Trace: TC-BE-HDL-02, TC-FE-05, U-05

## AC-09 — Phiên làm việc sau social login hoạt động như login thường (Must)
```gherkin
Given user đăng nhập bằng Google/GitHub
Then gọi API bảo vệ, refresh token, logout (blocklist access token + xóa refresh token) đều hoạt động
And Chat STOMP, phòng họp LiveKit, AI chat/RAG hoạt động với JWT này
```
Trace: TC-BE-INT-03, U-08, U-10

## AC-10 — API vẫn trả 401 JSON (Must · Hồi quy)
```gherkin
When gọi /api/v1/** không có token hoặc token sai
Then HTTP 401, Content-Type application/json, body ApiResponse lỗi — KHÔNG phải 302 redirect
```
Trace: TC-BE-SEC-01

## AC-11 — Hồi quy luồng email/password (Must)
```gherkin
Then register, login, refresh, logout, forgot/reset password hoạt động như trước
And login bằng mật khẩu với user chỉ có social (password_hash NULL) → 401 "Invalid email or password", không lỗi 500
```
Trace: TC-BE-REG-01..02, toàn bộ test hiện có

## AC-12 — Provider bật/tắt theo cấu hình (Must)
```gherkin
Given GITHUB_OAUTH_CLIENT_ID trống
Then ứng dụng vẫn khởi động bình thường
And GET /api/v1/auth/oauth2/providers không chứa "github"
And FE không hiển thị nút GitHub
```
Trace: TC-BE-CFG-01..02, TC-FE-02

## AC-13 — Chạy đúng trên production HF Spaces (Must)
```gherkin
Given BE ở https://taskpilot952476-taskpilot.hf.space, FE ở https://taskpilot-platform.netlify.app
Then redirect_uri gửi tới Provider có scheme https và đúng host HF (không "http://", không ":7860")
And luồng Google và GitHub hoàn tất từ Netlify
```
Trace: TC-BE-CFG-03, U-13, U-14

## AC-14 — Giao diện & đa ngôn ngữ (Must)
```gherkin
Then nút Google/GitHub hiện trên Login và Register, đúng brand guideline, có trạng thái loading/disabled
And hiển thị đúng trên Light, Dark, Glass và mobile 375×812 (không tràn, tương phản WCAG AA)
And mọi chuỗi mới có trong vi.json và en.json (parity 100%), không hardcode
```
Trace: TC-FE-01, TC-FE-06, U-11, U-12

## AC-15 — User social đặt mật khẩu (Should)
```gherkin
Given user chỉ có social (hasPassword=false)
Then trang Profile hiện "Đặt mật khẩu" (không yêu cầu mật khẩu cũ) và hiển thị provider đã liên kết
And sau khi đặt, đăng nhập email/password thành công
```
Trace: TC-BE-REG-03, U-09

## AC-16 — Không lộ secret & log an toàn (Must · Bảo mật)
```gherkin
Then không có client secret trong repo, log, response hoặc Swagger
And log không ghi access token của Provider, JWT hay one-time code đầy đủ
```
Trace: TC-SEC-SCAN-01, review checklist

## AC-17 — Tài khoản bị vô hiệu hóa (Could)
```gherkin
Given user bị admin khóa (nếu hệ thống có trạng thái khóa)
When đăng nhập social
Then chuyển về error=account_disabled
```
Trace: TC-BE-ACC-07

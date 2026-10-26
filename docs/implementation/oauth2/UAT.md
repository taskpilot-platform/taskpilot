# UAT — Security: OAuth2 & Social Login

**Người thực hiện**: Phan Lê Minh, Đặng Phú Thiện
**Môi trường**: Local (FE `http://localhost:5173`, BE `http://localhost:8080`) và Prod (FE `https://taskpilot-platform.netlify.app`, BE `https://taskpilot952476-taskpilot.hf.space`)
**Ảnh minh chứng**: `report/src/assets/auth-oauth2/<ID>_<mô-tả>.png`

## Chuẩn bị
- Tài khoản test:
  - **G1**: Gmail chưa từng đăng ký TaskPilot (có trong Google Test users nếu app ở chế độ Testing)
  - **G2**: Gmail đã đăng ký TaskPilot bằng mật khẩu, là MANAGER của ít nhất 1 project có task/chat/file
  - **GH1**: GitHub có email public · **GH2**: GitHub đặt email private (Settings → Emails → "Keep my email addresses private")
- Trước mỗi case: mở cửa sổ ẩn danh (tránh phiên Google/GitHub cũ) hoặc logout Provider.
- Mở DevTools → tab Network + Application để kiểm tra URL và localStorage.

---

| ID | Kịch bản | Các bước | Kết quả mong đợi | AC | Local | Prod | Ảnh |
|---|---|---|---|---|---|---|---|
| U-01 | Google — user mới | 1. `/login` → "Tiếp tục với Google" 2. Chọn G1 → Đồng ý 3. Logout, đăng nhập Google lần 2 | Vào `/dashboard`; Profile hiện tên + avatar Google; lần 2 không tạo user trùng (Admin → Users chỉ 1 bản ghi) | 01, 05 | ☐ | ☐ | U01_google_new_user |
| U-02 | GitHub — user mới | `/login` → "Tiếp tục với GitHub" → GH1 → Authorize | Vào `/dashboard`, tên = name hoặc login GitHub | 02 | ☐ | ☐ | U02_github_new_user |
| U-03 | Liên kết tài khoản cũ | Đăng nhập Google bằng G2 | Thấy đúng project/task/chat/file cũ, role MANAGER giữ nguyên; logout → login mật khẩu cũ vẫn được; avatar cũ không đổi | 04 | ☐ | ☐ | U03_link_existing_account |
| U-04 | GitHub email private | Đăng nhập GitHub bằng GH2 | Thành công, email trong Profile = email primary GitHub | 03 | ☐ | ☐ | U04_github_private_email |
| U-05 | Hủy consent | Bấm Cancel trên trang Google; lặp lại với GitHub | Về `/login`, toast "đã hủy đăng nhập", localStorage không có token | 08 | ☐ | ☐ | U05_cancel_consent |
| U-06 | Email chưa xác minh | GitHub account chưa verify email (hoặc mô phỏng bằng test) | Toast `email_unverified`, không tạo user | 06 | ☐ | — | U06_email_unverified |
| U-07 | Không lộ token, code 1 lần | 1. Trong Network, xem redirect `/auth/callback?code=…` 2. Copy URL, đăng nhập xong mở lại URL đó ở tab mới 3. Chờ > 60s với code mới (chặn exchange bằng DevTools "Block request URL") rồi thử | URL chỉ có `code`, không có `eyJ…`; thanh địa chỉ sau đó sạch query; mở lại → lỗi, không đăng nhập; code quá 60s → lỗi | 07 | ☐ | ☐ | U07_one_time_code |
| U-08 | Phiên sau social login | Đăng nhập Google → chờ/ép refresh (xóa access token trong localStorage, giữ refresh) → thao tác → Logout → bấm Back | Refresh tự động OK; logout xong không truy cập lại được trang bảo vệ | 09 | ☐ | ☐ | U08_session_refresh_logout |
| U-09 | Đặt mật khẩu cho user social | User G1 → Profile → "Đặt mật khẩu" → logout → login email + mật khẩu mới | Profile hiển thị provider đã liên kết; đăng nhập mật khẩu thành công | 15 | ☐ | ☐ | U09_set_password |
| U-10 | Các phân hệ khác với JWT social | User GitHub: gửi chat project, mở "Họp ngay" (LiveKit), hỏi AI chat, tìm Knowledge | Tất cả hoạt động, không 401 | 09 | ☐ | ☐ | U10_modules_with_social_jwt |
| U-11 | Giao diện 3 theme + mobile | Xem `/login`, `/register` ở Light/Dark/Glass; DevTools 375×812 | Nút rõ ràng, không tràn, tương phản tốt | 14 | ☐ | ☐ | U11_theme_light/dark/glass/mobile |
| U-12 | Đa ngôn ngữ | Chuyển vi ↔ en trên `/login` và các toast lỗi | Không còn chuỗi tiếng Anh/khóa i18n thô | 14 | ☐ | — | U12_i18n |
| U-13 | Redirect URI prod | Trên Netlify bấm Google, xem URL trang consent | `redirect_uri=https://taskpilot952476-taskpilot.hf.space/login/oauth2/code/google`; không lỗi `redirect_uri_mismatch` | 13 | — | ☐ | U13_prod_redirect_uri |
| U-14 | GitHub prod | Trên Netlify bấm GitHub | Dùng OAuth App "TaskPilot" (prod), về Netlify `/dashboard` | 13 | — | ☐ | U14_prod_github |
| U-15 | Hồi quy email/password | Register → login → forgot password → reset → login | Như trước giai đoạn này | 11 | ☐ | ☐ | U15_regression_password |
| U-16 | Tắt provider | Local: để trống `GITHUB_OAUTH_CLIENT_ID`, restart BE | App khởi động; `/login` chỉ còn nút Google | 12 | ☐ | — | U16_provider_disabled |

---

## Biên bản nghiệm thu

| Hạng mục | Kết quả |
|---|---|
| Số case Pass / Tổng | __ / 16 |
| Lỗi tồn đọng (ID, mức độ) | |
| Ngày nghiệm thu local | |
| Ngày nghiệm thu prod | |
| Kết luận | ☐ Đạt ☐ Đạt có điều kiện ☐ Không đạt |

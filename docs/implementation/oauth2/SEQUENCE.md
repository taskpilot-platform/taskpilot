# Sơ đồ Tuần tự (Sequence Diagram) — OAuth2 & Social Login (Google & GitHub)

Tài liệu thiết kế kiến trúc và tuần tự dành cho báo cáo chuyên đề và Khóa luận Tốt nghiệp.

---

## 1. Kiến trúc Tổng thể Luồng Xác thực OAuth2 qua Single-Use One-Time Code

Quy trình xác thực tuân thủ khuyến nghị bảo mật OAuth 2.0 Security Best Current Practice (BCP) của IETF: **Không truyền trực tiếp Access Token / JWT qua URL / Query parameters**. Thay vào đó, Backend trả về một mã trao đổi dùng 1 lần (One-Time Code, TTL 60s) để Frontend tiêu thụ thông qua POST payload an toàn.

```mermaid
sequenceDiagram
    autonumber
    actor User as Người dùng (Browser)
    participant FE as TaskPilot Frontend (SPA)
    participant BE as TaskPilot Backend (Spring Boot)
    participant IdP as Nhà cung cấp (Google / GitHub)
    participant DB as PostgreSQL Database

    User->>FE: Bấm "Đăng nhập với Google / GitHub"
    FE->>BE: Chuyển hướng: GET /oauth2/authorization/{provider}
    Note over BE: Lưu authorization request vào HttpOnly Cookie (stateless)
    BE-->>User: 302 Redirect tới trang đăng nhập của Google / GitHub
    User->>IdP: Xác thực & Cấp quyền (Consent)
    IdP-->>BE: 302 Redirect về: GET /login/oauth2/code/{provider}?code=...&state=...
    Note over BE: Khôi phục state từ Cookie, đối soát CSRF & trao đổi Authorization Code lấy Token của IdP
    BE->>IdP: POST /token (Code exchange)
    IdP-->>BE: id_token / access_token
    BE->>IdP: Lấy UserInfo (hoặc email phụ từ API nếu là GitHub)
    IdP-->>BE: User attributes (email, email_verified, name, avatar, sub)
    
    alt Email chưa xác minh (email_verified == false)
        BE-->>User: 302 Redirect về FE: /auth/callback?error=email_unverified
    else Email hợp lệ & đã xác minh
        BE->>DB: Tra cứu user_identities(provider, provider_subject)
        alt Đã có danh tính
            BE->>DB: Cập nhật last_login_at
        else Chưa có danh tính nhưng email trùng user sẵn có
            BE->>DB: Liên kết danh tính mới vào User hiện tại
        else Người dùng hoàn toàn mới
            BE->>DB: Tạo mới User (password=null, role=USER) & UserIdentity
        end
        Note over BE: Sinh mã trao đổi một lần (One-Time Code, TTL 60s) lưu trong Memory Store
        BE-->>User: 302 Redirect về FE: /auth/callback?code={oneTimeCode}
    end

    User->>FE: Trình duyệt tải trang /auth/callback?code={oneTimeCode}
    FE->>BE: POST /api/v1/auth/oauth2/exchange { "code": "{oneTimeCode}" }
    Note over BE: Tiêu thụ nguyên tử (Atomic Consume) mã 1 lần (chống Replay Attack)
    BE-->>FE: 200 OK: { accessToken, refreshToken, user profile }
    Note over FE: Xóa query param khỏi URL bằng window.history.replaceState
    Note over FE: Lưu JWT vào Zustand store & LocalStorage
    FE-->>User: Chuyển hướng tới /dashboard
```

---

## 2. Chi tiết Luồng Phân giải & Liên kết Tài khoản (Account Resolution Matrix)

```mermaid
flowchart TD
    Start(["Nhận OAuth2UserInfo từ Provider"]) --> CheckVerified{"Email đã được xác minh<br/>(email_verified == true)?"}
    
    CheckVerified -- "Không" --> RejectUnverified["Ném lỗi email_unverified<br/>Chuyển hướng lỗi về FE"]
    CheckVerified -- "Có" --> QueryIdentity{"Đã tồn tại trong<br/>user_identities (provider, subject)?"}
    
    QueryIdentity -- "Đã tồn tại" --> TouchLogin["Cập nhật last_login_at<br/>Trả về User liên kết"]
    QueryIdentity -- "Chưa tồn tại" --> QueryUser{"Email có trùng với<br/>tài khoản nào trong users?"}
    
    QueryUser -- "Trùng email sẵn có" --> LinkIdentity["Tạo UserIdentityEntity mới<br/>Liên kết vào User hiện tại<br/>(Cập nhật avatar nếu thiếu)"]
    QueryUser -- "Email mới hoàn toàn" --> ProvisionUser["Tạo UserEntity mới:<br/>- password_hash = NULL<br/>- role = USER<br/>- status = AVAILABLE<br/>Tạo UserIdentityEntity mới"]
    
    TouchLogin --> IssueCode["Sinh One-Time Code (TTL 60s)<br/>Redirect về /auth/callback?code=..."]
    LinkIdentity --> IssueCode
    ProvisionUser --> IssueCode
```

---

## 3. Đặc tả An toàn Bảo mật (Security Invariants)

1. **Không lộ Secret**:
   - Client Secret của Google & GitHub nằm hoàn toàn ở Backend (`taskpilot/.env`), không bao giờ chuyển xuống Frontend hay Git.
2. **Bảo vệ Token trên URL**:
   - Sử dụng One-Time Exchange Code (TTL 60s, Single-Use). Ngay cả khi URL bị lưu vào Browser History, mã đã bị tiêu thụ và vô hiệu hóa ngay lập tức.
3. **Chống giả mạo Email (Account Takeover Protection)**:
   - Chỉ tự động liên kết tài khoản khi và chỉ khi email được nhà cung cấp chứng nhận `email_verified == true`. Đối với GitHub nếu email để private, hệ thống truy vấn endpoint bảo mật `https://api.github.com/user/emails` và chỉ chấp nhận email có `verified: true, primary: true`.
4. **Hỗ trợ người dùng không có mật khẩu (Password-less Social Users)**:
   - Cột `users.password_hash` được chuyển thành nullable.
   - Endpoint `POST /api/v1/auth/login` kiểm tra null an toàn, trả về lỗi nghiệp vụ Bad Credentials thay vì sập hệ thống (NPE).
   - Trang Profile cung cấp giao diện "Thiết lập mật khẩu" (không đòi hỏi mật khẩu cũ) cho người dùng đăng nhập lần đầu qua Google/GitHub muốn bổ sung mật khẩu để đăng nhập trực tiếp bằng email.

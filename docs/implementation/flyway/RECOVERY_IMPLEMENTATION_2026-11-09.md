# Bản sửa cứu hộ đã hiện thực — 09/11/2026

> **Bản ghi lịch sử 09/11:** các kết luận dưới đây mô tả thời điểm điều tra/hotfix ban đầu. Ngày 10/11, nguồn đã tiếp nhận Flyway V42, validate đạt và app mới đã deploy/live smoke thành công; xem [kết quả phát hành cuối](RECOVERY_RELEASE_2026-11-10.md). Dùng [quy trình nhóm](../../development/FLYWAY_TEAM_WORKFLOW.md) cho migration mới, không lặp lại hướng recovery thử nghiệm cũ.

> **Cập nhật sau khi triển tiếp:** hotfix ứng dụng đã merge PR #51 và chạy trên Hugging Face; kiểm tra HTTP thật đạt lúc 23:10 +07:00. Đã backup/restore dữ liệu `public` thành công và xác nhận history không đổi. Phần recovery Flyway bên dưới vẫn là rehearsal, chưa cutover. Các dòng “chưa commit/push/deploy” mô tả mốc ban đầu. Xem [báo cáo phát hành hotfix](HOTFIX_RELEASE_2026-11-09.md) và [việc còn lại](RECOVERY_FOLLOWUP_2026-11-09.md).

## Phạm vi và trạng thái

- Nhánh `fix/flyway-safe-recovery`, nền `main` tại `759ede5`.
- Không thay đổi trực tiếp các migration lịch sử, cấu hình Flyway mặc định, workflow deploy hoặc CSDL nguồn.
- Chưa commit/push/deploy. CSDL nguồn không có lệnh ghi từ các bước cứu hộ này; dữ liệu V41 đã thay đổi trước đó không bị hoàn tác tự động.
- Bản sửa ứng dụng đã được kiểm thử trong Docker cô lập. Phục hồi Flyway được đóng gói theo hướng **opt-in**, chưa là cutover production hoàn tất.

## Thay đổi mã nguồn

| Tệp | Nội dung và lý do |
|---|---|
| `GlobalExceptionHandler.java` | Không dùng thông điệp “aborted” để nhận diện client disconnect; lỗi JDBC/DataAccess luôn được xử lý như lỗi backend. Xung đột toàn vẹn trả 409 với thông điệp an toàn. IO disconnect thực sự vẫn được phân biệt. |
| Các bundle `i18n/messages*.properties` | Thêm thông điệp xung đột dữ liệu Việt/Anh. |
| `AuthService.java` | Chuẩn hóa email bằng trim và `Locale.ROOT` cho đăng ký/đăng nhập/quên mật khẩu; chỉ đổi dữ liệu đầu vào, không chạy UPDATE tài khoản hiện có. |
| `UserRepository.java` | Thêm `existsByEmailIgnoreCase` để kiểm tra trước khi lưu; unique index vẫn là hàng rào khi request chạy đua. |
| `GlobalExceptionHandlerTest`, `AuthServiceRecoveryTest` | Kiểm thử JDBC aborted, socket cause trong lỗi DB, IO thật, trả 409 không lộ SQL, locale và email khác hoa/thường. |
| `application-recovery.yml`, `RecoveryProfileSafetyTest` | Profile tường minh dùng `flyway_schema_history_v2`, bundle ngoài bắt buộc, validation bật, không tự baseline, không out-of-order, cấm clean và Hibernate chỉ validate. Không tự bật recovery từ profile mặc định. |
| `tools/flyway-recovery/prepare_bundle.py` | Tạo bundle offline từ dump schema-only đáng tin cậy; không đọc `.env` hoặc kết nối DB; xuất manifest SHA-256 và trạng thái chưa duyệt production. |
| `tools/flyway-recovery/preflight.sql` | Thu metadata chỉ đọc để kiểm tra môi trường trước cutover; không baseline hay migrate. |
| `tools/flyway-recovery/rehearse_sensitive_rls.sql` | Chỉ mô phỏng hardening trong database lab allowlist, luôn rollback; không phải migration production. |

## Kết quả đã xác nhận

### Kiểm thử module và artifact

- `mvn -B -o -pl taskpilot-users -am test`: thành công ở infrastructure, contracts và users; mốc chạy đầu có 78 ca được thống kê, gồm 1 live test bị skip và không có failure/error. Sau đó có thêm kiểm thử bản dịch thông điệp xung đột.
- Build/package toàn reactor với các test chọn lọc handler/auth/profile/schema audit: thành công. Mốc chạy đó có 22 test được chọn, không lỗi; không tuyên bố đây là toàn bộ test suite.
- Python: **7/7** kiểm thử công cụ bundle đạt; có chặn snapshot thiếu đối tượng V41 để không gắn nhãn sai phiên bản. Bundle được tạo từ dump V41 và xác minh SHA-256 thành công.

### Hồi quy rộng hơn sau bản sửa cuối

Đã chạy `mvn -B -o package` toàn reactor với selector loại riêng bốn lớp kiểm thử gọi dịch vụ thật (`GoogleDriveLiveCheckTest`, `GoogleAiEmbeddingModelVerificationTest`, `GeminiBatchQuotaExperimentTest`, `RealDocumentPipelineVerificationTest`). Tổng hợp XML Surefire, bỏ các report cũ của lớp bị loại:

| Module | Tests | Failures / errors / skipped |
|---|---:|---|
| infrastructure | 24 | 0 / 0 / 0 |
| contracts | 42 | 0 / 0 / 0 |
| users | 12 | 0 / 0 / 0 |
| ai | 236 | 0 / 0 / 0 |
| projects | 41 | 0 / 0 / 0 |
| app | 15 | 0 / 0 / 0 |
| **Tổng trong đợt chạy** | **370** | **0 / 0 / 0** |

BUILD SUCCESS. Có kiểm thử PostgreSQL Testcontainers thật trong đợt chạy; không dùng credential nguồn cho các live-test đã loại. Không gọi đây là xác minh toàn bộ tích hợp với dịch vụ ngoài hay toàn bộ dữ liệu production.

### Bundle candidate thực tế trên PostgreSQL 17.11

Bundle được tạo bằng công cụ trong kho mã, không sửa checksum để bắt chước lịch sử cũ. Thử độc lập vào `taskpilot_recovery_fresh_v41` và `taskpilot_recovery_existing_v41`:

```text
CANDIDATE_FRESH_MIGRATIONS=2
CANDIDATE_FRESH_VALID=true
CANDIDATE_IMPLICIT_ADOPTION_BLOCKED=true
CANDIDATE_EXISTING_MIGRATIONS=1
CANDIDATE_EXISTING_VALID=true
CANDIDATE_LEGACY_HISTORY_PRESERVED=true
CANDIDATE_RESTART_MIGRATIONS=0
CANDIDATE_CLEAN_BLOCKED=true
```

Database đã có schema bị từ chối tự tiếp nhận; chỉ sau bước baseline tường minh trong harness local mới chạy migration tiếp nối. Digest của lịch sử cũ được giữ nguyên. Không có lệnh `repair` hay replay SQL chuẩn hóa email V41.

### Chạy Spring Boot với profile mới và kiểm tra HTTP thật

Ứng dụng được build lại từ nhánh sửa, chạy với `dev,recovery`, lấy bundle qua `TASKPILOT_RECOVERY_MIGRATIONS` trong Docker network internal, không có `.env`/credential thật. Không override tên history bằng CLI trong hai lần này, để xác minh profile mới thực sự có hiệu lực.

| Kiểm tra | CSDL trắng | Bản phục dựng có lịch sử |
|---|---|---|
| History được bind | `flyway_schema_history_v2` | `flyway_schema_history_v2` |
| Khởi động + health | Đạt / HTTP 200, DB UP | Đạt / HTTP 200, DB UP |
| Register tài khoản giả | 201 | 201 |
| Login | 200 | 200 |
| Profile bằng JWT | 200 | 200 |
| Đăng ký lại email khác hoa/thường | **409**, JSON 111 byte | **409**, JSON 111 byte |

Trước sửa cùng loại tình huống trả HTTP 200/body 0 byte dù SQL thất bại. Log artifact mới có build commit `759ede5`, working tree có thay đổi chưa commit; không gọi nó là artifact release đã duyệt.

Sau khi đóng gói lại bản cuối cùng, kiểm tra HTTP thêm tình huống **hai request đăng ký đồng thời cùng email**: trả một 201 và một 409 có JSON, số dòng được lưu đúng **1**. Lần này đi qua lỗi unique JDBC thực sự (không chỉ pre-check) và không còn HTTP thành công giả. Log container `taskpilot-recovery-fixed-race-20261109`, exit 0.

### RLS và guard lab

- SQL rehearsal bật RLS được cho hai bảng trong transaction local rồi rollback; kiểm tra sau đó cả hai trở về trạng thái RLS cũ (0 bảng còn bật do rehearsal).
- Chạy nhầm vào database không có tiền tố lab bị từ chối trước thay đổi bảng.
- Preflight chạy thành công trên database lab và rollback transaction đọc.
- Chưa thay đổi quyền nguồn, chưa coi kiểm thử superuser local là bằng chứng tương thích với mọi role Supabase thật.

## Điều kiện còn thiếu trước khi khẳng định đã cứu xong production

- Backup dữ liệu nguồn/PITR đã được xác minh phục hồi, không chỉ dump schema/history.
- Review role/owner/ACL, schema ngoài `public`, seed và snapshot trước khi cấp phép cutover; hai số migration candidate chưa được dành số chính thức.
- Đối soát tài khoản có hậu tố `_dup_` sau V41 qua quy trình dữ liệu được phép, không tự đổi email hàng loạt.
- Kiểm thử đầy đủ các nghiệp vụ và tích hợp thật; OAuth/AI/S3/LiveKit không được dùng credential thật trong lab.
- Hardening RLS cần một migration và rollout riêng sau khi kiểm tra role backend/đường PostgREST; rehearsal luôn rollback, chưa bảo vệ nguồn.
- Không tự bật validation trên lịch sử cũ, không tự kích hoạt recovery profile trên môi trường đang chạy. Theo [runbook](RECOVERY_RUNBOOK.md) và duyệt từng bước.

## Bằng chứng local

- Container `taskpilot-recovery-fixed-fresh-20261109`, `taskpilot-recovery-fixed-existing-20261109` giữ log kiểm tra ứng dụng sửa, đều exit 0 ở hai lần đầu.
- Thư mục lab được giữ tại `%TEMP%/opencode/taskpilot-flyway-isolated-20261109/`, gồm `recovery-candidate-bundle/`, `candidate-recovery-test.log`, `candidate-preflight.txt` và log hồi quy.
- Không đưa schema dump/history dump hoặc credential nguồn vào Git.

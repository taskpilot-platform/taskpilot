# Flyway recovery — kết quả vận hành 2026-11-10

## Trạng thái

**CSDL nguồn đã tiếp nhận và validate Flyway V42; bản app mới đã deploy và kiểm tra live thành công.** PR [#52](https://github.com/taskpilot-platform/taskpilot/pull/52) đã merge vào main `39f03e73419dfa269fac43fa5b3b09c2f5f2c3c5`; CI/build/deploy pipeline thành công. Runtime Hugging Face `29f411547f71acf09c6be409c9b7281d38d081ce` đã RUNNING và các file policy/contract/migration được đối chiếu khớp bản phát hành.

## Thay đổi thực tế trên nguồn

- Giữ nguyên `public.flyway_schema_history`: 41 dòng, digest khớp trước chuyển đổi và backup.
- History đang dùng: `taskpilot_migrations.flyway_schema_history_v2`, gồm V41 `BASELINE` và V42 `SQL`, đều thành công; validate xác nhận không có migration chờ.
- V41 được tiếp nhận bằng operator tường minh, ràng buộc hash snapshot đã duyệt; không chạy lại SQL lịch sử, không `repair`, không tự baseline khi app khởi động, chặn `clean`.
- V42 thêm index lịch họp đã kiểm tra định nghĩa. Không viết lại email, không cập nhật/xóa dòng nghiệp vụ. Digest toàn bộ bảng nghiệp vụ trước/sau giữ nguyên.
- Khóa ghi thực tế: **21.538 ms (~21,5 giây)**. Không cam kết zero downtime; thao tác ghi có thể chờ trong khoảng khóa.
- `anon`/`authenticated` không có quyền dùng schema metadata mới. CLI kiểm tra cả quyền bảng history.
- Kết nối session dùng cho operator đã hoạt động. Đường pooler runtime cũ cũng đã được kiểm tra bằng đúng artifact: validate V42, migrate chạy **0** migration. Không đổi endpoint mặc định của app.
- Kiểm tra sau chuyển đổi: app health UP, 458 tài khoản, legacy history 41 dòng, index mới hợp lệ.

## Luồng thống nhất sau sửa

App và operator dùng chung cấu hình và bundle đóng gói `classpath:db/reconciled`. CSDL trắng chạy V41/V42; nguồn hiện tại baseline đã duyệt ở V41 rồi chỉ chạy V42. Profile `recovery` là alias của cùng policy, không còn bundle ngoài hoặc luồng migration thứ hai. File lịch sử cũ được giữ để đối chiếu.

Migration SQL V43+ đi theo Flyway thông thường, được kiểm tra checksum và Hibernate mapping; không phải sửa Java allowlist hoặc hash schema V41 cho từng lần thêm migration hợp lệ.

## Backup và diễn tập

- Backup mới: `flyway-release-20261110-092420/public-complete.dump`, SHA-256 `c20c3c8f60b12e33ebebc365483e6352b0c0ca823274613fde957c15cf73b13c`, 5.894.782 byte.
- Phạm vi: toàn bộ structure/data của `public`, giữ owner/ACL gốc trong archive. Đây không phải backup toàn bộ Supabase/PITR; không thay đổi `auth`, `storage` hoặc schema ngoài phạm vi.
- Backup nằm ngoài Git, trong thư mục Windows hạn chế quyền. Không commit dump/log/credential/dòng dữ liệu nguồn.
- Đã phục hồi thành công 39 bảng public, 458 tài khoản, 41 dòng history; so toàn bộ bảng với nguồn bằng row count và digest có thứ tự cố định: khớp.
- Exact final image tiếp nhận V42 trên bản phục hồi trong 2.037 ms, validate thành công, giữ nguyên dữ liệu và lịch sử.
- App prod chạy bằng UID 1000, mạng cô lập, với CSDL phục hồi: health/auth/refresh/admin/reset password/race/project/task/meeting đều đạt. Hai đăng ký đồng thời trả 201/409, chỉ lưu một tài khoản.
- Bản hotfix cũ cũng khởi động và vượt cùng smoke trên CSDL đã tiếp nhận: đã thử đường quay lại app cũ.

## Kiểm thử

- Backend: **408 test**, 0 lỗi/fail/skip trong bộ hồi quy local không gọi nhà cung cấp thật; có 24 test PostgreSQL recovery. CI, Docker build và CodeQL của release đã đạt.
- Frontend: **89 unit test**, build thành công, **149/149 mocked UI test**, không skip/flaky. Chỉ sửa test để khớp ngôn ngữ/modal/endpoint tải file hiện tại; không đổi product, dependency hoặc tắt test. Commit local `24bcd72`, nhánh `test/recovery-app-regression`.
- Integration trình duyệt với backend thật: đăng nhập, lưu project/task/meeting, đọc dữ liệu qua API thật, kết thúc meeting và hiển thị trạng thái kết thúc, dọn project thử nghiệm; đạt. Không mock response API. Đây là kiểm tra các luồng được liệt kê, không phải mọi chức năng của app.

## Hai lần dừng an toàn trước lần tiếp nhận thành công

Lần đầu bị `REVIEWED_SCHEMA_MISMATCH` trước khi tạo metadata/migrate. Đối chiếu chỉ đọc cho thấy đúng một index queue có cách deparse array cast khác giữa PostgreSQL nguồn và bản dump/restore. Đã chuẩn hóa riêng hai định nghĩa đầy đủ, có fixture cấu trúc nguồn tái hiện RED và test vẫn chặn đổi cột/điều kiện index; hash reference không thay đổi. Contract nguồn sau sửa đã đạt ở chế độ chỉ đọc.

Một lần CLI sau đó trả `PSQLException` mà wrapper không xuất chi tiết; không suy đoán nguyên nhân. Lần gọi tiếp có trace dùng chính class triển khai từ artifact đã tiếp nhận thành công; kiểm tra dữ liệu/lịch sử và validate sau đó đều đạt. Log các lần chạy được giữ riêng, không bỏ qua guard hoặc sửa tay history để vượt lỗi.

## Xác minh bản app đã phát hành

Quan sát trực tiếp lúc 09:58 ngày 2026-11-10 (UTC+7) trên [app đang chạy](https://taskpilot952476-taskpilot.hf.space):

- Đăng ký 201; đăng nhập, profile và refresh 200; đăng ký email trùng 409 có JSON; health UP.
- Đối chiếu xác nhận app live ghi đúng CSDL nguồn đã kiểm tra.
- Đã xóa tài khoản thử cùng refresh token của riêng tài khoản đó. Số tài khoản trước/sau đều 458.
- Sau cleanup, digest **toàn bộ dữ liệu nghiệp vụ** và legacy history vẫn khớp trước chuyển đổi.
- CLI validate lại sau deploy: V42, pending=0. History cũ vẫn 41 dòng.
- Đã dừng app/browser server lab và xóa container bản sao dữ liệu phục hồi cùng volume diễn tập được xác minh thuộc lượt chạy; giữ backup và bằng chứng riêng ngoài Git.

Bằng chứng riêng: thư mục `%LOCALAPPDATA%/TaskPilotRecovery/flyway-release-20261110-092420`, gồm manifest, restore/adoption logs, source observations, pooled startup validation, CI archive, runtime provenance và live smoke results. Không cần phát tán các file chứa metadata nguồn/credential để xem báo cáo này.

## Rủi ro còn lại, tách khỏi sự cố Flyway

- Quyền/RLS có sẵn của `user_identities` và `project_meetings` vẫn cần đợt kiểm tra/hardening riêng: metadata trước đó cho thấy tắt RLS và client có SELECT. Chưa chứng minh đã đóng đường phơi lộ qua PostgREST. Việc phục hồi Flyway không tự giải quyết rủi ro này; không hạ mức nó thành lỗi hình thức.
- Chưa chạy thử nhà cung cấp thật cho Google Drive, embedding/LLM, S3, OAuth và LiveKit media/recording. Không gắn nhãn các tích hợp đó đã pass.
- Các cảnh báo Hibernate/tooling không ảnh hưởng luồng đã kiểm chứng không được mở rộng thành dự án chuẩn hóa enterprise trong lần cứu app này.

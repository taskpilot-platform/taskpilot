# Sửa các phát hiện sau verify Flyway — 10/11/2026

> **Cập nhật tiếp nối:** giữ nguyên các sửa lỗi/index/schema đã kiểm chứng trong tài liệu này. Bộ chốt mới nhất có thêm test giữ endpoint app đang chạy và cho phép V43+ theo Flyway thông thường; không còn yêu cầu đổi Java/contract cho mỗi migration tiếp theo. Hồi quy mới nhất: 405 test backend đạt; frontend 89 unit và 149 UI regression đạt. Cutover nguồn được ghi riêng khi thực hiện xong, không suy diễn từ các kết quả local.

## Kết quả

Đã sửa ba phát hiện trong [báo cáo độc lập](INDEPENDENT_REVIEW_2026-11-10.md) trên working tree của `fix/flyway-safe-recovery`, giữ nguyên các thay đổi có sẵn. Không sửa SQL migration V41/V42 trong lượt này, không ghi CSDL nguồn, không push/deploy và không tự tạo commit chứa phần việc dang dở của người khác.

**Mã sửa đã qua hồi quy và build; chưa có cutover production.**

## Thay đổi

| Tệp | Sửa gì |
|---|---|
| `taskpilot-app/src/main/java/com/taskpilot/app/migration/RecoveryAdoption.java` | Kiểm tra đầy đủ index canonical email: đúng bảng, biểu thức, btree, không partial/include, unique/valid/ready/live/immediate. Kiểm tra schema độc lập trước tạo history; xác minh schema ở startup và validate hoàn tất. Approval mới ràng buộc thêm hash contract. |
| `taskpilot-app/src/main/java/com/taskpilot/app/migration/ReviewedSchemaContract.java` | Contract cấu trúc độc lập dựng từ baseline V41 đóng gói trên PostgreSQL 17, không lấy target làm chuẩn. So bảng, cột/kiểu/typmod/default/nullability/identity/generated/collation, constraints, index, enum và cấu hình sequence. Bỏ qua dữ liệu/sequence counter/owner/ACL/RLS; index V42 chỉ optional trước migrate và bắt buộc tồn tại đúng sau migrate. |
| `taskpilot-app/src/main/java/com/taskpilot/app/migration/MigrationDatabaseConfiguration.java` | External migration location bị từ chối rõ ràng thay vì âm thầm override. Giữ một chính sách bundle đóng gói/schema history riêng. |
| `taskpilot-app/src/main/resources/application-recovery.yml` | Chuyển profile cũ thành alias rõ nghĩa của chính sách đóng gói, không còn yêu cầu bundle ngoài. |
| `taskpilot-app/src/test/java/com/taskpilot/app/migration/ReconciledFlywayIntegrationTest.java` | Thêm các ca tái hiện index email sai, schema drift đã được inspect lại, drift sau V42, mất index V42 và kiểm tra contract từ baseline trắng độc lập. |
| `taskpilot-app/src/test/java/com/taskpilot/app/migration/RecoveryProfileSafetyTest.java` | Test cấu hình có hiệu lực qua customizer, từ chối external location và alias không cần biến bundle ngoài; không chỉ đọc YAML. |
| `docs/implementation/flyway/RECOVERY_RUNBOOK.md` | Quy trình mới mô tả đúng private history, packaged bundle, inspect/approval/adopt/validate, thứ tự maintenance/cutover và retry. Giữ nguyên runbook cũ dưới nhãn SUPERSEDED. |

Hash contract: `a74f4cfac91fa1184ac18284fadf558ca6b7bc64e1276c84fdf99c6fa5876da5`. Thay đổi cấu trúc về sau cần review contract/migration policy; không đổi hash chỉ để vượt guard.

## Bằng chứng trước và sau sửa

- Trước sửa, test mới chạy thật: **25 ca, 10 failures, 0 errors/skips**, đúng các lỗ hổng đã phát hiện (`repair-red.log`).
- Sau sửa lần đầu, tập trung: **28 ca đạt**; sau đó bổ sung hai ca mất index V42 và reference contract, đều nằm trong hồi quy cuối.
- Hồi quy cuối `mvn -B -o test` loại riêng bốn lớp gọi provider thật: **403 tests, 0 failures, 0 errors, 0 skipped**.
- Trong đó migration integration **23 ca PostgreSQL thật**; không bỏ qua do thiếu Docker. Có các ca sai schema/index bị chặn **trước khi tạo history mới**, và đường tiếp nhận hợp lệ/restart/resume vẫn đạt.
- Python tooling: **7/7 đạt**.

| Module | Tests | Failures / errors / skipped |
|---|---:|---|
| infrastructure | 24 | 0 / 0 / 0 |
| contracts | 42 | 0 / 0 / 0 |
| users | 16 | 0 / 0 / 0 |
| ai | 236 | 0 / 0 / 0 |
| projects | 41 | 0 / 0 / 0 |
| app | 44 | 0 / 0 / 0 |
| **Tổng** | **403** | **0 / 0 / 0** |

Build `mvn -B -o package -DskipTests` từ bản sao mã sạch thư mục output, không copy `.env`/`.git`/`target`, đạt toàn reactor và Spring Boot repackage. Đối chiếu byte của toàn bộ source/resource/test/POM: **0 khác biệt** so với working tree đã chạy test. Đây là build riêng để tránh lỗi rename JAR của output đang có trên Windows; không dừng tiến trình của người dùng.

## Chạy ứng dụng thật bằng artifact sau sửa

Đã chạy artifact mới trong Docker network internal, chỉ dùng credential giả/local, không mount `.env` nguồn:

| Kiểm tra | DB trắng mới | DB fixture đã tiếp nhận |
|---|---|---|
| Profile | `dev,recovery` — alias không cần biến bundle ngoài | `dev` — chính sách mặc định |
| History/location thực tế | `taskpilot_migrations` / `classpath:db/reconciled` | Giống DB trắng |
| Version | 42 | 42 |
| Health / register / login / profile | 200 / 201 / 200 / 200 | 200 / 201 / 200 / 200 |
| Duplicate khác hoa/thường | 409 JSON | 409 JSON |
| Process exit | 0 | 0 |

Thử lại DB thiếu `users.avatar_url` nhưng đã có history V42 từ thí nghiệm trước: startup giờ bị chặn bởi **`REVIEWED_SCHEMA_MISMATCH` trong migration guard**, không còn được báo validate hoàn tất rồi mới vỡ ở Hibernate.

## Đối chiếu với backup đã phục dựng

Trên container bản phục dựng sẵn có `taskpilot-recovery-fullcopy-20261109`, chỉ chạy transaction **READ ONLY** lấy metadata cấu trúc, không lấy user rows hoặc in credential. Hash schema thực tế khớp chính xác reference V41 ở trên. Vì vậy contract không chỉ được kiểm tra trên fixture tự sinh; cấu trúc bản phục dựng dữ liệu thực tế này cũng tương thích.

Đây **không** phải kiểm tra trực tiếp nguồn hiện tại, restore backup mới hoặc chạy lại toàn bộ browser/UAT trên bản sao dữ liệu thật. Không nâng cấp phạm vi bằng chứng vượt điều đã thực hiện.

## Giới hạn và bước vận hành còn lại

- Còn phải review/đóng gói toàn bộ dirty tree thành artifact phát hành có định danh, kiểm tra restore/backup hiện hành, chạy đúng artifact trên bản phục dựng và phê duyệt maintenance trước khi adopt nguồn.
- Session-pooler/login role mới chỉ có test local; không tuyên bố đã kiểm chứng endpoint Supabase thật trong lượt này.
- Các bảng hiện có không tự được harden RLS/ACL chỉ vì baseline trắng có ACL chặt hơn.
- Transaction khóa ghi có giới hạn idle 45 giây; các bước Flyway trên connection riêng không nguyên tử với nó. Runbook yêu cầu quiesce writer, không hứa zero downtime, và dừng/đối soát khi lỗi thay vì auto-retry.
- [SUPERSEDED] Inventory hiện đã cho phép migration V43+ theo quy trình Flyway thông thường; giới hạn V41/V42 chỉ còn ở operator tiếp nhận lần đầu.

## Bằng chứng local

`C:/Users/homepc/AppData/Local/Temp/opencode/taskpilot-independent-review-20261110/`

- `repair-red.log`, `repair-focused.log`, `repair-regression.log`, `repair-build.log`
- `contract-reference.log`, `GenerateReviewedContract.java`
- `repaired-app-fresh.log`, `repaired-app-existing.log`, `repaired-app-missing-column.log`
- `repaired-source/`, `repaired-artifact/`

Không commit log, dump, credential hoặc dữ liệu nguồn vào repository.

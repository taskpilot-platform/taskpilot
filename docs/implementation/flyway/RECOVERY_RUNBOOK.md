# Flyway recovery — quy trình hiện hành sau kiểm tra độc lập 10/11/2026

> **Trạng thái đã phát hành:** nguồn đã được tiếp nhận V42 và bản app mới đã kiểm tra live; xem [kết quả cuối](RECOVERY_RELEASE_2026-11-10.md). Runbook dành cho recovery có chủ đích, không phải cách thêm migration hằng ngày. Việc mới dùng [quy trình nhóm](../../development/FLYWAY_TEAM_WORKFLOW.md); không baseline/adopt lại nguồn đã tiếp nhận.

> **Chốt phạm vi theo ưu tiên vận hành:** một chain đóng gói `db/reconciled`, giữ nguyên history cũ và dữ liệu; xử lý các chốt high/critical trước. App giữ endpoint DB đang hoạt động; operator tiếp nhận dùng session endpoint. Sau V42, migration V43+ đi theo Flyway thông thường, không cần đổi policy Java hoặc hash contract cho từng migration.

## Chính sách thực tế của bản sửa hiện tại

- **Không còn là opt-in.** Khởi động bình thường dùng bundle đóng gói `classpath:db/reconciled`, history **`taskpilot_migrations.flyway_schema_history_v2`**, baseline 41 và forward migration 42. Profile `recovery` chỉ là alias tương thích cho cùng chính sách, không bắt buộc bật.
- Không dùng `TASKPILOT_RECOVERY_MIGRATIONS` hoặc bundle bên ngoài để chạy ứng dụng. Cấu hình location khác bundle đóng gói bị từ chối, không bị âm thầm bỏ qua.
- Không tự baseline, không `repair`, không `clean`, không replay SQL đổi email V41. Hibernate chỉ validate.
- **Không deploy bản mới lên DB đang có dữ liệu nhưng chưa được operator tiếp nhận.** Startup từ chối trạng thái đó; đây không phải cơ chế chuyển đổi không downtime.
- Hotfix ứng dụng đã phát hành là bản khác, không tự bao gồm cutover này. Không dùng kết quả hotfix hoặc rehearsal cũ để phê duyệt artifact mới.

## 1. Chuẩn bị artifact, backup và bản phục dựng

1. Chốt/review toàn bộ mã và tài nguyên của artifact, kể cả các file mới chưa được Git theo dõi. Không phát hành chỉ một phần dirty tree.
2. Có backup/PITR và bằng chứng restore, bao gồm dữ liệu, ownership/ACL, extensions, sequence và những schema Supabase cần thiết. Backup `public` không thay thế backup toàn dự án.
3. Khôi phục backup vào môi trường cô lập. Không mount `.env` nguồn cho ứng dụng thử; dùng credential local và chặn truy cập dịch vụ ngoài.
4. Chạy **đúng artifact dự kiến triển khai**: operator, validation, toàn backend và smoke nghiệp vụ. Đối chiếu digest dữ liệu/history trước/sau. Các kiểm thử schema trắng hoặc fixture giả không thay bước này.

## 2. Kiểm tra tương thích trước baseline

Operator có hai lớp kiểm tra riêng:

- Approval ràng buộc URL session đã chuẩn hóa, login username, `current_user`, database, catalog/data/history digest và bundle/contract. Nó chặn thay đổi kể từ lần inspect.
- `ReviewedSchemaContract` ràng buộc cấu trúc với **baseline V41 độc lập đã được duyệt**: bảng, cột/kiểu/default/nullability, constraints, indexes, enum và định nghĩa sequence. Không lấy DB đích làm mẫu kỳ vọng. Index email phải đúng bảng/biểu thức, không partial và sẵn sàng bảo vệ mọi dòng.

Hash cấu trúc tham chiếu PostgreSQL 17: `a74f4cfac91fa1184ac18284fadf558ca6b7bc64e1276c84fdf99c6fa5876da5`. Test `reviewedContractMatchesASeparateFreshV41Baseline` xác minh bằng schema trắng dựng từ SQL đóng gói. Không thay hash để làm DB nguồn vượt kiểm tra; nếu khác, dừng và phân tích/review chênh lệch.

Contract này bảo vệ bước tiếp nhận và trạng thái phục hồi V41/V42. Khi đã có V43 trở đi, Flyway tiếp tục kiểm tra checksum/history và Hibernate kiểm tra mapping; không ép schema mới khớp nguyên trạng snapshot V41. Giữ V41/V42 bất biến và thêm SQL version mới trong cùng thư mục. SQL callback tự động bị tắt; dùng migration versioned cho thay đổi có chủ đích.

Index lịch họp đúng định nghĩa được phép chưa tồn tại ở V41 và bắt buộc tồn tại khi validate hoàn tất V42. Giá trị sequence hiện tại và dữ liệu không thuộc hash cấu trúc. Owner/ACL/RLS không bị ép giống ACL của baseline trắng; chúng vẫn thuộc catalog approval và phải được review riêng. Việc tiếp nhận không tự harden các bảng đang tồn tại.

## 3. Chạy operator từ artifact đã duyệt

Giải nén JAR được duyệt vào thư mục làm việc riêng, không chứa `.env`. Các lệnh sau dùng classpath sau khi giải nén; ví dụ này là PowerShell/Windows. Trên Linux thay dấu phân cách classpath `;` bằng `:`.

```powershell
jar -xf '<reviewed-application.jar>'
$operator = 'com.taskpilot.app.migration.RecoveryAdoption'
$classpath = 'BOOT-INF/classes;BOOT-INF/lib/*'
java --enable-preview -cp $classpath $operator --inspect
```

- Cấp `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` từ cơ chế bí mật của người vận hành, không ghi vào Git, log hoặc command history. CLI chỉ đọc các biến môi trường này; không tự đọc `.env`.
- `DB_URL` qua Supabase transaction pooler `*.pooler.supabase.com:6543` được đổi sang session port 5432. Kiểm tra endpoint/session authentication trước maintenance; không coi test URL local là bằng chứng đã kết nối Supabase thật. Login username và PostgreSQL `current_user` được ràng buộc riêng.
- `--inspect` chỉ đọc và xuất observation JSON. Review các trường điều kiện cùng bằng chứng catalog độc lập; lưu **riêng object `approval` bên trong observation**, không phải toàn observation, vào file JSON giới hạn quyền đọc. Mỗi artifact/contract mới cần inspect và phê duyệt lại.
- Đặt `TASKPILOT_RECOVERY_APPROVAL` trỏ tới file approval đó. Không để file dữ liệu hoặc credential trong kho mã.

```powershell
java --enable-preview -cp $classpath $operator --adopt
java --enable-preview -cp $classpath $operator --validate
```

**Chỉ chạy adopt trên nguồn sau khi bước bản phục dựng đã đạt và có cửa sổ bảo trì/phê duyệt thao tác nguồn.** Các câu lệnh ở đây là hướng dẫn vận hành, không có nghĩa phiên sửa mã đã thực hiện chúng trên nguồn.

Operator khóa chống hai người tiếp nhận đồng thời và khóa ghi các bảng `public` bằng SHARE/NOWAIT. Reader được phép tiếp tục; writer có thể phải chờ hoặc timeout. Các kiểm tra approval, index email và cấu trúc phải đạt **trước khi tạo history mới**. Operator tự baseline 41, gắn comment SHA-256 cho snapshot, migrate V42 rồi so lại dữ liệu/history cũ. Không baseline thủ công vì marker không có comment ràng buộc sẽ bị startup từ chối.

## 4. Thứ tự cutover và xử lý lỗi

1. Dừng triển khai migration song song và quiesce writer/background jobs trong cửa sổ bảo trì đã thống nhất. Giữ artifact cũ và phương án restore/forward-fix.
2. Kiểm tra target/backup lần cuối, inspect và duyệt trạng thái phù hợp; thực hiện adopt bằng artifact mới đã thử trên bản phục dựng.
3. Chỉ sau khi adopt và validate hoàn tất mới triển khai ứng dụng mới, kiểm tra health và nghiệp vụ. Không dùng `ddl-auto=update` để vượt lỗi schema.
4. Khi có bất kỳ lỗi nào, dừng rollout, lưu bằng chứng và kiểm tra history thực tế. Baseline/V42 dùng connection riêng nên **không cùng một transaction nguyên tử** với khóa ghi; lỗi cuối không có nghĩa mọi việc đã rollback.
5. Baseline 41 dang dở có thể tiếp tục qua operator sau khi nguyên nhân đã được review. Nếu schema/data đã đổi, inspect lại và phê duyệt mới; approval cũ phải bị từ chối. Với history đã đủ V42, dùng validate, không chạy adopt lại để ép thành công.
6. Lock connection có `idle_in_transaction_session_timeout=45s`. Không cam kết freeze liên tục hoặc zero downtime chỉ dựa trên ca chạy local nhanh; thử thời gian thực tế trên bản phục dựng và dùng quiescence ngoài ứng dụng. Nếu lock hết hạn, dừng và đối soát, không tự retry hàng loạt.

## 5. Kiểm thử và tiêu chí phát hành

- Chạy tập trung `ReconciledFlywayIntegrationTest`, `MigrationConnectionSafetyTest`, `RecoveryDefaultConfigurationTest`, `RecoveryProfileSafetyTest`, sau đó hồi quy backend an toàn.
- Phải có test âm: index email sai expression/bảng hoặc partial, schema thiếu/sai cột và constraint, history giả, index lịch sai/mất, và cấu hình bundle ngoài.
- Schema drift phải bị từ chối trước baseline; validate hoàn tất phải phát hiện drift kể cả khi checksum history vẫn khớp.
- Giữ riêng bằng chứng test hiện tại, restore có dữ liệu thật và production. Không gọi rehearsal là đã cứu xong nguồn.

---

# [SUPERSEDED] Bản runbook ban đầu — chỉ giữ làm lịch sử

**Toàn bộ phần dưới mô tả thiết kế opt-in/bundle ngoài cũ; không dùng để triển khai bản hiện tại.** Quy trình có hiệu lực nằm phía trên.

# Bản sửa cứu hộ — triển khai theo hai giai đoạn

> **Trạng thái mới:** giai đoạn A đã phát hành qua PR #51 và kiểm tra trên dịch vụ thật. Giai đoạn B chưa cutover; profile/bundle recovery chưa đưa vào hotfix. Backup `public` đã thử restore, nhưng chưa thay thế backup toàn bộ Supabase/PITR. Xem [kết quả phát hành](HOTFIX_RELEASE_2026-11-09.md).

**Nhánh:** `fix/flyway-safe-recovery`, tách từ `main` tại `759ede5`. Tài liệu này không cho phép tự chạy SQL lên CSDL đang deploy.

## A. Hotfix ứng dụng không cần sửa dữ liệu

- Không nuốt lỗi JDBC có chữ “aborted” thành client disconnect.
- Xung đột toàn vẹn dữ liệu trả HTTP 409 với JSON an toàn, không trả SQL/thông tin tài khoản.
- Register, login và forgot-password dùng email trim/lowercase với `Locale.ROOT`; register kiểm tra trùng không phân biệt hoa/thường. Không đổi email của tài khoản hiện có; không hoàn tác V41 hay gộp 9 tài khoản.
- Các migration cũ, `application.yml`, `application-dev.yml`, `application-prod.yml` và cấu hình deploy mặc định không bị sửa. Hotfix không tự bật chế độ recovery.

Kiểm thử:

```powershell
mvn -B -o -pl taskpilot-users -am test
mvn -B -o -pl taskpilot-app -am package '-Dtest=GlobalExceptionHandlerTest,AuthServiceRecoveryTest,RecoveryProfileSafetyTest,EntityFlywaySchemaParityAuditTest' '-Dsurefire.failIfNoSpecifiedTests=false'
python -m unittest discover -s tools/flyway-recovery -p 'test_*.py' -v
```

Để phát hành hotfix, phải review, commit và duyệt deploy riêng. Không bật recovery profile khi chỉ muốn sửa API. Không chạy ứng dụng thử với `.env` thật: các tác vụ nền có thể ghi CSDL ngay cả khi không gọi API.

## B. Phục hồi Flyway có xác nhận tường minh — hiện chỉ rehearsal

### 1. Điều kiện tiên quyết

- Có backup dữ liệu đã thử phục hồi/PITR và cấu hình deploy được lưu; dump schema/history không thay thế backup dữ liệu.
- Điều phối người phát triển để không deploy migration song song; xác minh mốc `main` và trạng thái thật của nguồn ngay trước cutover. Nguồn đã chạy V41 dù Git đã revert.
- Phê duyệt snapshot `public`, extension và phiên bản PostgreSQL, role/owner/ACL, RLS, enum, triggers/functions, seed cấu hình và mọi phụ thuộc `auth`/`storage`. Chưa đủ các điều kiện này thì dừng, không baseline.
- Số V41/V42 trong bundle rehearsal chưa được coi là dành số chính thức; dùng không gian migration và bảng history mới, không trộn thư mục cũ vào recovery.

### 2. Tạo bundle OFFLINE từ dump schema-only đã có

```powershell
python tools/flyway-recovery/prepare_bundle.py --schema-dump '<trusted-public-schema-only.dump>' --output '<new-rehearsal-folder>'
python tools/flyway-recovery/prepare_bundle.py --output '<new-rehearsal-folder>' --verify
```

Tool không đọc `.env`, không kết nối DB và không có lệnh baseline/repair/clean. Nó từ chối dump có data entries, loại bảng lịch sử cũ, psql meta commands và session SET, không chấp nhận custom procedural code hoặc tham chiếu các schema Supabase ngoài phạm vi. Đây là kiểm tra phòng vệ cho **dump đáng tin cậy**, không phải bộ lọc SQL độc hại tổng quát. Extension, role và schema vẫn phải review.

Kết quả gồm snapshot schema V41 (không chứa dữ liệu tài khoản), V42 chỉ bổ sung lịch/index cuộc họp, manifest SHA-256. Manifest để `reviewed_for_production=false`; verify chỉ kiểm tra toàn vẹn artifact, **không** chấp thuận triển khai. Không sửa manifest thành true để thay thế quy trình review.

### 3. Đối chiếu bản sao trước khi tiếp nhận

`tools/flyway-recovery/preflight.sql` chỉ đọc: thu version, history, fingerprint cột/constraint/enum, index, RLS, policy, owner, quyền và routine. Chạy vào CSDL lab với thông tin kết nối tường minh; không truyền `.env` nguồn vào công cụ thử nghiệm.

So preflight trước/sau và với schema được duyệt; phân loại mọi khác biệt, đặc biệt kiểu cột, index cùng tên nhưng khác định nghĩa, unique email, RLS và role backend. Không dùng chỉ max(version) hoặc việc app trả 200 làm tiêu chí thông qua.

### 4. Hai đường phải kiểm thử

- **CSDL trắng:** Flyway chạy snapshot và migration mới; không mang lịch sử cũ hay dữ liệu người dùng vào đây. Cần bổ sung seed đã được review cho cấu hình thực tế; snapshot chỉ có cấu trúc.
- **CSDL đã có dữ liệu:** giữ nguyên bảng `flyway_schema_history`. Sau khi preflight/backup được duyệt, người vận hành mới thực hiện một lần baseline phiên bản 41 vào **`flyway_schema_history_v2`** bằng Flyway tương thích với app; xác nhận danh tính DB và số phiên bản. Không cung cấp lệnh tự tiếp nhận remote trong tool để tránh thao tác nhầm. Baseline không được replay SQL chuẩn hóa email V41.
- Sau đó validate, migrate và khởi động với profile recovery chỉ trên lab trước; bảo toàn digest fixture/history, kiểm tra HTTP status/body/tác động DB, thử restart và ca âm tính.

Profile `application-recovery.yml`:

- `baseline-on-migrate=false`, `validate-on-migrate=true`, `out-of-order=false`, `clean-disabled=true`.
- History mới `flyway_schema_history_v2`; chỉ đọc bundle ngoài được chỉ định bởi `TASKPILOT_RECOVERY_MIGRATIONS`.
- `ddl-auto=validate`.
- Không có bundle thì từ chối khởi động. Database không rỗng chưa được baseline tường minh thì từ chối migrate. Đây là chặn an toàn, **sẽ làm startup thất bại nếu bật profile nhầm trên production**.

### 5. An ninh và rollback

`tools/flyway-recovery/rehearse_sensitive_rls.sql` là mô phỏng RLS cho hai bảng phát hiện, chỉ chấp nhận database tên `taskpilot_recovery_%` và luôn rollback. Không coi nó là migration production hoặc tìm cách bỏ guard để chạy trực tiếp. Xác minh backend role, service role và mọi đường truy cập trước khi chuẩn bị migration an ninh riêng.

Nếu startup lab fail: giữ dịch vụ thật nguyên trạng, đọc log và sửa trên bản sao. Không bật `ddl-auto=update`, không chạy `repair`, không xóa history, không cắt hậu tố email để cố làm xanh kiểm thử. Khi rollback code sau cutover, không giả định SQL/data đã rollback; phải kiểm tra tương thích và dùng kế hoạch backup/forward-fix đã duyệt.

## Trạng thái giao hàng

Đoạn dưới là mốc giao hàng ban đầu trước khi phát hành hotfix ứng dụng; trạng thái mới nằm ở đầu tài liệu.

Hotfix và cấu hình opt-in được viết trên nhánh riêng. Bundle thử nghiệm nằm ngoài Git, chưa được review production. Chưa push, chưa tạo PR, chưa deploy, chưa có lệnh ghi vào CSDL nguồn từ phiên cứu hộ. Kết quả kiểm thử thực tế sẽ ghi trong `RECOVERY_IMPLEMENTATION_2026-11-09.md`.

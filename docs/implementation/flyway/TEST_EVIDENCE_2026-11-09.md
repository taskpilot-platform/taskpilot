# Minh chứng kiểm thử đã rút gọn — 09/11/2026

> **Bản ghi lịch sử 09/11:** các kết luận dưới đây mô tả thời điểm điều tra/hotfix ban đầu. Ngày 10/11, nguồn đã tiếp nhận Flyway V42, validate đạt và app mới đã deploy/live smoke thành công; xem [kết quả phát hành cuối](RECOVERY_RELEASE_2026-11-10.md). Dùng [quy trình nhóm](../../development/FLYWAY_TEAM_WORKFLOW.md) cho migration mới, không lặp lại hướng recovery thử nghiệm cũ.

> Các ca điều tra trước sửa ở dưới được giữ làm đối chứng. Kết quả sau sửa trên nhánh `fix/flyway-safe-recovery`: [370 test hồi quy, 7 test công cụ và HTTP 409 đã sửa](RECOVERY_IMPLEMENTATION_2026-11-09.md). Chưa triển khai CSDL nguồn.

Các dòng bên dưới được trích từ kết quả đã chạy, không phải kết quả dự kiến. Chỉ sử dụng database Docker local, không có dữ liệu người dùng thật. Xem [đánh giá phương án](SOLUTION_ASSESSMENT_2026-11-09.md) cho phạm vi và giới hạn.

## Build và test có sẵn

```text
mvn -B -pl taskpilot-app -am clean package -DskipTests
BUILD SUCCESS

mvn -B -o -pl taskpilot-app -am test -Dtest=EntityFlywaySchemaParityAuditTest -Dsurefire.failIfNoSpecifiedTests=false
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Đây không phải kết quả toàn bộ test suite. Mã đã build là `c621c53`, cây mã trùng `main` tại `759ede5` sau revert.

## Baseline snapshot được thử trên trạng thái V41

```text
IMPLICIT_ADOPTION_BLOCKED=true
EXPLICIT_BASELINE_MIGRATIONS=1
EXISTING_VALID=true
RESTART_MIGRATIONS=0
HISTORY_PRESERVED=true
FIXTURE_DATA_PRESERVED=true
WRONG_SCHEMA_PREFLIGHT_BLOCKED=true
NEGATIVE_HISTORY_NOT_CREATED=t
FRESH_MIGRATIONS=2
FRESH_VALID=true
FRESH_AND_ADOPTED_COLUMNS_CONSTRAINTS_EQUAL=true
```

Snapshot V41 và migration V42 chỉ có trong thư mục lab, chưa là migration chính thức. Fingerprint âm tính kiểm tra cột/constraint, không đại diện mọi lớp kiểm tra preflight cần có trên môi trường thật.

## So catalog hai đường baseline

```text
columns: missing_in_target=0 extra_in_target=0 different=0
constraints: missing_in_target=0 extra_in_target=0 different=0
indexes: missing_in_target=0 extra_in_target=0 different=0
rls: missing_in_target=0 extra_in_target=0 different=0
policies: missing_in_target=0 extra_in_target=0 different=0
sequences: missing_in_target=0 extra_in_target=0 different=0
```

Không so ACL/ownership, dữ liệu cấu hình, schema Supabase ngoài `public` hoặc mọi định nghĩa functions/triggers/enum.

## Hibernate validate 27 entity

```text
SCHEMA_VALIDATE=taskpilot_fresh_main;PASS
SCHEMA_VALIDATE=taskpilot_restore;PASS
SCHEMA_VALIDATE=taskpilot_current_v41;PASS
SCHEMA_VALIDATE=taskpilot_snapshot_fresh_v41;PASS
```

## Spring Boot thật và HTTP

```text
APP_STARTUP=taskpilot_fresh_main;PASS
APP_STARTUP=taskpilot_snapshot_fresh_v41;PASS
APP_STARTUP=taskpilot_current_v41;PASS
```

Ba trường hợp đều có health HTTP 200, PostgreSQL UP; API danh sách OAuth provider HTTP 200, `data=[]` do không dùng credential thật. Ứng dụng chạy trong mạng Docker internal, dummy credentials, không có `.env` thật, không chạy luồng OAuth/AI/storage ngoài mạng.

## Auth và ca âm tính phát hiện lỗi

```text
AUTH_REGISTER=taskpilot_snapshot_fresh_v41;201
AUTH_LOGIN=taskpilot_snapshot_fresh_v41;200
AUTH_PROFILE=taskpilot_snapshot_fresh_v41;200
AUTH_CASE_VARIANT_REGISTER=taskpilot_snapshot_fresh_v41;HTTP=200;BODY_LENGTH=0

DB_ABORT_MISCLASSIFIED_AS_DISCONNECT=true
SAME_ERROR_WITHOUT_ABORTED_HTTP=500
```

Ca cuối là **lỗi sản phẩm**: CSDL từ chối insert trùng với SQLSTATE 23505 nhưng handler bỏ qua do thông báo JDBC có chữ “aborted”. Không coi HTTP 200 đó là nghiệp vụ đạt.

## RLS với dữ liệu giả — đã rollback

```text
SYNTHETIC_ANON_WITHOUT_RLS_VISIBLE=true
SYNTHETIC_ANON_WITH_RLS_BLOCKED=true
LAB_ADMIN_FIXTURE_ACCESS_PRESERVED=true
ROLLBACK
```

Role quản trị lab là superuser. Kết quả không thay thế kiểm thử role thực tế của backend/PostgREST trên Supabase và không khẳng định nguồn đã có rò rỉ.

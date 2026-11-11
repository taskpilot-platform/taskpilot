# PR #53 — đóng chốt migration/schema, 11/11/2026

## Phạm vi

Hoàn thiện PR #53 đang open (`chore/flyway-team-workflow`), không mở PR phục hồi thứ hai. Không sửa V41/V42, không thêm SQL nghiệp vụ vào active bundle, không chạy migrate/baseline/repair trên nguồn, không thay RLS hoặc deploy/merge tự động.

Rủi ro được xử lý là SQL mới không ghi schema có thể vào `taskpilot_migrations`, dù engine validate xanh. V42 đã phục hồi và không cần cứu lại.

## Các chốt thực hiện

1. **AST PostgreSQL 17:** `tools/flyway-team/sql_policy.py`, dependency `pglast==7.22`. Lint SQL mới so với accepted main; xét đúng relation/qualified name, không regex tìm chuỗi. CREATE INDEX xét bảng sau ON, không bắt qualify tên index. Kiểm tra cả FK, identity sequence, sequence owner, quoted identifiers và CTE/DML. SQL không phân tích được/procedural/dynamic/schema redirection/function ngoài allowlist bị từ chối, không có bypass bằng comment. Các câu lệnh chưa hỗ trợ cần review policy/test, không lách checker.
2. **Artifact SQL-only:** quét toàn bộ active directory và accepted Git tree, từ chối `.sql.conf`, callback, nested directory, symlink và file ngoài tên versioned SQL. Legacy directory bất biến.
3. **Catalog allowlist runtime:** `MigrationMetadataGuard` kiểm tra namespace dependencies trước migrate và khi validate hoàn tất. Chỉ chấp nhận history table và đúng PK/index/row/array types của Flyway 11/PostgreSQL; không coi mọi object cùng tên là hợp lệ. Bảng nghiệp vụ/enum/sequence/routine hoặc object khác bị chặn, kể cả khi chưa có history. Giữ nguyên private default schema và chính sách validate/baseline/clean.
4. **Integration thật:** `MigrationSchemaIntegrationTest` dựng PostgreSQL 17/Testcontainers, migrate cả fresh/upgrade bằng engine/strategy thật, đối chiếu public table/type/sequence/catalog, bảo toàn fixture cũ, restart backend thật với Hibernate validate, đọc/ghi entity và HTTP health. `.env` nguồn không được nạp. Các migration probe chỉ nằm trong temp, không cấp số V trên nguồn.
5. **Không pass do skip:** lớp integration mới không bật `disabledWithoutDocker`. Workflow yêu cầu `docker info`, Maven clean test và kiểm tra XML Surefire. Missing/empty/skipped/failed report hoặc thiếu ca fresh/upgrade/metadata-negative làm job fail. Unit tests tái hiện report bị thiếu/skip/failure/partial.
6. **Required checks không Pending vì paths:** workflow bỏ path filter, chạy mọi PR vào main. Hai job độc lập `migration-source-check` và `migration-integration` phải được maintainer thêm vào ruleset cùng `build-and-test`, giữ nguyên chính sách còn lại. Trạng thái effective rules phải được đọc lại sau cấu hình; YAML riêng không chứng minh đã bắt buộc merge.

Guard sau migrate không rollback transaction đã commit. Vì vậy AST/CI sandbox phải chặn trước merge; runtime guard chỉ là phòng vệ cuối. Không dùng DB chung để thử SQL hoặc tự xóa metadata để làm xanh.

## Bằng chứng local

- RED trước sửa: checker chấp nhận unqualified/wrong-schema/dynamic SQL và sidecar; ba ca PostgreSQL table/enum/sequence migrate rồi validate thành công sai mong đợi.
- GREEN sau sửa: **414 backend regression tests**, 0 failure/error/skip, loại bốn lớp live-provider có chủ đích. Trong đó **30 integration cases bắt buộc** (28 recovery/catalog + 2 fresh/upgrade real backend) đều thực sự chạy.
- Tooling: **27 checker/policy/report-gate tests + 7 historical-tool tests đạt**. Có nhiều SQL subcases cho quoted identifiers, comment/string giả, partial/procedural/session redirect và identity sequence đích sai.
- `verify_integration.py` xác nhận cả fresh/upgrade backend và các ca catalog-negative có mặt, skipped=0.
- Kiểm tra source chỉ đọc sau sửa: shared/main/local đều V42, không có new local migration hay accepted-not-deployed; candidate V43 chưa được giữ chỗ.
- Engine validate bằng **các class policy/guard mới biên dịch**, datasource bị cưỡng chế read-only, `createSchemas=false`, init DDL bị tắt và không gọi migrate: V42, pending0, đạt. Đây không phải restart/deploy production.
- CI thật phải xem trên **head hiện tại của [PR #53](https://github.com/taskpilot-platform/taskpilot/pull/53)**, không dùng CI xanh từ commit cũ. Việc cập nhật PR/ruleset được đối chiếu qua API; không coi kết quả local là CI remote.

Log local nằm ngoài Git dưới `%LOCALAPPDATA%/Temp/opencode/`: `pr53-metadata-red.log`, `pr53-metadata-green.log`, `pr53-schema-integration.log`, `pr53-full-regression.log`. Không commit credential/dump/row nghiệp vụ.

## Tiêu chí nghiệm thu

- SQL sai bị source check và catalog regression chặn; SQL đúng chạy qua fresh/upgrade, backend Hibernate/health hoạt động.
- History và phụ trợ hợp lệ vẫn được phép; business objects trong metadata bị chặn.
- Mandatory integration không được thiếu hoặc skip; CI trên head mới phải xanh.
- Effective ruleset của main phải yêu cầu cả hai check mới trước merge. Repo admin cần kiểm chứng riêng, không chỉ thêm file workflow.
- Không thay V41/V42 hoặc ghi nguồn. RLS/permissions và tác động email V41 cũ giữ thành công việc riêng.

## Rà soát cuối trước merge

Review tiếp phát hiện phép thu thập tên CTE toàn statement cho phép tên trong truy vấn con bị coi là hợp lệ ở scope ngoài; đồng thời CTAS EXECUTE và MERGE trong CTE chưa bị chặn như các dạng unsupported ở cấp ngoài. Đã tái hiện năm subcase RED, sửa walker theo scope PostgreSQL (CTE thường thấy các sibling trước đó; CTE recursive thấy các binding của WITH RECURSIVE), và chặn cả các statement unsupported lồng nhau. CTE đúng vẫn được chấp nhận. **29 test checker/policy/report-gate đạt** sau sửa; review độc lập của delta không thấy blocker. Required checks trên head mới phải được xác minh lại trước merge, không tái sử dụng CI của head cũ.

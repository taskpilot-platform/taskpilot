# Flyway: hai người làm việc với một DB chung

Quy tắc này dùng chung cho người và agent. [SKILL.md](../../.agents/skills/taskpilot-flyway-team/SKILL.md) độc lập, không cần cài Superpowers. Agent đọc `AGENTS.md` ở gốc repo sẽ được trỏ tới skill.

## Kiểm tra version hiện tại

Từ thư mục backend, với Python 3.10+, Git và PostgreSQL client (`psql`):

```sh
python -m pip install -r tools/flyway-team/requirements.txt
python tools/flyway-team/check.py
```

Lệnh tự fetch `origin/main`, đối chiếu file migration, rồi **chỉ đọc** history đang dùng trên DB chung. Không khởi động Spring, không chạy migration/baseline/repair/clean, không giữ chỗ số V, không ghi credential hoặc query dòng nghiệp vụ.

Ví dụ khi DB/main/local đều V42: `deployed_version: 42`, `next_version: 43`. Đây chỉ là ví dụ; luôn chạy lệnh để lấy số hiện tại. Nếu main đã có V43 nhưng chưa deploy và local có V44 hợp lệ, số tiếp theo là V45, không phải V43.

- `deployed_version`: version SQL đã áp dụng trên DB chung; không phải bằng chứng app deploy đang khỏe.
- `main_version` / `local_version`: version cao nhất của bundle trong main mới fetch / checkout hiện tại.
- `new_local_versions`: các migration đang làm, chưa có trong main; phải xem cả danh sách này trước khi merge.
- `merged_not_deployed`: migration đã merge nhưng DB chưa áp dụng; chờ deployment theo đúng thứ tự.
- `next_version`: số cho **một migration mới nữa**, không phải yêu cầu đổi số migration đang làm. Khi offline, trường này là `null`.
- `number_reserved: false`: số chưa được giữ chỗ. Hai người chạy cùng lúc vẫn có thể nhận cùng số.
- `flyway_validate_run: false`: helper đối chiếu history/checksum, nhưng không thay thế validate của Flyway engine và kiểm tra schema/app.

Tool dùng `taskpilot_migrations.flyway_schema_history_v2`. `public.flyway_schema_history` là lịch sử cũ, không dùng để cấp số mới. Nó chặn trùng số, thay đổi/xóa/đổi tên SQL đã có trên main, nhánh thiếu migration main, history lỗi/lệch filename/checksum và dãy số không liên tiếp. Checksum tương thích `ChecksumCalculator` của Flyway 11.3.2; khi nâng thư viện, kiểm tra lại tương thích thay vì bỏ check.

## Schema đích và chính sách SQL mới

- History ở schema riêng **không** có nghĩa SQL không qualify sẽ tự tạo vào `public`. Mọi đối tượng nghiệp vụ phải chỉ rõ schema đích: `CREATE TABLE public.example (...)`, `ALTER TABLE public.example ...`, `CREATE INDEX idx_example ON public.example (...)`. Tên index không cần và không được ép thành `public.idx_example` trong cú pháp CREATE INDEX.
- Checker dùng **pglast 7.22 / PostgreSQL 17 AST**, không regex tìm chuỗi `public.`. Nó chỉ lint SQL mới so với base main, không sửa/lint ngược V41/V42 hoặc SQL đã được chấp nhận. Comment, string literal và tên quoted chứa dấu chấm không thay thế schema thực.
- Policy hiện hỗ trợ table/index/enum/sequence/view, ALTER TABLE/SEQUENCE/ENUM, DROP/COMMENT tương ứng, DML/CTE với relation `public`, SELECT và một allowlist builtin. FK target, identity sequence name và sequence owner cũng phải qualify đúng. `SET LOCAL lock_timeout/statement_timeout` được phép.
- **Fail closed:** DO, routine/CALL, dynamic SQL, đổi search_path/role/schema, COPY/extension và câu lệnh/function chưa được phân tích an toàn bị từ chối. Không thêm bypass/comment để lách; nếu migration thực sự cần chúng, review và mở rộng policy cùng AST test + PostgreSQL regression trước. Checker không phải sandbox thực thi SQL hay chứng nhận đúng nghiệp vụ.
- Active bundle chỉ chứa file UTF-8 versioned SQL phẳng. `.sql.conf`, callback, file khác, thư mục con và symlink đều bị từ chối. Migration không được đổi execution policy bằng sidecar mà reviewer không thấy.

## Allowlist schema metadata

Runtime kiểm tra catalog trước migrate và khi validate hoàn tất. `taskpilot_migrations` chỉ được chứa các đối tượng Flyway 11/PostgreSQL đã biết:

- bảng `flyway_schema_history_v2`;
- PK/index `flyway_schema_history_v2_pk` và index `flyway_schema_history_v2_s_idx`, thực sự thuộc bảng history;
- constraint PK của bảng history, composite row type và array type tự động của chính bảng này.

Không cho phép bảng nghiệp vụ, enum, sequence, view/routine hoặc đối tượng schema khác lọt vào, kể cả đặt tên giống index hợp lệ nhưng sai loại. Không yêu cầu schema trống. Khi nâng Flyway phải review thay đổi catalog/allowlist và test trước khi triển khai.

Guard sau migrate là phòng vệ cuối: SQL có thể đã commit trước khi guard báo lỗi. Do đó không dùng production để thử; chốt AST và CI sandbox phải chặn trước merge/deploy. Không tự xóa đối tượng/history để vượt guard.

## Cấu hình kết nối

Giữ credential trong `.env` bị Git ignore hoặc environment. Mặc định helper dùng `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. Nếu các biến đó trỏ DB local, đặt **cả ba** `MIGRATION_SHARED_DB_URL`, `MIGRATION_SHARED_DB_USERNAME`, `MIGRATION_SHARED_DB_PASSWORD` riêng để helper đọc DB chung; cấu hình chạy app không bị thay đổi.

Có thể chọn file credential khác bằng `--env-file PATH`; environment ưu tiên hơn giá trị cùng tên trong file. Không đưa password vào command line hoặc commit `.env`. URL PostgreSQL và username/password dùng biến riêng, không nhét credential vào URL.

`tools/flyway-team/shared-target.json` chỉ chứa fingerprint không bí mật của host/port/database/login đã được duyệt. Session/transaction endpoint của cùng Supabase pooler được quy về cùng định danh. Nếu gặp `WRONG_SHARED_DATABASE`, kiểm tra biến kết nối; **không sửa fingerprint để vượt lỗi**. Chỉ maintainer cập nhật fingerprint sau khi xác minh việc đổi đích DB hoặc login; không hardcode một version mới vào config.

Nếu không có quyền kết nối/`psql`, báo version live chưa biết và nhờ người có quyền chạy helper. Vẫn có thể soát source offline bằng lệnh CI bên dưới; không lấy kết quả offline để đoán version live. Không dán credential hoặc output chứa dòng nghiệp vụ vào PR.

## Quy trình hai người

1. Chạy helper trước khi đặt tên SQL; ghi số dự kiến trong PR feature và thống nhất người làm số nào. Số chỉ chốt trước merge; status read-only không thể cấp số độc quyền.
2. Thêm file mới vào `taskpilot-app/src/main/resources/db/reconciled`, dạng `V43__add_example_column.sql` với số thực tế đã thống nhất. Không sửa `db/migration`, không sửa file đã được main chấp nhận.
3. Test SQL trên DB test tạm: cả DB trắng và nâng từ phiên bản trước, dùng Flyway thật cùng app/integration test. DB test tạm không phải môi trường chung thứ hai cần duy trì.
4. Trước merge, cập nhật main vào nhánh, chạy lại helper và xem các PR migration đang mở. Nếu migration **chưa áp DB chung** bị trùng số, đổi sang số tiếp theo đã thống nhất và test SQL sau migration vừa merge. CI phải đạt trên nhánh đã cập nhật.
5. Pipeline/người phụ trách deployment là đầu mối áp SQL lên DB chung. Chờ migration trước deploy thành công rồi mới áp migration sau. Sau đó cả hai cập nhật nhánh trước khi chạy app local.

Ví dụ A và B cùng viết V43: nếu chưa ai áp vào DB chung, A merge V43 trước; B cập nhật main, đổi SQL của mình thành V44, kiểm tra tương thích với V43 rồi test và merge. Nếu V43 của B **đã chạy trên DB chung**, không được chỉ đổi tên thành V44: đó là xung đột history thật, cần đối soát theo [runbook recovery](../implementation/flyway/RECOVERY_RUNBOOK.md).

## Tránh làm app chung sập

- App tự chạy Flyway lúc startup. Chạy nhánh local có migration chưa merge với `.env` trỏ DB chung sẽ áp SQL đó ngay. Không dùng cách này để thử SQL.
- Giữ validate bật, baseline-on-migrate false, out-of-order false, clean disabled. Không `repair` checksum, xóa history hay reset DB chung để che xung đột.
- Vì code local/deployment cùng dùng schema, ưu tiên thêm cột tương thích, nullable/default/backfill hợp lý. Việc đổi tên/xóa cột hoặc siết constraint phải xét code đang chạy; lên số V đúng không tự làm SQL an toàn.
- Sau deploy: kiểm tra app health, Flyway validate và luồng bị ảnh hưởng. Helper đọc được version không đồng nghĩa deploy/app khỏe hoặc SQL đúng nghiệp vụ.

## CI không cần credential chung

```sh
python -m unittest discover -s tools/flyway-team -p "test_*.py"
python tools/flyway-team/check.py --offline --no-fetch --base-ref <PR-base-SHA>
python tools/flyway-team/verify_integration.py
```

Workflow `flyway-team-check.yml` dùng base SHA của PR để kiểm tra source và tính bất biến; nó không được cấp secret DB và không giả vờ đã đọc DB chung. Khi merge một PR migration khác, nhánh còn lại phải cập nhật và kiểm tra lại với main mới. Người review vẫn phải xem live status mới cùng thứ tự các PR; check Git không khóa số hoặc thay thế phối hợp.

Workflow chạy **mọi PR vào main**, không lọc `paths`. Hai check cần được maintainer cấu hình thành required trong ruleset: `migration-source-check` và `migration-integration`, bên cạnh `build-and-test`. Sửa YAML không tự cấu hình ruleset; phải đọc lại API/UI để xác minh trước khi gọi đợt này hoàn tất.

`migration-integration` yêu cầu Docker hoạt động, chạy Maven **clean test** trên PostgreSQL/Testcontainers thật và đối chiếu Surefire XML. Thiếu report, mất ca fresh/upgrade, test rỗng, failures/errors hoặc bất kỳ skip trong hai lớp integration bắt buộc đều làm job thất bại. Lớp `MigrationSchemaIntegrationTest` không dùng `disabledWithoutDocker`: thiếu Docker là lỗi. Backend thật phải khởi động với Hibernate validate, HTTP health UP và đọc/ghi entity ánh xạ bảng mới ở cả fresh và upgrade; fixture nằm trong temp, không thêm migration giả vào active bundle, không đọc `.env` nguồn.

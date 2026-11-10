# Flyway: hai người làm việc với một DB chung

Quy tắc này dùng chung cho người và agent. [SKILL.md](../../.agents/skills/taskpilot-flyway-team/SKILL.md) độc lập, không cần cài Superpowers. Agent đọc `AGENTS.md` ở gốc repo sẽ được trỏ tới skill.

## Kiểm tra version hiện tại

Từ thư mục backend, với Python 3.10+, Git và PostgreSQL client (`psql`):

```sh
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
```

Workflow `flyway-team-check.yml` dùng base SHA của PR để kiểm tra source và tính bất biến; nó không được cấp secret DB và không giả vờ đã đọc DB chung. Khi merge một PR migration khác, nhánh còn lại phải cập nhật và kiểm tra lại với main mới. Người review vẫn phải xem live status mới cùng thứ tự các PR; check Git không khóa số hoặc thay thế phối hợp.

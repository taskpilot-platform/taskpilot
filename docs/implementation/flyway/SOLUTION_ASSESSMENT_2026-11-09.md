# Đánh giá phương án phục hồi, kiểm thử và phát hiện mới

> **Bản ghi lịch sử 09/11:** các kết luận dưới đây mô tả thời điểm điều tra/hotfix ban đầu. Ngày 10/11, nguồn đã tiếp nhận Flyway V42, validate đạt và app mới đã deploy/live smoke thành công; xem [kết quả phát hành cuối](RECOVERY_RELEASE_2026-11-10.md). Dùng [quy trình nhóm](../../development/FLYWAY_TEAM_WORKFLOW.md) cho migration mới, không lặp lại hướng recovery thử nghiệm cũ.

> **Đã có bản sửa trên nhánh riêng:** xem [kết quả hiện thực và kiểm thử](RECOVERY_IMPLEMENTATION_2026-11-09.md) và [runbook](RECOVERY_RUNBOOK.md). Các phát hiện bên dưới mô tả mốc điều tra trước sửa; chưa có cutover hoặc sửa CSDL nguồn.

**Ngày điều tra:** 09/11/2026. **Phạm vi triển khai:** chưa cho phép thay đổi CSDL nguồn hoặc triển khai production. Các SQL ghi chỉ chạy trong Docker cô lập, không dùng dữ liệu người dùng thật.

## 1. Kết luận điều hành

Ứng dụng không cần xóa hay dựng lại CSDL đang dùng. Lược đồ được quan sát đủ cho **27 entity của `main`** qua kiểm tra Hibernate và cho khởi động Spring Boot trong môi trường cô lập. Vấn đề chính là nguồn gốc lược đồ không tái tạo được từ lịch sử migration hiện tại, cùng một số rủi ro bảo mật/dữ liệu phát hiện trong quá trình điều tra.

Phương án có triển vọng nhất khi SQL lịch sử đã mất là **baseline lược đồ được thẩm định + bảng lịch sử Flyway mới + migration tiếp nối**, giữ nguyên bảng lịch sử cũ làm bằng chứng. Phương án này đã vượt qua kiểm thử schema, Flyway và startup trên cả database trắng và bản phục dựng, **nhưng chưa đủ điều kiện triển khai**: cần thẩm định quyền, seed dữ liệu cấu hình, các schema Supabase khác, toàn bộ nghiệp vụ và khả năng phục hồi backup.

## 2. Thay đổi trạng thái trong lúc điều tra: V41 thực sự đã chạy

Trước đó, bản sao cấu trúc và lịch sử ghi đến V40. Trong khi điều tra, origin đã có:

| Commit | Sự kiện Git | Thời điểm commit |
|---|---|---|
| `41a04da` | Merge PR #49 chứa V41 chuẩn hóa email | 09/11/2026 21:46:50 +07:00 |
| `c0645fa` | Commit revert PR #49 | 09/11/2026 21:51:20 +07:00 |
| `759ede5` | Merge PR #50 đưa revert vào `main` | 09/11/2026 21:58:51 +07:00 |

`main` cục bộ đã fast-forward tới `759ede5`, giữ các tài liệu chưa commit. Cây mã của `759ede5` **giống hệt** `c621c53` đã build/kiểm thử; đây không phải thay đổi sang một bộ mã khác. Artifact dùng cho thử nghiệm có build fingerprint `c621c53`.

Kiểm tra lại nguồn bằng `BEGIN READ ONLY` xác nhận:

- Lịch sử có **41 dòng**, V41 `V41__normalize_user_emails.sql` thành công, checksum `-398912632`, `installed_on=2026-11-09 14:51:14.914162` (cột timestamp không kèm timezone; không tự suy diễn timezone).
- Index `idx_users_email_lower_trimmed` tồn tại.
- Thống kê hiện có **9 email có hậu tố `_dup_<số>`**, không còn email khác dạng `lower(trim(email))`. Không đọc hoặc công bố email cụ thể. Có tương quan với tác động dự kiến của V41, nhưng không có bản sao dữ liệu trước thay đổi để phục hồi chính xác từng email.
- Không phải các lệnh thử nghiệm của phiên điều tra này áp dụng V41 lên nguồn. GitHub Actions ghi nhận đồng bộ Hugging Face sau PR #49 và sau revert; chưa có log startup/migrate của dịch vụ để quy kết chính xác tiến trình đã thực thi SQL.

**Hệ quả:** revert Git không đảo ngược SQL/data đã commit. Không tự chạy lại, xóa index hoặc khôi phục email bằng cách cắt hậu tố: phải xác minh từng tài khoản, xung đột và backup/PITR. Bản sao V40 cũ vẫn được giữ để đối chiếu, đã tạo thêm bản sao cấu trúc/lịch sử V41 cho các thử nghiệm mới.

Các run quan sát qua GitHub API:

- Đồng bộ commit merge PR #49: <https://github.com/taskpilot-platform/taskpilot/actions/runs/42638032401>.
- Đồng bộ commit revert trên `main`: <https://github.com/taskpilot-platform/taskpilot/actions/runs/42639982561>.
- E2E trên merge PR #49 có trạng thái workflow `success`: <https://github.com/taskpilot-platform/taskpilot/actions/runs/42638469260>. Chưa tải và thẩm định từng ca kiểm thử/artifact, không sử dụng trạng thái này để khẳng định mọi luồng đã đạt.

Workflow `deploy-hf.yml` có chạy kiểm thử khi mở PR nhưng job đồng bộ chỉ được phép chạy với push hoặc dispatch. Không có bằng chứng để kết luận PR chưa merge tự deploy chỉ vì tên workflow hiện `success`.

## 3. Độ lệch lược đồ rộng hơn checksum

Đã lấy catalog cột, constraint, index, RLS, policy và sequence của các database cô lập; không so sánh dữ liệu người dùng. So bản phục dựng V40 (đã thêm index lịch họp trong thử nghiệm) với CSDL trắng chỉ chạy `main`:

| Nhóm metadata | Có trên bản phục dựng nhưng không có trên CSDL trắng `main` | Định nghĩa khác giữa đối tượng chung |
|---|---:|---:|
| Cột | 117 | 21 |
| Constraint | 29 | 0 |
| Index | 30 | 1 |
| Bảng trong thống kê RLS | 9 | 8 |
| Policy | 8 | 0 |
| Sequence | 5 | 0 |

Đây là **số khác biệt catalog, không phải số lỗi chức năng**. Có thêm hai cột legacy trên CSDL trắng không có ở bản phục dựng. Một khác biệt index là cách biểu diễn ép kiểu của predicate hàng đợi; chưa chứng minh khác ngữ nghĩa, không cần tự động sửa. Không chỉnh phân hệ RAG do phát hiện này.

Các nguyên nhân cụ thể quan sát được:

- `main` không dựng bốn bảng `team_chat_*` và các bảng recommendation/adaptive mà nguồn có; các entity trên `main` không ánh xạ các nhóm bảng này, nên chúng thiếu mà startup vẫn có thể đạt.
- Độ dài nhiều cột khác nhau, phần lớn nguồn có `varchar(255)`, trong khi SQL tạo mới có `varchar` không giới hạn hoặc giới hạn khác. Không tự thu hẹp kiểu dữ liệu vì có thể mất dữ liệu.
- Nguồn còn `tasks.tags` và `tasks.required_skills`; chuỗi migration `main` đã đổi chúng thành `legacy_*_do_not_use`. Không có field tương ứng trong `TaskEntity` hiện tại; cần xác minh bên tiêu thụ trước khi rename/drop.
- Nguồn bật RLS cho 8 bảng chung mà chuỗi migration tạo mới không bật: `ai_chat_memories`, `ai_chat_requests`, `document_chunk_staging`, `document_chunks`, `documents`, `labels`, `task_labels`, `task_required_skills`.

Khôi phục V34–V38 chỉ loại được một phần độ lệch; trên CSDL trắng thử nghiệm đó vẫn thiếu 32 cột so với bản phục dựng, gồm 30 cột của bốn bảng nhóm chat và hai cột legacy nói trên.

## 4. Phát hiện an ninh cần ưu tiên riêng

Truy vấn **metadata quyền**, không truy vấn dữ liệu qua API, cho thấy trên nguồn:

| Bảng | RLS | Quyền SELECT hiệu lực của `anon` / `authenticated` |
|---|---|---|
| `user_identities` | Tắt | Có / Có |
| `project_meetings` | Tắt | Có / Có |

Đây là dấu hiệu rủi ro nghiêm trọng nếu các bảng được phơi qua PostgREST/schema công khai. **Chưa truy cập dữ liệu thật qua API, chưa xác minh phơi lộ ngoài mạng, không kết luận đã có rò rỉ.** RBAC ở Spring không tự bảo vệ một đường PostgREST truy cập trực tiếp bảng.

Đã tái hiện trên dữ liệu tổng hợp trong database local: với quyền SELECT tương tự, role `anon` đọc được bản ghi mẫu khi RLS tắt; sau khi bật RLS và policy deny, truy vấn không thấy bản ghi; role quản trị local vẫn thấy bản ghi. Toàn bộ thay đổi quyền, policy và bản ghi thử được rollback trong transaction. Điều đó **không chứng minh** cấu hình role backend/Supabase thật tương thích với policy mẫu; cần xác minh owner, bypassrls, service role và mọi đường truy cập trước khi triển khai.

Không gộp việc sửa quyền hàng loạt vào một thao tác `repair` Flyway. Cần một thay đổi an ninh riêng, đủ kiểm thử để không chặn backend hoặc tác vụ hợp lệ.

### Lỗi ứng dụng tái hiện được sau V41: HTTP thành công giả

Thử đăng ký tài khoản giả trên baseline V41: đăng ký email thường trả HTTP 201, đăng nhập trả 200 và đọc hồ sơ có JWT trả 200. Sau đó đăng ký cùng email nhưng viết hoa, lệnh INSERT bị unique index `idx_users_email_lower_trimmed` từ chối (SQLSTATE 23505), nhưng HTTP trả **200, body rỗng (0 byte)** thay vì 409. Đã tái hiện HTTP hai lần, lần thứ hai kiểm tra rõ độ dài body.

Điều tra mã `GlobalExceptionHandler.java:155–170` cho thấy `isClientDisconnect` dò chuỗi `aborted` trong mọi loại exception. Thông báo JDBC batch chứa “was aborted” nên bị coi nhầm là client ngắt kết nối; handler trả `null`, bỏ qua xử lý lỗi. Chạy trực tiếp lớp handler đã build từ `main`: exception có chuỗi “Batch entry 0 was aborted: duplicate key …” trả `null`; bỏ chữ “aborted” thì trả 500. Quy tắc này được đưa vào lịch sử ở commit `9b392aa` (`fix(realtime): prevent SSE connection leaks and preserve AI stream UX`). Bản sửa 10 lỗi trong PR #49 không thay đổi quy tắc này, nên không được giả định merge lại PR là hết lỗi.

Đây không phải bằng chứng mất dữ liệu hay CSDL hỏng; nó cho thấy app/CI có thể nhận HTTP thành công dù thao tác dữ liệu thất bại. Cần hotfix riêng: giới hạn nhận diện disconnect theo loại lỗi/chuỗi nguyên nhân IO thực sự, chuẩn hóa và kiểm tra email nhất quán, ánh xạ vi phạm unique thích hợp sang 409. Bổ sung kiểm thử âm tính cho exception SQL có chữ “aborted”, duplicate email khác hoa/thường, cùng kiểm tra response body và tác động dữ liệu; không chỉ assert HTTP 200. Chưa sửa các lớp sản phẩm trong phiên điều tra này.

## 5. Ma trận đánh giá phương án

| Phương án | Ưu điểm | Hạn chế/rủi ro | Đánh giá |
|---|---|---|---|
| Giữ nguyên cấu hình, thêm index riêng | Ít thay đổi tức thời | Không xử lý lịch sử, drift hoặc quyền; chưa đo lợi ích index | Chỉ là biện pháp ổn định tạm thời; thiếu index chưa được chứng minh gây sập app. |
| Bật validation hoặc `outOfOrder` ngay | Có thể phát hiện sai lệch | Validation chặn startup; `outOfOrder` không giải quyết migration cùng số đã bị thay nội dung | Không áp dụng trực tiếp. |
| `repair` trên thư mục migration hiện tại | Nhanh làm bớt lỗi lịch sử | Có thể đổi checksum/mô tả, đánh dấu migration thiếu deleted; không dựng lại schema/data; che xung đột V34 | Không khuyến nghị. |
| Dựng lại V24/V25 rồi chỉnh checksum cho khớp | Bề ngoài có lịch sử đủ | Không có chứng cứ SQL gốc; làm sai tính toàn vẹn kiểm toán | Loại bỏ. |
| Khôi phục tệp lịch sử đã chứng minh V34–V38 | Có checksum xác thực, giữ lịch sử | V19, V24/V25, V39 chưa giải quyết; CSDL mới vẫn khác nguồn | Hữu ích làm bằng chứng, chưa đủ là phương án cuối. |
| Baseline snapshot được thẩm định + lịch sử mới, giữ lịch sử cũ | Không cần giả SQL mất; tránh sửa checksum; hai đường mới/cũ có thể hội tụ | Cần review schema/role/seed; cutover sai có thể baseline nhầm DB; baseline không chứng minh quá khứ đúng | **Ứng viên ưu tiên**, đã kiểm thử ở mức schema/startup trong lab, chưa được duyệt deploy. |
| Tạo CSDL mới rồi chuyển dữ liệu thật | Tách xa lỗi lịch sử | Phức tạp dữ liệu, sequence, thời gian ngừng, realtime/storage/auth; rủi ro cao hơn hiện trạng | Chỉ dự phòng khi CSDL hiện có không còn dùng được; chưa thấy bằng chứng cần làm. |

## 6. Cách baseline thử nghiệm mới xử lý cả hai đường

1. Tạo SQL snapshot chỉ cấu trúc `public`, **loại bảng lịch sử cũ**, owner/ACL và lệnh meta của psql; thêm khai báo extension cần cho phần `public`. Đây là artifact thử nghiệm trong thư mục tạm, chưa đưa vào migration `main`.
2. Trên database trắng: Flyway chạy snapshot V41 và migration index V42; validate đạt.
3. Trên bản phục dựng V41: đặt `baselineOnMigrate=false` để không tự tiếp nhận DB không rỗng. Thử migrate trước khi chấp thuận baseline bị chặn như dự kiến. Sau kiểm tra fingerprint cột/constraint, gọi baseline **tường minh** vào bảng lịch sử mới; chạy V42, validate đạt.
4. 41 dòng lịch sử cũ được kiểm tra bằng digest trước/sau và giữ nguyên. Các fixture user/project/task/meeting cũng giữ nguyên digest. Chạy migrate lại thực thi 0 migration.
5. Thử sửa sai kiểu thời gian trong database âm tính riêng: fingerprint thay đổi, từ chối bước baseline; bảng lịch sử mới chưa được tạo. Fingerprint này chỉ là kiểm tra lab cho cột/constraint, **chưa phải công cụ preflight production đầy đủ**: phải bổ sung quyền, RLS/index/extension, seed, danh tính DB, lịch sử và phân loại drift được phép.
6. So hai database đã tiếp nhận và mới dựng: không khác metadata được so cho cột, constraint, index, RLS, policy và sequence. So sánh **không bao gồm** ACL/owner, seed, functions/triggers/enum definition đầy đủ, storage/auth hoặc schema Supabase khác; không được gọi là hai môi trường production hoàn toàn tương đương.

Các số V41/V42 và tên `flyway_reconciled_history_lab` chỉ dùng trong lab. Snapshot bao gồm index unique email sau V41 đã áp dụng trên nguồn; không đưa SQL sửa email vào baseline, không nhập dữ liệu tài khoản nguồn. Nếu đem baseline này vào CSDL khác trước V41, cần phân tích và chuyển đổi riêng, không tự đánh dấu đã có thay đổi.

## 7. Kết quả kiểm thử và giới hạn

| Kiểm tra | Kết quả thực tế |
|---|---|
| Build sạch reactor `main`, `package -DskipTests` | BUILD SUCCESS; không gọi đó là toàn bộ test đạt. |
| `EntityFlywaySchemaParityAuditTest` hiện có | 4/4 đạt dù drift vẫn tồn tại: test không kiểm tra lịch sử nguồn. |
| Hibernate 7.4.1 validate 27 entity | Đạt trên fresh main, bản phục dựng V40, bản đã baseline V41, baseline trắng V41. Chỉ xác minh mapping trong phạm vi Hibernate. |
| Flyway trên lịch sử cũ V40 | 9 lỗi; hồi phục V34–V38 trong thư mục tạm còn 4 lỗi. |
| Baseline trắng và tiếp nhận bản phục dựng V41 | Validate đạt cả hai, migrate lại không chạy thêm; bảo toàn digest fixture và lịch sử cũ. |
| Kiểm tra âm tính tự baseline DB không rỗng | Bị chặn. |
| Kiểm tra âm tính thay đổi kiểu cột | Bị fingerprint guard lab chặn trước baseline. |
| RLS với dữ liệu tổng hợp | Xác nhận đọc khi RLS tắt, bị chặn sau policy mẫu; toàn bộ transaction rollback. |
| Khởi động Spring Boot + HTTP | Ba trường hợp đạt: fresh main; baseline trắng V41; bản phục dựng được tiếp nhận V41. Health HTTP 200 và DB UP; API danh sách OAuth provider HTTP 200, danh sách rỗng do không cấp credential thật. |
| Auth thật trong lab với tài khoản giả | Register 201, login 200 và hồ sơ với JWT 200; duplicate email khác hoa/thường bị CSDL từ chối nhưng HTTP 200: **phát hiện lỗi**, không tính là pass nghiệp vụ. |
| Đối chứng handler lỗi SQL | Chuỗi “was aborted” bị phân loại nhầm disconnect, trả null; lỗi tương tự không có chữ đó trả 500. |
| Kiểm tra dịch vụ đang deploy | GET health công khai trả `UP` tại thời điểm điều tra; không chứng minh nghiệp vụ hoặc build đang chạy. |

Ứng dụng chạy trong container Java 25, mạng Docker `--internal`, chỉ liên lạc tới PostgreSQL lab, không gắn `.env` thật, credential tích hợp để trống hoặc giả. Dùng `SpringApplication.run(TaskPilotApplication.class, ...)` với cấu hình lab tường minh, bỏ qua đoạn nạp dotenv trong hàm `main`. Trường hợp baseline mới bật validation, baseline-on-migrate tắt. Có dummy Gemini key chỉ để dựng bean; không kiểm thử OAuth2/AI/S3/LiveKit thật. Container ứng dụng tự đóng sau kiểm tra, exit code 0 cho ba lần startup đầu. Poller có thể khởi động nhưng không có tài liệu nguồn để xử lý, mạng ngoài bị chặn.

Đây là kiểm thử khởi động và lược đồ; **không phải** UAT đầy đủ, kiểm thử tải, xác minh dữ liệu thật, khả năng khôi phục production hay kiểm thử tích hợp Supabase.

## 8. Phương án ứng phó đề nghị, theo thứ tự

### Ngay bây giờ — chưa ghi CSDL nguồn

- Xác nhận điều phối nhóm để tránh deploy/merge migration mới trong khi điều tra; phiên này chưa thay đổi workflow, khóa nhánh hoặc ngắt dịch vụ.
- Giữ artifact và cấu hình hiện tại, kiểm tra build đang phục vụ, xem log deploy/migration V41, xác minh backup/PITR trước lần V41 chạy. Dump schema/history **không phải backup dữ liệu** và không khôi phục được email cũ.
- Đánh giá quyền PostgREST cho bảng định danh/cuộc họp và các bảng liên quan; lựa chọn biện pháp hạn chế tạm cần người vận hành phê duyệt và kiểm thử. Không cắt quyền backend một cách đột ngột.
- Đối soát 9 tài khoản nghi bị ảnh hưởng email theo quy trình riêng, chỉ người có quyền xử lý dữ liệu cá nhân. Không tự động xóa hậu tố hoặc gộp tài khoản.

### Chuẩn bị bản sửa để review

- Thiết kế nhánh riêng từ `main` hiện tại, không sửa trực tiếp migration đang dùng trên production. Thẩm định snapshot, loại cấu trúc không cần, giữ lịch sử cũ bất biến và ghi nhận lý do chấp nhận baseline mới.
- Có script preflight chỉ đọc với allowlist môi trường, xác nhận version/fingerprint/backup; baseline tường minh một lần, không để app tự baseline DB bất kỳ.
- Tách hotfix xử lý exception và email thành PR nhỏ; CI phải assert cả status, body và dữ liệu sau request, không chỉ health hoặc HTTP 200. Không cần một migration sửa email hàng loạt để khắc phục lỗi báo thành công giả.
- Tách migration bổ sung index khỏi migration RLS hoặc thay đổi dữ liệu email. Thử SQL transaction/lock timeout; đánh giá `CREATE INDEX CONCURRENTLY` nếu cần giảm chặn ghi, cùng cách xử lý index invalid và Flyway transaction. Chưa quyết định index này cần triển khai ngay.
- Xác định seed cấu hình/skill tối thiểu và quyền/ownership: baseline schema hiện tại không mang dữ liệu `system_settings`, skills, bucket hoặc ACL thật. Bổ sung seed đã review, không copy secret từ CSDL.
- Kiểm thử nghiệp vụ auth, refresh, task, meeting, file, chat và tài liệu; trường hợp role owner/bypass RLS khác lab; schema Supabase và API trực tiếp. Khôi phục thử backup trên DB khác trước khi xin duyệt production.

### Điều kiện mới được triển khai

- Có phương án được duyệt, backup kiểm tra phục hồi, danh tính DB đích và người chịu trách nhiệm.
- Mọi preflight đạt; không có nguồn drift mới hoặc deploy chạy song song; số migration được nhóm dành chính thức.
- Trước bước SQL thay đổi dữ liệu: cần phê duyệt riêng. Bước baseline/index không được gộp việc đổi tài khoản.
- Sau triển khai: validate, metadata, dữ liệu thống kê, health và nghiệp vụ; có ngưỡng dừng. Rollback code không được coi là rollback DB; kế hoạch khôi phục phải mô tả riêng cho từng thay đổi.

## 9. Bằng chứng và khả năng tái hiện

Thư mục lab: `%TEMP%/opencode/taskpilot-flyway-isolated-20261109/`. Không đưa dump schema/history vào Git công khai khi chưa thẩm định.

- `public-schema.dump`, `flyway-history.dump`: mốc V40 ban đầu.
- `public-schema-v41.dump`, `flyway-history-v41.dump`: mốc V41 sau thay đổi ngoài phiên điều tra.
- `catalog-diff.txt`, `catalogs.json`; `catalog-diff-v41.txt`, `catalogs-v41.json`: metadata và chênh lệch.
- `snapshot-baseline-test.log`: kiểm thử Flyway/baseline và bảo toàn fixture.
- `hibernate-schema-test.log`: kiểm tra Hibernate thật.
- `rls-security-test.log`: tái hiện quyền truy cập dữ liệu giả, rollback.
- `src/main/java/SnapshotBaselineAudit.java`, `deep_catalog_audit.py`, `prepare_schema_baseline.py`, `AppStartupAudit.java`: mã harness, chỉ dùng lab. Không triển khai các tệp này lên production.
- Log startup được giữ trong các container `taskpilot-app-main-audit-20261109`, `taskpilot-app-snapshot-audit-20261109`, `taskpilot-app-adopted-audit-20261109` qua `docker logs`.
- Log auth và lỗi HTTP 200 rỗng được giữ trong `taskpilot-app-auth-audit-20261109` và `taskpilot-app-auth-edge-audit-20261109`; `exception-classifier-test.log` ghi đối chứng handler không cần mạng. Các log SQL chỉ có tài khoản/mật khẩu băm giả của lab; không đưa nguyên log lên tài liệu báo cáo học phần.

Không công bố credential CSDL nguồn hoặc nội dung dữ liệu người dùng trong tài liệu này.

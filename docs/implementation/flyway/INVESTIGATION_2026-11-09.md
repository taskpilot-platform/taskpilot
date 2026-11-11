# Điều tra Flyway và phương án ứng phó — 09/11/2026

> **Bản ghi lịch sử 09/11:** các kết luận dưới đây mô tả thời điểm điều tra/hotfix ban đầu. Ngày 10/11, nguồn đã tiếp nhận Flyway V42, validate đạt và app mới đã deploy/live smoke thành công; xem [kết quả phát hành cuối](RECOVERY_RELEASE_2026-11-10.md). Dùng [quy trình nhóm](../../development/FLYWAY_TEAM_WORKFLOW.md) cho migration mới, không lặp lại hướng recovery thử nghiệm cũ.

> **Cập nhật quan trọng sau mốc điều tra ban đầu:** `main` trên origin đã merge PR #49 rồi revert qua PR #50, hiện ở `759ede5` với cây mã giống `c621c53`. CSDL nguồn đã áp dụng V41 trong khoảng thời gian đó; trạng thái V40 ở các mục cũ chỉ là ảnh chụp trước thay đổi. Xem [đánh giá phương án và kiểm thử sâu](SOLUTION_ASSESSMENT_2026-11-09.md) để tránh dùng kết luận cũ cho môi trường hiện tại. Không có lệnh ghi nào của phiên điều tra này chạy trên CSDL nguồn.

## Phạm vi và an toàn

- Nguồn mã chuẩn: backend nhánh `main`, commit `c621c53`, đối chiếu với `origin/main` tại thời điểm điều tra.
- CSDL cấu hình trong môi trường làm việc được truy vấn bằng các câu `SELECT`; các truy vấn lược đồ và dữ liệu tổng hợp sử dụng `BEGIN READ ONLY` và `ROLLBACK`. Không áp dụng SQL thay đổi, `migrate`, `repair` hay `clean` lên CSDL đó.
- Thử nghiệm ghi chỉ thực hiện trên PostgreSQL **18.6** chạy cục bộ tại `127.0.0.1:55439`, cụm tạm độc lập và dữ liệu tổng hợp. Không sao chép dữ liệu hoặc thông tin đăng nhập người dùng thực.
- CSDL được kiểm tra dùng PostgreSQL **17.6**; Flyway dự án là **11.3.2**, có cảnh báo PostgreSQL 18 chưa được phiên bản Flyway này kiểm thử hỗ trợ. Do khác phiên bản PostgreSQL, kết quả cô lập chứng minh hành vi trong điều kiện thử nghiệm, chưa thay thế thử nghiệm trên bản sao PostgreSQL 17 có đầy đủ extension và cấu hình của môi trường đích.
- Không khẳng định CSDL được cấu hình là CSDL production, cũng như không suy diễn cấu hình hoặc mã nguồn của dịch vụ đang chạy từ tệp cục bộ.

## 1. Sự khác nhau của V34

`main` chứa `V34__add_scheduled_time_to_project_meetings.sql`: thêm hai cột `scheduled_start_time`, `scheduled_end_time` kiểu `TIMESTAMP WITH TIME ZONE`, sau đó tạo chỉ mục `idx_project_meetings_scheduled_start`. `ProjectMeetingEntity` và các lớp dịch vụ cuộc họp có sử dụng hai cột này.

Lịch sử Flyway của CSDL thay vào đó ghi V34 là `V34__create_recommendation_snapshots_tables.sql`, tạo các bảng snapshot và ứng viên gợi ý. Đó là **hai migration độc lập dùng cùng số 34**, không phải chỉ đổi tên. Bản V34 snapshot cùng V35–V38 trên nhánh `fix/heuristic-correctness` khớp tên và checksum trong lịch sử CSDL; đây là nguồn tham khảo cho việc khôi phục lịch sử, không phải đề xuất merge toàn nhánh.

Lược đồ CSDL được kiểm tra hiện có cả hai cột lịch họp, đúng kiểu `timestamp with time zone` và cho phép NULL như SQL V34 trên `main`; có 79 cuộc họp, 41 giá trị thời điểm bắt đầu và 13 giá trị thời điểm kết thúc. Chưa xác định được cơ chế hoặc thời điểm hai cột này được tạo. Không thấy chỉ mục `idx_project_meetings_scheduled_start` hay chỉ mục khác của bảng có định nghĩa chứa các cột `scheduled_*`. Tổng kích thước bảng và các chỉ mục được thống kê là 144 kB tại thời điểm kiểm tra; đây không phải cam kết về thời gian khóa khi triển khai.

Kết luận: dữ liệu thời gian cuộc họp **không bị thiếu toàn bộ**; sai lệch cụ thể đã quan sát là **lịch sử V34 trỏ tới migration khác và thiếu chỉ mục dự định**. Chưa đo hiệu năng hay chứng minh việc thiếu chỉ mục gây lỗi chức năng.

## 2. Tổng quan lịch sử khi đối chiếu `main`

So sánh tên và checksum tính theo thuật toán CRC32 cho SQL của Flyway, không thay đổi CSDL:

| Phiên bản | Kết quả |
|---|---|
| 31 migration khác | Tên và checksum trùng. |
| V19 | Cùng tên, checksum CSDL `-1337296038`, mã `893775703`; chưa xác minh bản gốc. |
| V24–V25 | Có trong lịch sử, thiếu tệp trong `main`; chưa tìm được bản chính xác trong các nhánh cục bộ đã kiểm tra. |
| V34 | Hai nội dung khác nhau như mục 1. |
| V35–V38 | Có trong lịch sử, thiếu trên `main`; tệp nhánh `fix/heuristic-correctness` khớp checksum. |
| V39 | Cùng tên OAuth2; checksum CSDL `0`, mã `-1429490097`; chưa rõ nguồn gốc. Bảng, các cột danh sách cơ bản và hai chỉ mục có tồn tại, `users.password_hash` nullable, nhưng chưa xác nhận đầy đủ constraint hoặc dữ liệu. |
| V40 | Cùng migration cuộc họp, tên và checksum trùng. |

`main` không có V41. V41 trên nhánh `fix/def-001-010-and-e2e-ci` chưa merge, không thuộc phạm vi triển khai `main`. Không được chạy nó trong đợt điều tra này.

### Nguồn gốc cấu hình validation

- Commit `b8a3de8fcaadf0094b9afe290d7abe17ece686f8` ngày 27/06/2026 thêm `spring.flyway.validate-on-migrate: false` vào `application.yml`. Đây là commit tính năng AI, không có giải thích lý do tắt validation trong thông điệp commit. Cùng commit thêm tệp V19 hiện tại; sự kiện này không chứng minh nguyên nhân checksum V19 trong CSDL khác.
- Commit `e35d70e` ngày 25/07/2026 bỏ `spring-boot-starter-flyway` khỏi `taskpilot-app/pom.xml` trên dòng lịch sử liên quan. Commit `e607ae7`, vào `main` qua PR #47 ngày 03/11/2026, bổ sung lại starter để Spring Boot 4 tự cấu hình Flyway; không bật lại validation. PR #48 thêm test tĩnh, không kiểm tra checksum hoặc lịch sử CSDL thật.
- Với cấu hình hiện tại, Flyway có thể migrate mà không kiểm tra tính nhất quán các migration đã áp dụng trước đó; migration đã ghi nhận không được chạy lại chỉ vì tệp cùng số bị đổi. Điều này giải thích cơ chế có thể cho phép khởi động dù drift, như thử nghiệm ở mục 3.2, nhưng không chứng minh phiên bản hoặc cấu hình đang chạy trên môi trường triển khai.

## 3. Thử nghiệm cô lập

### 3.1 SQL V34 thật của `main`

Dùng tệp V34 hiện tại, bảng và dữ liệu tổng hợp trong ba schema độc lập:

1. **Đã có hai cột đúng kiểu và một bản ghi; thiếu chỉ mục**: chạy V34 hai lần. Cả hai lần thành công, chỉ mục đúng được tạo và giá trị mẫu được bảo toàn.
2. **Chưa có hai cột và chỉ mục**: chạy V34 thành công; hai cột `timestamptz` được tạo.
3. **Cột đã tồn tại sai kiểu và tên index đã tồn tại nhưng trỏ cột khác**: `IF NOT EXISTS` bỏ qua cả hai; định nghĩa sai **không được sửa**. Vì vậy không thể dựa vào thông báo chạy thành công để khẳng định lược đồ đúng.

### 3.2 Hành vi Flyway 11.3.2

Thử nghiệm tối giản, không phải toàn bộ migration TaskPilot: tạo lịch sử mẫu V34 snapshot và V40, rồi chuyển thư mục migration sang V34 lịch họp và V40 tương ứng. `validate-on-migrate=false`, mặc định `outOfOrder=false`:

- `validateWithResult()` trả **không hợp lệ**.
- `migrate()` báo không có migration cần chạy; V34 lịch họp không chạy lại và chỉ mục vẫn thiếu.
- Bổ sung một migration mẫu **V42** chứa SQL V34 lịch họp: Flyway chạy một migration mới, tạo index và giữ nguyên giá trị mẫu. V42 chỉ là **số minh họa trong phòng thí nghiệm**, chưa được dành chỗ hoặc đề xuất triển khai cố định.
- Trong schema mẫu thứ hai, bật `validate-on-migrate=true` trước khi xử lý xung đột V34 khiến `migrate()` ném `FlywayValidateException`; V42 không được áp dụng. Do đó không bật validation đột ngột trên môi trường đang vận hành khi chưa xử lý lịch sử.

Tệp và dữ liệu thử nghiệm nằm tại `%TEMP%/opencode/taskpilot-flyway-isolated-20261109/`, không thuộc kho mã. Kiểm thử chưa bao gồm luồng khởi động Spring Boot đầy đủ, Flyway trên toàn bộ CSDL thật, đo lock, hay kiểm thử triển khai.

## 4. Nguyên tắc ứng phó ít ảnh hưởng

1. Giữ dịch vụ hiện tại ổn định; không tự động restart/deploy, không chạy `flyway repair`, `clean`, migration SQL hoặc thay đổi cấu hình Flyway trên CSDL được kiểm tra. Chưa biết trạng thái dịch vụ đang chạy hay cấu hình thực tế, cần xác minh riêng.
2. Chụp trạng thái, sao lưu và dựng **bản sao cô lập được phép sử dụng**, cùng PostgreSQL 17, extension, lịch sử Flyway và lược đồ; tránh sao chép dữ liệu cá nhân nếu không cần. Xác nhận backup/khả năng khôi phục trước bất cứ thay đổi nào.
3. Kiểm kê tất cả môi trường và nhánh có thể được triển khai, phân biệt các nơi đã áp dụng từng phiên bản, checksum và lược đồ; dành số migration mới chỉ sau khi thống nhất với nhóm.
4. Dựng phương án hòa giải lịch sử: phục hồi tệp V34–V38 đúng checksum; tìm V24–V25 và bản V19 gốc; đối chiếu đầy đủ V39 với DDL, constraint và lịch sử triển khai. Không viết lại lịch sử cũ hoặc dùng `repair` hàng loạt để che xung đột.
5. Xem xét một migration **mới sau phiên bản cao nhất đã dành** cho phần lịch họp; SQL phải an toàn cả với môi trường đã/chưa có cột, và kiểm tra riêng loại cột, index cùng tên, lock và dữ liệu. Giữ lại nguyên giá trị đã có. Việc phục hồi lịch sử V34 và chuyển thay đổi lịch họp sang phiên bản mới phải được thử đồng thời cho cả CSDL mới và CSDL đã có lịch sử, không chỉ cho một môi trường.
6. Thử Flyway validate/migrate, kiểm tra lược đồ và dữ liệu, khởi động `main`, kiểm thử cuộc họp và tác vụ nền trên bản sao. Kiểm tra startup khi extension/biến môi trường không đủ và chuẩn bị kế hoạch quay lại phiên bản ứng dụng, phục hồi CSDL nếu cần.
7. Sau khi xác minh và được phê duyệt, triển khai trong khoảng thời gian phù hợp với giám sát, backup, đầu mối dừng/rollback và kiểm tra sau triển khai. Chỉ bật validation sau khi tất cả sai lệch đã được hòa giải và xác minh trên mọi môi trường liên quan.

## 5. Vấn đề còn mở

- Nguồn chính xác V19, V24, V25 và lý do V39 có checksum 0.
- Lịch sử tạo hai cột lịch họp, sự tương thích đầy đủ các constraint/index OAuth2, và ảnh hưởng của nhánh adaptive ở các CSDL khác.
- Phiên bản ứng dụng, runtime configuration và việc dùng chung CSDL của các dịch vụ đang chạy.
- Tính khả thi của một CSDL mới từ toàn bộ migration hiện có: chưa chạy kiểm thử đầy đủ vì local PostgreSQL thiếu các extension/dịch vụ tương đương môi trường mục tiêu.
- Thời gian khóa thực tế khi thêm index ở môi trường đích; số liệu kích thước là ảnh chụp nhất thời, không thay thế thử nghiệm.

## 6. Chuẩn bị bản sao cô lập sau khi được đồng ý

- Đã dùng `pg_dump --schema-only --schema=public --no-owner --no-privileges` để lưu cấu trúc `public` vào thư mục tạm `%TEMP%/opencode/taskpilot-flyway-isolated-20261109/public-schema.dump`. Không sao chép dữ liệu bảng người dùng.
- Đã sao chép **chỉ dữ liệu bảng lịch sử Flyway** vào `flyway-history.dump` trong cùng thư mục; chưa sao chép dữ liệu ứng dụng. Bản sao này có thể chứa tên người cài đặt migration, cần quản lý như dữ liệu nội bộ.
- Nguồn có các extension `vector`, `pgcrypto`, `uuid-ossp`, `pg_stat_statements`, `supabase_vault` và nhiều schema Supabase ngoài `public`; bản dump chỉ `public` **không phải** bản sao đầy đủ cấu trúc toàn hệ thống. Cần xem lại phụ thuộc và chuẩn bị bản sao tương đương trước khi kết luận khả năng triển khai.
- Docker Desktop được khởi động để thử image `pgvector/pgvector:pg17`, container `taskpilot-flyway-lab-20261109`. Container đã dừng mã **137**, chưa xác định chính xác nguyên nhân; Docker báo `OOMKilled=false`, vì vậy không khẳng định là thiếu RAM. Không phục hồi dump hay chạy thử trên container này. Các phép thử PostgreSQL 18 tại mục 3 vẫn là thử nghiệm mô phỏng, không phải bản sao CSDL nguồn.
- Chưa tạo migration sửa hay sửa lịch sử nguồn. Trước khi thử tiếp, cần kiểm tra khả năng hoạt động ổn định của Docker/WSL và các phụ thuộc extension/schema Supabase; dựng bản sao mới, xác nhận hoàn tất restore rồi mới thực hiện thử nghiệm.

### Cập nhật sau khi khởi động lại Docker

- Đã khởi động lại container và dựng PostgreSQL **17.11** với `vector`, `pgcrypto`, `uuid-ossp` và các role giả lập cho chính sách RLS. Restore cấu trúc `public` cùng 40 dòng lịch sử Flyway thành công trong database `taskpilot_restore`; số dòng `users` và `project_meetings` đều bằng 0. Bản sao không bao gồm các schema Supabase khác hay dữ liệu ứng dụng, nên không phải bản sao production đầy đủ. Một database thử nghiệm đầu tiên restore chưa hoàn tất do thiếu role; đã dùng database mới để restore lại, không xóa dữ liệu.
- Trên bản phục dựng, Flyway 11.3.2 `validateWithResult` xác nhận 9 lỗi: checksum V19, V34, V39; thiếu tệp V24, V25, V35, V36, V37, V38. `migrate` với validation tắt thực thi 0 migration.
- Tạo migration **V42 chỉ trong thư mục tạm** từ SQL lịch họp V34 và áp dụng duy nhất vào `taskpilot_restore`: Flyway chạy thành công, index được tạo, hai cột đã tồn tại được bỏ qua. Sau đó `validateWithResult` vẫn thất bại vì các sai lệch lịch sử chưa được hòa giải. Đây chỉ giải quyết thiếu index trong bản sao, **không phải bản sửa toàn diện hoặc SQL đã được duyệt triển khai**; V42 chưa được dành số.
- Container đang là môi trường thử nghiệm; chưa chạy `repair`, chưa thay đổi CSDL nguồn, chưa tạo migration chính thức trên `main`. Việc chạy ứng dụng đầy đủ, dữ liệu giả, các môi trường khác và xác minh các tệp lịch sử còn thiếu vẫn chưa hoàn tất.
- Thử tiếp trong thư mục migration tạm: khôi phục các tệp V34–V38 khớp lịch sử từ nhánh `fix/heuristic-correctness`, chuyển phần lịch họp sang migration V42 thử nghiệm đã áp dụng. Flyway validate trên bản sao còn **4 lỗi**: checksum V19/V39 và thiếu V24/V25. Không cần sửa lịch sử CSDL để loại bỏ 5 lỗi V34–V38 trong phép thử này. Chưa xác minh phương án cho CSDL mới hay những môi trường có lịch sử khác; không được tự ý áp dụng các thay đổi tạm này lên `main` hoặc CSDL thật.

### Thử nghiệm CSDL trắng và truy tìm migration gốc

- Trên PostgreSQL 17.11 trắng, chạy trực tiếp toàn bộ migration `main` với Flyway 11.3.2: **34 migration thực thi thành công**. Tuy nhiên `team_chat_rooms`, `team_chat_messages`, `team_chat_room_members`, `team_chat_message_reactions` và `recommendation_snapshots` không tồn tại, trong khi chúng tồn tại ở bản phục dựng cấu trúc nguồn. Migrate thành công không đồng nghĩa cấu trúc hai môi trường tương đương.
- Trên một database trắng riêng khác, chạy tập migration tạm đã bổ sung V34–V38 từ lịch sử và V42 thử nghiệm: **39 migration chạy, Flyway validate thành công**. Đây chưa phải sửa chữa hoàn chỉnh vì V24/V25 vẫn thiếu và chưa kiểm thử toàn bộ ứng dụng hoặc dữ liệu.
- Đã tìm tên tệp V24/V25 trong các nhánh Git cục bộ, kho backup, tệp workspace và các commit Git không còn tham chiếu; chưa tìm thấy tệp gốc. Không được tự chế tệp hoặc sửa checksum để giả lập lịch sử đã được chứng minh.
- Đối chiếu V39 trên bản phục dựng: khóa chính, khóa ngoại, unique, hai index và các cột hiện ra phù hợp với tệp V39 hiện tại; `password_hash` nullable. Đây là bằng chứng tương thích lược đồ ở phạm vi đã kiểm tra, không xác định nguyên nhân checksum 0 hoặc khẳng định mọi tác động và dữ liệu khớp. Cần phương án hòa giải lịch sử có phê duyệt, backup và kiểm thử trên bản sao trước khi can thiệp CSDL nguồn.

### Thử phương án lịch sử Flyway mới, giữ nguyên lịch sử cũ

- Do không tìm được SQL gốc V24/V25, đã thử trên một database **phục dựng riêng**: dùng tên bảng lịch sử mới `flyway_reconciled_history_lab`, baseline phiên bản 40, và chỉ chứa migration V42 lịch họp thử nghiệm. Kết quả: migrate và validate thành công; 40 dòng lịch sử cũ vẫn giữ nguyên, bảng nhóm chat vẫn tồn tại và index được tạo. Không sửa checksum hay đánh dấu các migration lịch sử là deleted.
- Thử cùng cấu hình trên database **trắng**: thất bại vì `project_meetings` không tồn tại. Phương án này do đó chưa dùng được cho cả hai loại môi trường; cần thiết kế và xác minh baseline schema cho CSDL mới, bao gồm bảng nhóm chat, RLS, extension, dữ liệu cấu hình thiết yếu và các phụ thuộc Supabase. Không dùng trực tiếp dump production làm migration khi chưa thẩm định nội dung, quyền và tính di động.
- Thử nghiệm này không chứng minh lược đồ nguồn hiện tại đúng cho mọi môi trường và không cho phép tự động baseline CSDL bất kỳ. `baselineOnMigrate` có thể đánh dấu nhầm một CSDL không rỗng; phương án thật phải yêu cầu xác minh danh tính CSDL và các điều kiện tiên quyết trước khi baseline, cùng bản sao lưu và phê duyệt.

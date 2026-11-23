# Trạng thái bàn giao P1–P8

Mốc mã nguồn đã kiểm chứng là backend `ba1e8a4` và frontend `ff3b71d` trên nhánh `fix/full-remediation` của mỗi repo.

| Phạm vi | Trạng thái |
| --- | --- |
| P1–P8 | Đã kiểm chứng trên môi trường cô lập. Mốc backend đã chạy PostgreSQL V51. Hai repo cần được review cùng nhau. |
| P4 và P7 với hạ tầng thật | Còn kiểm tra ACL Drive cũ, họp LiveKit đa điểm và mở bản ghi Drive từ máy khác. |
| P9 | Chờ giảng viên phản hồi về OGD trong đề cương và phương án học theo đợt. |
| P10–P11 | Hạng mục tiếp theo theo [kế hoạch](PLAN.md). Nhóm thống nhất người phụ trách khi nhận việc. |

Hồi quy tại mốc trên qua 670 test backend, 169 test Vitest, 173 ca Chromium cô lập và build frontend. Ca Chromium nối API thật đã kiểm tra thẻ gợi ý giao task, nộp và duyệt qua API, rồi thấy đúng một mẫu trong báo cáo Adaptive theo phạm vi. Phần trình bày của Copilot trong ca đó dùng dữ liệu mô phỏng.

Khi nhận một hạng mục, ghi mã phase, phạm vi và người phụ trách ở PR hoặc issue tương ứng. Khi bàn giao, cập nhật mốc hai repo, kết quả kiểm chứng và việc còn chờ.

## Bản sửa Copilot sau P8, kiểm chứng ngày 23/11/2026

Diff trên `fix/full-remediation` của hai repo đã được kiểm chứng với 697 test backend, 180 test frontend và build frontend. Lượt frontend mặc định có timeout ở test admin khi chạy đồng thời với backend và UAT. Chạy lại toàn bộ với `npm run test -- --maxWorkers=2` đã qua. Các test backend dùng PostgreSQL cô lập.

Copilot mặc định gọi Groq GPT-OSS 120B trước, rồi đến các Gemini đã qua probe và OpenRouter đã qua probe. Thứ tự quản trị viên đã lưu trong `ai.model_priority` được giữ. Sau khi deploy backend mới, quản trị viên có thể sao lưu setting rồi đặt `valueJson` thành `{"models":[]}` qua `PUT /api/v1/admin/settings` để dùng chuỗi mặc định. Khi khôi phục, ghi lại đúng giá trị đã sao lưu.

UAT Chromium với dữ liệu tổng hợp và DB V51 riêng đã kiểm tra giao việc qua API model thật và ca giả lập Groq lỗi sau khi tạo đề xuất để Gemini thật tiếp quản. Cả hai ca đều có đúng một thẻ xác nhận, task chưa được gán trước khi bấm và được gán đúng sau xác nhận. Đã kiểm tra đổi thứ tự theo setting và khôi phục setting cũ trong DB cô lập.

Test hồi quy bao phủ model gửi token rồi treo, loại phần trả lời dở khi đổi model, tool bỏ qua interrupt và hai tool cùng vòng trong đó đề xuất hoàn tất trước một tool bị treo. SSE kết thúc theo hạn lượt, giữ đề xuất đã ghi nhận và bỏ qua kết quả đến muộn. Tool bỏ qua interrupt có thể tiếp tục dọn dẹp sau khi SSE đóng.

Backend và frontend cần được review và triển khai cùng nhau do bổ sung mã trạng thái i18n và sự kiện `reset_stream`. Kết quả trên áp dụng cho diff đang bàn giao. Môi trường live chưa được cập nhật trong lần kiểm chứng này.

## Tích hợp `main` mới (Egress / B2 / Drive / Prometheus) sau rebase, kiểm chứng ngày 23/11/2026

Hai nhánh `fix/full-remediation` đã rebase lên `main` mới: backend rebase lên `0e1852a` (HEAD sau replay `e93c5d6`, ref bảo vệ `backup/pre-main-rebase-20261123` tại `328b72a`), frontend rebase lên `6277d9f` (HEAD sau replay `19e6d66`, ref bảo vệ `backup/pre-main-rebase-20261123` tại `421efa4`). Các thay đổi tích hợp đang nằm trong working tree đã kiểm chứng, chờ Codex review trước khi chia commit.

- **Schema & Flyway (DB chung kiểm tra chỉ đọc lúc `2026-11-23T10:04:28Z`):** `deployed_version = 43`, `main_version = 43` (`V43__add_egress_recording_and_storage_lifecycle.sql`, giữ nguyên nội dung và checksum), `local_version = 52`, `next_version = 53` (chưa giữ chỗ). Chín migration remediation chưa triển khai nằm ở `V44`–`V52` trong `taskpilot-app/src/main/resources/db/reconciled` (`V47` đã thêm `'B2'` vào hai ràng buộc `CHECK` provider của `storage_upload_operations` và `storage_cleanup_jobs`). Đã chứng minh bằng PostgreSQL 17 cô lập: khởi tạo fresh lên `V52`, nâng cấp `V42 -> V43 -> V52` và `V43 -> V52` đều thành công, giữ nguyên checksum `main` và dữ liệu hiện có.
- **Tích hợp hành vi:** Giữ LiveKit Cloud Egress -> Backblaze B2 -> Google Drive private archive làm luồng ghi hình chính, giữ fallback ghi trình duyệt -> B2 (client helper vẫn giữ trần 5 phút / 50 MiB kế thừa P7), giữ tương thích plural client recording endpoints (`ClientMeetingRecordingService`), bảo toàn stable UID / `FileStreamTokenService` gắn `token_version` + trạng thái tài khoản, webhook xác minh kép Egress + room lifecycle, Range/`If-Range`/HEAD không mở body provider, durable cleanup/purge nhận diện B2/S3/Drive và chuỗi bảo mật `/actuator/prometheus`.
- **Kết quả kiểm chứng trên diff đang bàn giao:**
  - Backend full gate (`mvnw.cmd -o clean test -Dremediation.runId=rebase-egress-20261123T100757Z`): **768/768 tests PASS, 151/151 suites, 0 failures/errors/skipped** (`contracts` 49, `infrastructure` 111, `users` 56, `ai` 288, `projects` 108, `app` 156).
  - `tools/flyway-team/verify_integration.py`: **34/34 mandatory integration cases PASS**, `FRESH_AND_UPGRADE_BACKEND=true`.
  - `tools/verification/verify_phase.py`: **P0–P7 đều PASS** trên cùng `runId`.
  - `tools/flyway-team/check.py` (chỉ đọc Supabase & offline), `sql_policy.py` và unit test tool (`29/29` Flyway + `22/22` verification): **PASS**.
  - Frontend (source không đổi so với lượt chốt trước đó): **205/205 Vitest (37/37 files) PASS**, `npm run build` **PASS**, Chromium cô lập **173/173 PASS** (bao gồm `e2e/09-recording-capture-finalize.spec.ts` ép Egress 503 -> B2 fallback -> phát lại từ tài khoản thứ hai).
- **Chưa kiểm chứng trên hạ tầng thật:** Các ca `@Tag("provider-live")` tách riêng; live LiveKit Cloud Egress, Backblaze B2 bucket thật, private Google Drive archive thật, Grafana Cloud scrape và áp dụng `V44`–`V52` lên DB chung vẫn chưa chạy và cần nghiệm thu riêng sau khi merge.

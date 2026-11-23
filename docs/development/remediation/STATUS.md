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

## Sửa các phát hiện review R1–R7 (B2 proxy playback, multi-clip, durable cleanup & Egress race), kiểm chứng ngày 23/11/2026

Các phát hiện review R1–R7 sau tích hợp rebase đã được xử lý trên hai nhánh `fix/full-remediation` (backend HEAD `0a6820d` + working tree R3–R7; frontend HEAD `d547a62` + working tree R1 TS/lint polish & R3/R7 — chưa push/merge/deploy, phần R3–R7 giữ nguyên chưa commit để Codex review):

- **R1 (Retry barrier ghi hình cục bộ):** `LiveMeetingRoom.tsx` chặn thay room khi bấm Retry cho đến khi phiên ghi đang sở hữu finalize xong, cách ly theo owner session và giữ lại nút xuất cục bộ khi IndexedDB lỗi/không bền vững.
- **R2 (Phương án A — B2 playback qua proxy backend):** `MeetingRecordingService.getPlaybackUrl()` không bao giờ cấp direct presigned GET URL cho B2; toàn bộ playback B2 đi qua endpoint proxy có xác thực (`/api/v1/projects/{projectId}/files/{fileId}/stream?token=...`) kiểm tra lại membership, trạng thái tài khoản và `token_version` ở mỗi request/seek; `ProjectFileService` giới hạn open-ended Range (`bytes=start-`) cho B2 tối đa `8 MiB`/chunk để tránh mở luồng dài giữ tài nguyên; không có fallback tự động sang presigned GET khi proxy lỗi.
- **R3 & R7 (Lịch sử nhiều clip & khôi phục khi cuộc họp đang diễn ra):** Ghi nhận idempotent mọi clip hoàn tất từ Cloud Egress (`CLOUD_EGRESS` / `CLOUD_COMPOSITE`) và B2 fallback vào bảng `project_meeting_recordings` (không cần Flyway migration mới); mở rộng `GET .../recording/playback-url?fileId=...` kiểm tra file thuộc đúng cuộc họp; `MeetingRecordingModal` hợp nhất/khử trùng lặp theo `fileId` và giữ clip local `READY` chưa đồng bộ; `ProjectMeetingsTab` hiển thị lối vào danh sách bản ghi/khôi phục ngay trên thẻ cuộc họp đang `ACTIVE`.
- **R4 & R5 (Durable B2 cleanup & reservation cho presigned fallback PUT):** `RecordingArchiveWorker` ghi durable `StorageCleanupJobEntity` cho provider `B2` trong cùng transaction `completeArchiveToGoogleDrive` (và cho Drive orphan khi xóa trực tiếp thất bại); `MeetingRecordingService.presignFallbackUpload` đặt trước `StorageUploadOperation` cho B2 với lease `uploadTokenTtl + 15m` gắn `operationId` vào upload token, `completeFallbackUpload` đánh dấu provider-written + attach trong cùng transaction tạo `ProjectFileEntity` và bù trừ bền vững nếu file rỗng/quá cỡ; `StorageUploadOperationService.resolveKnownLocator()` hỗ trợ cả `S3` và `B2`.
- **R6 (Đua giữa Egress start RPC và đóng phòng):** `startRecording` gắn `egressId` qua transaction `REQUIRES_NEW` ngắn (không giữ DB lock qua RPC); nếu cuộc họp đã chuyển sang `ENDING`/`ENDED`, vẫn lưu `egressId`, gọi `stopRecordingIfActive` ngoài transaction và trả `409`; `recoverStartingClaim` và `EgressReconciliationJob` đều chủ động gọi dừng Egress chưa terminal khi cuộc họp ở trạng thái `ENDING`/`ENDED`.
- **Kết quả kiểm chứng cuối cùng trên diff đang bàn giao:**
  - Backend full gate (`mvnw.cmd -o clean test -Dremediation.runId=r1-r7-fixes-20261123T112821Z`): **782/782 tests PASS, 152/152 suites, 0 failures/errors/skipped** (`contracts` 49, `infrastructure` 114, `users` 56, `ai` 288, `projects` 119, `app` 156).
  - `tools/flyway-team/verify_integration.py`: **34/34 mandatory integration cases PASS**, `FRESH_AND_UPGRADE_BACKEND=true`.
  - `tools/verification/verify_phase.py`: **P0–P7 đều PASS** trên `RUN_ID=r1-r7-fixes-20261123T112821Z`.
  - Flyway source checker & SQL policy (kiểm tra DB chung chỉ đọc lúc `2026-11-23T11:37:47Z`, không ghi DB): `deployed_version = 43`, `main_version = 43`, `local_version = 52`, `next_version = 53`, `merged_not_deployed = []` (lưu ý `origin/main` đã có commit mới `4e034a0`, chưa thêm migration mới); unit test tool (`29/29` Flyway + `22/22` verification): **PASS**.
  - Frontend: Vitest **213/213 PASS (39/39 files)**, `npm run build` **PASS**, ESLint trên toàn bộ file mới/sửa sạch **0 lỗi / 0 cảnh báo** (còn cảnh báo/lỗi `no-explicit-any` baseline ở các dòng không sửa của `LiveMeetingRoom.tsx`, `ProjectMeetingsTab.tsx`, `meeting.service.ts`), Chromium cô lập **173/173 PASS** (bao gồm `e2e/08` và `e2e/09` với proxy Range `206` ở tài khoản thứ hai).
- **Nghiệm thu tải HF CPU Basic (`1 -> 3 -> 6` người xem đồng thời):** Đã chuẩn bị kịch bản/checklist tại [HF_PLAYBACK_LOAD_ACCEPTANCE.md](HF_PLAYBACK_LOAD_ACCEPTANCE.md), trạng thái **NOT RUN** (chưa deploy Space test, chưa chạy tải trên hạ tầng thật, không suy diễn từ Chromium mô phỏng).
- **Chưa kiểm chứng trên hạ tầng thật:** Các test `@Tag("provider-live")`, tải thực trên Hugging Face CPU Basic, LiveKit Cloud Egress thật, Backblaze B2 thật, Google Drive archive thật, Grafana Cloud scrape và chạy Flyway `V44`–`V52` trên DB chung.

### Khép kín 5 điểm review bổ sung F1–F5 & bài test đồng thời PG17 (R6), kiểm chứng ngày 23/11/2026

Các điểm P2 từ [2026-11-23-agy-r1-r7-followup-review.md](../../../../docs/superpowers/reviews/2026-11-23-agy-r1-r7-followup-review.md) đã được khắc phục trên working tree hiện tại (chưa tạo thêm commit, chưa push/merge/deploy, chưa tích hợp `origin/main` OTLP tại `4e034a0`):

- **F1 (R5 — Atomicity fallback upload):** Gộp tạo `ProjectFileEntity`, `markAttachedInCurrentTransaction`, `linkFallbackRecording` và ghi `project_meeting_recordings` vào **cùng một transaction `REQUIRES_NEW`**. Nếu cloud `claimRecordingStart` chen giữa hoặc ghi history lỗi, toàn bộ rollback và operation giữ nguyên ở `PROVIDER_WRITTEN` để retry/reconcile (không còn `ATTACHED` mồ côi).
- **F2 (R3 — Atomicity & replay repair cho cloud `COMPLETED`):** Gộp `transitionRecordingCompleted` và ghi `CLOUD_EGRESS`/`CLOUD_COMPOSITE` vào cùng một transaction `REQUIRES_NEW`; bổ sung nhánh repair idempotent khi replay `EGRESS_COMPLETE` kể cả nếu row đã ở `COMPLETED`.
- **F3 (R5 — Post-PUT-window durable cleanup):** Object bị reject (rỗng/quá cỡ/trùng lặp) được xóa best-effort ngay lập tức đồng thời enqueue job dọn B2 bền vững với `next_attempt_at = putExpiresAt + 5m` (không chuyển `SUCCEEDED` sớm khi URL presigned PUT còn hiệu lực; re-PUT trong cửa sổ URL vẫn bị dọn sau khi URL hết hạn). **Giới hạn đã biết:** S3/B2 presigned PUT không áp đặt hard timeout tổng thời lượng truyền TCP phía provider; trường hợp cực đoan PUT treo nhiều giờ vượt grace window cần B2 bucket lifecycle / orphan sweep ở tầng hạ tầng (đã ghi rõ trong [DESIGN.md](DESIGN.md)).
- **F4 (R3 — Logical-clip idempotence BE + FE):** Gắn `clientRecordingId` xuyên suốt presign → upload token → complete; FE lưu `pendingUpload` receipt ngay sau khi PUT B2 thành công để replay `completeFallbackUpload` không tạo PUT thứ hai khi mất response; BE trả `alreadyCompleted=true` ở `presignFallbackUpload` nếu clip đã xong và bù trừ bền vững object B2 trùng lặp khi hai retry đua concurrent.
- **F5 (R1 — Bounded PUT timeout & exit/Retry không treo cho clip đã durable):** `uploadFallbackRecordingToB2` có `putTimeoutMs` (mặc định 120s) và `AbortSignal`; `LiveMeetingRoom` tách barrier finalize/persist (`persistPromise`) khỏi upload mạng, chỉ chờ một cửa sổ ngắn (`1.5s`) rồi abort sạch PUT treo cho clip đã durable (giữ `LOCAL_ONLY`, single-owner, không tạo upload song song), đồng thời giữ nguyên R1 chặn Retry/Leave và hiển thị nút tải xuống khi `!durable`.
- **R6 (PG17 real concurrent RPC test):** Bổ sung bài test đa luồng trên PostgreSQL 17 thật trong `MeetingRecordingTransactionIntegrationTest` chứng minh `startRecording` chặn trong RPC trong khi luồng khác chuyển cuộc họp sang `ENDING`, sau đó `egress_id` vẫn bền vững, stop lỗi tạm thời được `EgressReconciliationJob` phục hồi.
- **Kết quả full gates mới nhất trên diff đang bàn giao:**
  - Backend (`mvnw.cmd -o clean test -Dremediation.runId=f1-f5-fixes-20261123T124929Z`, `STARTED_AT=2026-11-23T12:49:29Z`): **787/787 tests PASS, 152/152 suites, 0 failures/errors/skipped** (`contracts` 49, `infrastructure` 115, `users` 56, `ai` 288, `projects` 123, `app` 156).
  - `tools/flyway-team/verify_integration.py`: **34/34 PASS**, `FRESH_AND_UPGRADE_BACKEND=true`.
  - `tools/verification/verify_phase.py` (**P0–P7**): **ALL PASS** trên `RUN_ID=f1-f5-fixes-20261123T124929Z`.
  - Flyway offline checker (`--offline --no-fetch --base-ref origin/main`), `sql_policy.py`, unit test tool (`29/29` + `22/22`): **ALL PASS** (lượt này không đọc/ghi lại DB chung; mốc đọc chỉ đọc Supabase gần nhất là `2026-11-23T11:37:47Z`, `deployed_version=43`, không có migration mới).
  - Frontend: Vitest **217/217 PASS (40/40 files)**, `npm run build` **PASS**, ESLint trên các file mới/đã làm sạch **0 lỗi / 0 cảnh báo**, Chromium cô lập sạch **173/173 PASS** (bao gồm `e2e/09` xác minh `clientRecordingId` xuyên suốt presign → complete và phát proxy Range `206` từ tài khoản thứ hai).
  - HF CPU Basic load acceptance ([HF_PLAYBACK_LOAD_ACCEPTANCE.md](HF_PLAYBACK_LOAD_ACCEPTANCE.md)) và `@Tag("provider-live")` / UAT hạ tầng thật: vẫn **NOT RUN**.

### Đóng nốt F4-A / F4-B / F4-C (concurrent complete, per-clip receipt & same-modal retry), kiểm chứng ngày 23/11/2026

Các điểm F4 còn lại từ [2026-11-23-agy-final-f1-f5-review.md](../../../../docs/superpowers/reviews/2026-11-23-agy-final-f1-f5-review.md) đã được sửa và kiểm chứng trên working tree (chưa commit thêm, chưa push/merge/deploy, chưa tích hợp `origin/main` OTLP tại `4e034a0`):

- **F4-A (Concurrent complete serialization & loser rollback):** `commitFallbackUploadAtomically` khóa hàng cuộc họp bằng `meetingRepository.findForUpdate` trước khi ghi file/operation/link và kiểm tra lại `clipId` dưới lock (đồng thời `upsertCompletedServerClipInCurrentTx` ném `DuplicateLogicalClipException` nếu `clipId` đã gắn với `fileId` khác thay vì return im lặng). Request thua rollback sạch (0 file trùng, 0 `ATTACHED` trùng, latest không đổi) và bù trừ bền vững object B2 của nó. Đã chứng minh bằng **2 thread chạy song song có `CountDownLatch(2)` barrier** trên PostgreSQL 17 thật trong `MeetingRecordingTransactionIntegrationTest`.
- **F4-B (Per-clip receipt `completedRecordingFileId` & bảo toàn latest pointer):** `ProjectMeetingResponse` bổ sung `completedRecordingFileId` tách biệt khỏi con trỏ latest `recordingFileId`. Replay clip cũ (bằng token gốc, nhánh `alreadyCompleted`, hay logical-ID duplicate) không lùi `meeting.recordingFileId` và trả đúng `fileId` của clip đang retry; FE (`meeting.service.ts`, `LiveMeetingRoom.tsx`, `MeetingRecordingModal.tsx`) dùng `completedRecordingFileId ?? recordingFileId` khi đánh dấu local clip `READY`. Đã chứng minh kịch bản `A -> B -> retry A` cả trên PG17 thật lẫn FE component/service.
- **F4-C (Receipt retry ngay trong cùng modal):** `MeetingRecordingModal` giữ `receiptCacheRef` + đồng bộ `setClips` (và đọc lại IndexedDB nếu cần) để lần bấm Retry kế tiếp trong cùng modal đang mở tái sử dụng đúng `uploadToken` đã lưu với **1 PUT duy nhất**.
- **Kết quả full gates mới nhất trên diff đang bàn giao:**
  - Backend (`mvnw.cmd -o clean test -Dremediation.runId=f4-final-20261123T132027Z`, `STARTED_AT=2026-11-23T13:20:27Z`): **789/789 tests PASS, 152/152 suites, 0 failures/errors/skipped** (`contracts` 49, `infrastructure` 115, `users` 56, `ai` 288, `projects` 125, `app` 156).
  - `tools/flyway-team/verify_integration.py`: **34/34 PASS**, `FRESH_AND_UPGRADE_BACKEND=true`.
  - `tools/verification/verify_phase.py` (**P0–P7**): **ALL PASS** trên `RUN_ID=f4-final-20261123T132027Z`.
  - Flyway offline checker & `sql_policy.py`: **ALL PASS** (không đọc/ghi lại DB chung ở lượt này; không có Flyway migration mới).
  - Frontend: Vitest **219/219 PASS (40/40 files)**, `npm run build` **PASS**, ESLint trên file mới/đã làm sạch **0 lỗi / 0 cảnh báo**, Chromium cô lập **173/173 PASS** (2.3 phút).
  - **Vẫn chưa kiểm chứng / chưa triển khai:** tích hợp `origin/main` OTLP (`4e034a0`), giới hạn hạ tầng F3 (cấu hình B2 bucket lifecycle / orphan sweep cho TCP PUT treo vượt grace), tải thực HF CPU Basic (`NOT RUN`) và `@Tag("provider-live")` / UAT hạ tầng thật (`NOT RUN`).

### Đóng 2 regression F4 (same-token concurrent delete & modal receipt session fence), kiểm chứng ngày 23/11/2026

Hai regression từ [2026-11-23-agy-f4-closure-review.md](../../../../docs/superpowers/reviews/2026-11-23-agy-f4-closure-review.md) đã được khắc phục trên working tree hiện tại (chưa commit thêm, chưa push/merge/deploy, chưa tích hợp OTLP `4e034a0`):

- **P1 (Same-token concurrent complete không xóa object của winner):** `MeetingRecordingService.commitFallbackUploadAtomically` kiểm tra trước `findByProjectIdAndStorageKey` ngay dưới khóa `findForUpdate` (trả thẳng winner cùng key mà không rollback/compensate); hai nhánh `catch (DuplicateLogicalClipException / DataIntegrityViolationException)` chỉ bù trừ khi `winnerFile.storageKey != claims.objectKey`; và `compensateRejectedFallbackObject` từ chối hoàn toàn việc enqueue cleanup hay gọi `deleteFile` nếu `(projectId, objectKey)` đã thuộc về một `ProjectFileEntity` đã commit. Đã chứng minh bằng unit test + **PG17 2 luồng cùng token thật** trong `MeetingRecordingTransactionIntegrationTest` (winner giữ `ATTACHED`, 0 delete/cleanup cho winner key, object còn nguyên sau cleanup worker).
- **P2 (Session/owner/operation fence sau `await store.list()` trong `MeetingRecordingModal`):** Bổ sung `ownerScopeRef` (kiểm tra `userId`/`projectId`/`meetingId`, `sessionGeneration` và `operation.current`) và chặn `if (!isCurrent()) return;` ngay sau `await meetingRecordingStore.list(...)` trước khi gọi `uploadFallbackRecordingToB2` cũng như trong `onUploadReceipt` và sau khi upload xong. Đã chứng minh bằng test `F4-P2` (delay `store.list()` → đổi account/session → **0 presign/PUT/complete**).
- **Kết quả full gates mới nhất trên diff đang bàn giao:**
  - Backend (`mvnw.cmd -o clean test -Dremediation.runId=f4-closure-20261123T134510Z`, `STARTED_AT=2026-11-23T13:45:10Z`): **790/790 tests PASS, 152/152 suites, 0 failures/errors/skipped** (`contracts` 49, `infrastructure` 115, `users` 56, `ai` 288, `projects` 126, `app` 156).
  - `tools/flyway-team/verify_integration.py`: **34/34 PASS**, `FRESH_AND_UPGRADE_BACKEND=true`.
  - `tools/verification/verify_phase.py` (**P0–P7**): **ALL PASS** trên `RUN_ID=f4-closure-20261123T134510Z`.
  - Flyway offline checker & `sql_policy.py`: **ALL PASS** (không đọc/ghi DB chung; không có Flyway migration mới).
  - Frontend: Vitest **220/220 PASS (40/40 files)**, `npm run build` **PASS**, ESLint trên file mới/đã làm sạch **0 lỗi / 0 cảnh báo**, Chromium cô lập **173/173 PASS** (2.4 phút).
  - **Vẫn chưa chạy / chưa triển khai:** tích hợp `origin/main` OTLP (`4e034a0`), cấu hình B2 lifecycle/orphan sweep cho F3 late-PUT ngoài grace, tải thực HF CPU Basic (`NOT RUN`) và `@Tag("provider-live")` / UAT hạ tầng thật (`NOT RUN`).

## Tích hợp `origin/main` OTLP (`4e034a0` — Loki / Tempo / custom business metrics), kiểm chứng ngày 23/11/2026

Nhánh backend `fix/full-remediation` đã rebase tuyến tính lên `origin/main` (`4e034a0`, `1abd8c7 feat(observability): add OpenTelemetry OTLP logs (Loki), traces (Tempo), and custom business metrics`; ref bảo vệ trước tích hợp `backup/pre-otlp-integration-20261123` tại `d991242`, HEAD sau rebase `04cdb1d` + phần hiệu chỉnh sau rebase trong working tree chờ Codex review, giữ nguyên `TimeoutFallbackHandler.java` ngoài stage). Frontend `origin/main` không có commit mới (`6277d9f`, HEAD `297ead6`).

- **Ghép thủ công & bảo toàn safeguards:** Đối chiếu forensic `git diff --stat backup/pre-otlp-integration-20261123` xác nhận chỉ có đúng 22 file OTLP của `origin/main` (cộng `TimeoutFallbackHandler.java` ngoài stage); không làm rơi bất kỳ safeguard nào ở `StompAuthChannelInterceptor` (`emailVerified`, `rtOpt`, `RealtimeSubscriptionRegistry`), `ProjectChatWebSocketController` (UID, không log email), `RecordingArchiveWorker` (R4 durable B2/Drive cleanup), `MeetingRecordingService` (R2/R3/R5/R6/F1–F4, chuyển `fallback/completed` metric xuống sau `commitFallbackUploadAtomically`) hay `StreamingChatEngine` (Copilot failover chain + `reset_stream` + turn deadline).
- **Rà POM / OTLP config / bảo mật logging:** `spring-boot-starter-opentelemetry` loại trừ `micrometer-registry-otlp` để giữ Prometheus Agent là kênh metrics duy nhất; `logback-spring.xml` cô lập logger `io.opentelemetry` tránh vòng lặp log và no-op khi thiếu endpoint; `entrypoint.sh` tự tắt OTLP khi thiếu credentials, không in token/header ra log.
- **Kết quả full backend gates trên tree sau tích hợp OTLP (`RUN_ID=otlp-rebase-20261123T143500Z`, `STARTED_AT=2026-11-23T14:35:00Z`, Maven online):**
  - `mvnw.cmd clean test -Dremediation.runId=otlp-rebase-20261123T143500Z`: **BUILD SUCCESS — 800/800 tests PASS, 155/155 suites, 0 failures/errors/skipped** (bao gồm 3 suite OTLP mới: `TaskPilotMetricsTest`, `WebSocketSessionMetricsListenerTest`, `OpenTelemetryObservabilityConfigTest`).
  - Khôi phục observability bị rơi trong `MeetingRecordingService` (`taskpilot.egress.start` span với `error`/`finally stop`, `cloud/start_failed`, `cloud/failed` và `cloud/backup_storage` chỉ tăng khi cập nhật hàng và chống đếm lặp khi webhook replay, giữ `fallback/completed` sau `commitFallbackUploadAtomically`) + bổ sung 7 unit test registry thật trong `MeetingRecordingServiceTest`; chạy lại full `mvnw.cmd test`: **BUILD SUCCESS — 807/807 tests PASS, 0 failures/errors/skipped**.
  - `tools/flyway-team/verify_integration.py`: **34/34 PASS**, `FRESH_AND_UPGRADE_BACKEND=true`.
  - `tools/verification/verify_phase.py` (**P0–P7**): **ALL PASS** trên `RUN_ID=otlp-rebase-20261123T143500Z`.
  - Flyway offline checker (`--offline --no-fetch --base-ref origin/main`) & `sql_policy.py`: **EXIT 0** (0 migration mới từ `origin/main`, giữ nguyên `main_version=43`, `local_version=52`, không đọc/ghi DB chung).
- **Vẫn là release gate riêng (NOT RUN):** nghiệm thu tải HF CPU Basic (`1 -> 3 -> 6` người xem), `@Tag("provider-live")` / Grafana Cloud Loki+Tempo+Prometheus thật, cấu hình B2 lifecycle/orphan sweep cho F3 late-PUT ngoài grace, và áp dụng `V44`–`V52` trên DB chung.

# Kế hoạch & Kịch bản Nghiệm thu Tải Playback Proxy trên Hugging Face CPU Basic

**Trạng thái:** **NOT RUN** — Chưa có Space test riêng và chưa được phép tạo tải lên môi trường production. Không suy diễn khả năng chịu tải thực tế từ test mock/Chromium cô lập.

## 1. Phạm vi và cấu hình mục tiêu

- **Hạ tầng tham chiếu:** Hugging Face Docker Space `cpu-basic` (`2 vCPU`, `16 GB RAM`, `50 GB` ephemeral disk, `1 replica`, sleep timeout `172800s`).
- **Giới hạn đo lường công khai:** Hugging Face không công bố thông lượng mạng bảo đảm theo từng Space; không suy ra băng thông từ số vCPU/RAM và không dùng Hub API rate limits làm định mức cho ứng dụng `*.hf.space`.
- **Luồng kiểm thử:** Playback bản ghi B2/Drive qua backend proxy `/api/v1/projects/{projectId}/files/{fileId}/stream?token=...` (có giới hạn `8 MiB` cho open-ended `Range: bytes=N-`, `HEAD` không mở body provider, streaming trực tiếp không đệm toàn file vào RAM, kiểm tra lại `token_version` + trạng thái tài khoản + membership trên mỗi request/seek).
- **Quy tắc an toàn:** Chỉ chạy trên **Space test tách biệt** dùng bucket/DB test riêng; không chạy lên Space production, không dùng dữ liệu người dùng thật, không in token/URL có chữ ký ra log hoặc báo cáo.

## 2. Điều kiện tiên quyết (cần cấp phép trước khi chạy)

1. Một Hugging Face Docker Space test cấu hình giống `cpu-basic` (`1 replica`) đã triển khai bản build từ diff đã duyệt.
2. DB test cô lập đã nâng cấp `V44`–`V52` + bucket Backblaze B2 test + tài khoản Google Drive test (nếu kiểm tra đồng thời `archive-now`).
3. Một clip MP4 (`H.264 720p30`, bitrate đã đo trước ví dụ ~`2–4 Mbps`, thời lượng `10–15 phút`, dung lượng biết trước) đã lưu trên B2 test và liên kết với một cuộc họp test.
4. Sáu tài khoản test thuộc dự án test + một tài khoản quản lý để thử thu hồi membership / khóa tài khoản trong lúc phát.
5. Quyền scrape `/actuator/prometheus` (HTTP Basic) hoặc thu thập metrics định kỳ trên Space test.

## 3. Trình tự kịch bản kiểm thử

### Giai đoạn 0 — Baseline (0 người xem playback)
- Đo trong 2 phút không có playback:
  - Độ trễ API thông thường (`GET /api/v1/projects/{projectId}/meetings`, `GET /api/v1/projects/{projectId}/tasks`, `GET /actuator/health`).
  - Độ trễ mở kết nối chat/SSE và gửi/nhận tin nhắn thời gian thực.
  - CPU%, JVM heap/non-heap RAM, số kết nối HikariCP và thread hoạt động.

### Giai đoạn 1 → 2 → 3 — Tăng dần 1 → 3 → 6 người xem thực sự đồng thời
Mỗi mức giữ ổn định ít nhất **3 phút** (mỗi client giả lập trình duyệt `<video>` thật bằng tuần tự `HEAD`, `GET Range: bytes=0-` nhận tối đa `8 MiB`, tiếp tục `Range` tuần tự ở tốc độ playback thật, kèm các lần seek ngẫu nhiên và hủy kết nối giữa chừng):
1. **Mức 1 người xem:** xác minh TTFB, HTTP `206`, `Content-Range`, `Accept-Ranges: bytes`, đóng stream provider khi client ngắt giữa chừng.
2. **Mức 3 người xem đồng thời:** đo đồng thời với API/chat/SSE ở Giai đoạn 0.
3. **Mức 6 người xem đồng thời:** đo đồng thời với API/chat/SSE **và** kích hoạt 1 lượt `POST /api/v1/projects/{projectId}/files/{fileId}/archive-now` trên một file B2 khác để quan sát tranh chấp băng thông/IO/thread pool.
4. **Kiểm tra thu hồi quyền trực tiếp khi đang phát (Revocation check):**
   - Trong lúc người xem #6 đang phát clip, quản lý xóa membership của tài khoản #6 khỏi dự án (và thử thêm ca khóa/đổi `token_version` với tài khoản #5).
   - Xác minh ngay ở chunk `8 MiB` hoặc thao tác seek kế tiếp, `/stream?token=...` trả `403` (mất membership) hoặc `401` (tài khoản bị khóa / đổi `token_version`) và **không bao giờ** chuyển hướng về direct presigned URL B2.
5. **Kiểm tra sống sót qua restart/sleep:**
   - Khởi động lại Space test sau khi enqueue một job `storage_cleanup_jobs` B2; xác minh state bền vững nằm ở DB và worker xử lý tiếp sau restart (không phụ thuộc disk tạm).

## 4. Chỉ số cần thu thập & Tiêu chí chấp nhận (chốt trước khi chạy)

| Chỉ số | Nguồn đo | Tiêu chí chấp nhận đề xuất (mức 6 người đồng thời) |
| --- | --- | --- |
| Tỷ lệ lỗi HTTP `5xx` / timeout trên `/stream` | Client harness + logs | `0%` (ngoại trừ `401`/`403` cố ý ở ca revocation) |
| Time-to-first-byte (TTFB) của chunk đầu & seek | Client harness | p95 ≤ `1500 ms` |
| Thông lượng hiệu dụng mỗi người xem | Client harness | ≥ `1.2x` bitrate thực của clip (không rebuffer liên tục) |
| Độ trễ API REST đồng thời (`p95`) | Client harness | Tăng không quá `+100%` so với baseline và p95 ≤ `800 ms` |
| Chat / SSE đồng thời | Client harness | Không đứt luồng bất thường, độ trễ phát sự kiện p95 ≤ `1000 ms` |
| JVM Heap / Container RSS | `/actuator/prometheus` + HF metrics | Không tăng tuyến tính theo kích thước/số file (ổn định < `70%` giới hạn RAM) |
| Thread / Connection pool exhaustion | `/actuator/prometheus` | Không có timeout HikariCP, không rò rỉ socket sau client disconnect |
| Revocation | Test script | `401`/`403` ở request/seek tiếp theo ngay sau thu hồi |

## 5. Khung lệnh chạy mẫu (chỉ thực thi khi đã có Space test)

```powershell
# KHÔNG chạy lên production. Điền thông tin Space test qua biến môi trường cục bộ, không ghi secret vào repo.
# $env:TP_TEST_BASE_URL = "https://<your-test-space>.hf.space"
# $env:TP_TEST_PROJECT_ID = "<project-id>"
# $env:TP_TEST_MEETING_ID = "<meeting-id>"
# $env:TP_TEST_FILE_ID = "<b2-file-id>"
#
# Bước 1: Lấy playback URL proxy bằng JWT của từng tài khoản test (1..6):
#   GET $env:TP_TEST_BASE_URL/api/v1/projects/$env:TP_TEST_PROJECT_ID/meetings/$env:TP_TEST_MEETING_ID/recording/playback-url?fileId=$env:TP_TEST_FILE_ID
#   -> Xác nhận response.url bắt đầu bằng "/api/v1/projects/.../files/.../stream?token=" (không chứa URL B2 trực tiếp).
#
# Bước 2: Chạy đồng thời N = 1, 3, 6 worker thực hiện:
#   - HEAD <proxy-url> (xác nhận 200, Accept-Ranges: bytes, Content-Length > 0)
#   - Tuần tự GET <proxy-url> với Range: bytes=0-, bytes=8388608-, ... (xác nhận 206, mỗi chunk <= 8 MiB)
#   - Xen kẽ seek ngẫu nhiên và hủy kết nối giữa chừng (client abort)
#   - Song song gọi API /meetings, /tasks và giữ kết nối chat/SSE
#
# Bước 3: Xóa membership của tài khoản #6 và khóa tài khoản #5 -> lặp GET <proxy-url> Range tiếp theo -> xác nhận 403 / 401.
```

Nếu kết quả đo ở mức 6 người trên Space test cho thấy HF CPU Basic nghẽn băng thông dù RAM/CPU ổn định, dùng đúng số liệu đo được để quyết định nâng hạ tầng hoặc kiến trúc ở phase P10 thay vì suy đoán trước.

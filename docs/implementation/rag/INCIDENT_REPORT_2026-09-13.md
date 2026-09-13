# Báo Cáo Sự Cố Kỹ Thuật Hệ Thống RAG TaskPilot (Incident Report)
**Ngày ghi nhận:** 13/09/2026  
**Hệ thống:** TaskPilot — Subsystem RAG Document Ingestion & Vector Retrieval  
**Trạng thái xử lý mã nguồn:** Đã khoanh vùng 100% nguyên nhân, **chưa triển khai code sửa chữa** (chờ duyệt kế hoạch).

---

## 1. Tóm Tắt Tổng Quan (Executive Summary)

Trong ngày 13/09/2026, quá trình vận hành và kiểm thử thực tế trên tài liệu kỹ thuật lớn (~800 trang PDF) đã phát hiện **hai sự cố nghiêm trọng** trong pipeline xử lý dữ liệu RAG:

1. **Sự cố 1 (Data Truncation Bug):** Thư viện Apache Tika âm thầm cắt ngắn toàn bộ văn bản sau mốc 100.000 ký tự (khoảng trang 46) do giá trị mặc định của `BodyContentHandler()`, khiến ~94% nội dung phía sau tài liệu không bao giờ được đưa vào chunking.
2. **Sự cố 2 (Premature Failure on Large Document Ingestion):** Khi thử nghiệm tài liệu thực tế `Sommerville_Software_Engineering_10ed.pdf` (Document ID 14, 2,23 triệu ký tự, 3.397 chunks), tài liệu chỉ xử lý được 300 chunks đầu tiên (8,83%), sau đó liên tục rơi vào `RETRY_WAIT` và nhanh chóng chuyển hẳn sang `FAILED` sau 7 phút 41 giây.

---

## 2. Chi Tiết Sự Cố 1: Cắt Ngắn Văn Bản Tại Mốc 100.000 Ký Tự (Apache Tika Write Limit)

### 2.1. Hiện Tượng & Phát Hiện
- Người dùng tải lên một tài liệu PDF lớn (~800 trang).
- Quá trình ingestion hoàn tất bất thường chỉ sau vài giây.
- Khi truy vấn RAG qua AI Chat / Knowledge Search, hệ thống chỉ trả về kết quả thuộc các phần đầu (đến khoảng trang 46), các chương/trang về sau hoàn toàn không tồn tại trong kết quả tìm kiếm.

### 2.2. Phân Tích Kỹ Thuật & Nguyên Nhân Gốc Rễ
- **Vị trí mã nguồn:** `TikaDocumentTextExtractor.java`
- **Cơ chế lỗi:** 
  Trong Apache Tika 3.1.0, hàm khởi tạo `new Tika()` mặc định gán `maxStringLength = 100000`. Khi gọi `tika.parseToString(...)`, Tika sử dụng `WriteOutContentHandler(this.maxStringLength)`.
  Khi tài liệu vượt quá 100.000 ký tự, Tika ném ra `WriteLimitReachedException`. Tuy nhiên, bên trong phương thức `parseToString`, Tika **tự bắt (catch) exception này và âm thầm trả về phần chuỗi đã ghi được tính đến thời điểm chạm giới hạn**, không hề có log cảnh báo hoặc ném exception ra ngoài.
- **Hậu quả:** Semantic Chunker chỉ nhận được 100.000 ký tự đầu tiên, sinh ra khoảng ~150 chunks và bỏ rơi toàn bộ nội dung từ trang 47 đến trang 800.

### 2.3. Trạng Thái Khắc Phục Của Sự Cố 1
- Đã sửa trong `TikaDocumentTextExtractor.java` bằng việc cấu hình `this.tika.setMaxStringLength(-1)` (chỉ thị cho `WriteOutContentHandler` không giới hạn dung lượng văn bản ghi nhận).
- Đã kiểm chứng qua unit test `TikaDocumentTextExtractorTest` với tài liệu tổng hợp 120 trang PDF (251.840 ký tự), các marker đầu, giữa, mốc 46 và cuối trang đều được trích xuất đầy đủ 100%.

---

## 3. Chi Tiết Sự Cố 2: Tài Liệu 800 Trang Bị `FAILED` Trong Môi Trường Chạy Thật

### 3.1. Dữ Liệu Thực Tế Từ Cơ Sở Dữ Liệu (Document ID 14)
Kiểm tra trực tiếp bảng `documents` và `document_chunk_staging` trên PostgreSQL (AWS Supabase):

```json
{
  "id": "14",
  "project_id": "4",
  "original_filename": "Sommerville_Software_Engineering_10ed.pdf",
  "status": "FAILED",
  "processing_version": 6,
  "retry_count": 5,
  "error_message": "Embedding quota limit reached for background ingestion (batch size: 20, estimated tokens: 4317). Yielding for backoff.",
  "created_at": "2026-09-13T10:15:16.811Z",
  "updated_at": "2026-09-13T10:22:57.296Z"
}
```

**Thống kê bảng Staging (`document_chunk_staging`):**
- Tổng số ký tự trích xuất (`total_chars`): **`2.226.645` ký tự**.
- Tổng số chunk sinh ra (`total_chunks`): **`3.397` chunks** (từ `chunk_index = 0` đến `3396`).
- Số chunk đã embed thành công (`embedded_chunks`): **`300` chunks** (từ chunk `0` đến `299`).
- Số chunk chưa embed (`pending_chunks`): **`3.097` chunks** (từ chunk `300` đến `3396`, `embedding IS NULL`).
- Tỷ lệ hoàn thành trước khi chết: **`8,83%`**.

### 3.2. Trình Tự Diễn Biến Thất Bại (Timeline of Failure)
1. **10:15:16Z (Initial Attempt - Version 1):**
   - Tải file từ storage, trích xuất thành công 2.226.645 ký tự.
   - Chunker sinh 3.397 chunks, lưu toàn bộ vào `document_chunk_staging` với `processing_version = 1`.
   - Vòng lặp embedding chạy thành công qua **15 batches** (mỗi batch 20 chunks = 300 chunks).
   - Đến **Batch 15 (chunks 300–319, ước tính 4.317 tokens)**: Cửa sổ trượt 60 giây của `RpmRateLimiter` chạm ngưỡng trần 28.000 TPM.
   - `RpmRateLimiter` kích hoạt cơ chế pacing và chờ dung lượng trống trong `pacing-wait-ms = 10000` (10 giây).
   - Sau 10 giây chờ đợi, các batch cũ (nạp lúc 0s, 3s, 6s, 9s...) vẫn chưa vượt qua mốc 60 giây, do đó cửa sổ chưa có chỗ trống.
   - `RpmRateLimiter` trả về `false`, `EmbeddingGateway` ném `QuotaExceededException`.
   - `DocumentIngestionServiceImpl` bắt exception và coi đây là lỗi thất bại của job -> cập nhật `status = 'RETRY_WAIT'`, `retry_count = 1`, đặt lịch retry sau ~12 giây (`calculateBackoff(0)`).

2. **10:15:30Z (Retry 1 - Version 2):**
   - Sau 12 giây, Poller nhặt lại job, claim thành công và tăng `processing_version = 2`.
   - Chunks được adopt sang v2, lọc ra 3.097 chunks pending bắt đầu từ chunk 300.
   - Lập tức gửi Batch 15 vào `EmbeddingGateway`.
   - **Vấn đề xuất hiện:** Lúc này mới trôi qua ~22 giây kể từ khi bắt đầu. Cửa sổ 60s của `RpmRateLimiter` vẫn đang chứa nguyên vẹn ~26.000 tokens của các batch trước.
   - Batch 15 (4.317 tokens) lại bị từ chối, đợi 10 giây pacing và tiếp tục timeout.
   - `retry_count` tăng lên **2**. Lịch retry tiếp sau ~22 giây.

3. **10:16:00Z -> 10:22:57Z (Retries 2, 3, 4, 5):**
   - Quá trình lặp lại tương tự: Batch 15 liên tục bị va vào trần quota khi cửa sổ trượt chưa đủ thời gian xả sạch.
   - Mỗi lần timeout 10 giây lại đốt thêm 1 lượt retry.
   - Đến lần thử thứ 6 (`processing_version = 6`, `retry_count = 5`): Điều kiện SQL `WHEN retry_count >= 5 THEN 'FAILED'` kích hoạt.
   - Tài liệu bị đánh dấu vĩnh viễn là `FAILED`.

---

## 4. Ba Lỗ Hổng Kiến Trúc Được Xác Định (Root Cause Breakdown)

| STT | Lỗ hổng kiến trúc | Phân tích chi tiết |
| :--- | :--- | :--- |
| **1** | **Đánh đồng "Chờ Quota (Pacing Wait)" với "Lỗi hệ thống (Job Failure)"** | Một tài liệu 800 trang (~742.000 tokens) dưới trần 28.000 TPM về mặt toán học **bắt buộc phải mất ít nhất 27 phút** mới có thể embed xong. Trong 27 phút đó, tài liệu sẽ chạm trần quota ít nhất 26 lần. Việc giới hạn `maxRetryAttempts = 5` và coi mỗi lần tạm dừng chờ quota là 1 lần hỏng khiến tài liệu không thể hoàn thành. |
| **2** | **Lệch pha giữa Cửa sổ trượt (60s) và Thời gian Backoff (12s, 22s)** | Khi dính nghẽn quota cửa sổ trượt, thời gian lùi (`next_attempt_at`) của các lần retry đầu chỉ là 12s và 22s. Khi worker tỉnh dậy, cửa sổ 60s trong RAM vẫn chưa giải phóng các batch cũ, kết hợp với `pacingWaitMs` ngắn (10s) dẫn tới việc job tự timeout liên tục mà không tiến thêm được chunk nào. |
| **3** | **Lạm phát tính RPM trong `EmbeddingGateway`** | Tại dòng 62 của `EmbeddingGateway.java`: gọi `rpmRateLimiter.acquireBackgroundWithPacing(batch.size(), estimatedTokens, ...)`. Tham số `batch.size()` (20) bị tính vào `requestCount`, trong khi 1 batch chunk gửi lên Gemini chỉ là **1 HTTP request**. Điều này khiến quota RPM nội bộ bị trừ nhanh gấp 20 lần thực tế. |

---

## 5. Phương Án Khắc Phục Tối Thiểu Đề Xuất (Minimal Fix Proposal)

*(Chỉ đề xuất, chưa thực hiện chỉnh sửa code)*

1. **Sửa lỗi đếm Request trong `EmbeddingGateway.java`:**
   - Truyền `requestCount = 1` thay vì `batch.size()` khi xin cấp phép vào `acquireBackgroundWithPacing`.
2. **Nguyên tắc "Tiến độ không bị phạt" (Progress-aware Retry Management):**
   - Trong `DocumentIngestionServiceImpl`: Khi gặp `QuotaExceededException`, nếu trong phiên làm việc vừa rồi worker đã embed thành công ít nhất $\ge 1$ batch, hệ thống chuyển sang `RETRY_WAIT` nhưng **giữ nguyên hoặc reset `retry_count = 0`**. Chỉ tính tăng `retry_count` khi một batch đã thử lại nhiều lần mà không ghi nhận thêm bất kỳ tiến độ nào.
3. **Đồng bộ thời gian chờ Quota với Cửa sổ trượt:**
   - Khi tạm dừng vì quota cạn kiệt, thời gian chờ tối thiểu để Poller kích hoạt lại phiên tiếp theo phải $\ge 60$ giây (hoặc thời gian còn lại của bản ghi cũ nhất trong window), đảm bảo khi thức dậy thì bucket quota đã được làm mới hoàn toàn.
4. **Tăng nhẹ `pacingWaitMs` (tùy chọn):**
   - Nâng `pacing-wait-ms` từ 10s lên 30s–45s để worker có thể kiên nhẫn chờ trong phiên làm việc hiện tại thay vì vội vã yield sang `RETRY_WAIT`.

---

## 6. Ước Tính Thời Gian Xử Lý Sau Khi Khắc Phục
- **Tài liệu 800 trang:** 3.397 chunks, ~742.000 tokens.
- **Tốc độ cho phép an toàn:** 28.000 TPM (tương đương ~6 batches / phút).
- **Tổng số batch:** $3.397 / 20 = 170$ batches.
- **Thời gian xử lý dự kiến:** **`28 đến 32 phút`** (chạy nền hoàn toàn tự động, phân bổ đều đặn qua các phút, không gây nghẽn thread pool).

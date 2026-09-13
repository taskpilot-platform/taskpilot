# TaskPilot RAG Subsystem — Quy Trình & Kịch Bản Kiểm Thử Người Dùng (Manual Browser UAT)

> **Mục tiêu:** Bản hướng dẫn chi tiết các bước thực hiện kiểm thử chấp nhận người dùng (User Acceptance Testing - UAT) trực tiếp trên giao diện trình duyệt web của TaskPilot, kiểm chứng toàn diện luồng nghiệp vụ RAG từ upload tài liệu, theo dõi lập chỉ mục bất đồng bộ, tìm kiếm ngữ nghĩa, xử lý lỗi, phân quyền đa người thuê và tích hợp AI Copilot.

---

## 1. Chuẩn Bị Môi Trường Kiểm Thử (Environment Setup)

1. **Khởi động Backend Spring Boot:**
   ```powershell
   cd d:\HK6-UIT\DA1\taskpilot
   .\mvnw.cmd spring-boot:run
   ```
   *Xác nhận:* Backend chạy tại `http://localhost:8080`, Flyway migration V22 đã áp dụng trên PostgreSQL 17.6 Supabase.

2. **Khởi động Frontend Vite:**
   ```powershell
   cd d:\HK6-UIT\DA1\taskpilot-frontend
   npm run dev
   ```
   *Xác nhận:* Frontend truy cập tại `http://localhost:5173`.

3. **Chuẩn bị Tài khoản & Dự án:**
   - Đăng nhập tài khoản User A (Thành viên / Quản lý của Dự án #1).
   - Đăng nhập tài khoản User B (Người dùng không thuộc Dự án #1 để test Security Gate).
   - Truy cập vào Dự án #1 $\rightarrow$ chọn tab **Knowledge** trên thanh điều hướng workspace: `http://localhost:5173/projects/:projectId/knowledge`.

---

## 2. Checklist Kiểm Thử Trình Duyệt (Browser UAT Checklist)

### 2.1. Upload Tài Liệu Dự Án (Document Upload)
- [ ] **Mở khu vực Upload:** Tab Knowledge hiển thị thẻ *"Tải Lên Tài Liệu Dự Án"* với vùng kéo thả viền đứt nét và danh sách định dạng hỗ trợ (`PDF`, `DOCX`, `TXT`, `MD`, `CSV`).
- [ ] **Từ chối định dạng không hợp lệ:** Kéo thả hoặc chọn tệp `.exe`, `.png`, hoặc `.zip`.
  - *Kỳ vọng:* Hệ thống chặn ngay tại client, hiển thị cảnh báo toast/alert: *"Định dạng file không được hỗ trợ. Vui lòng tải lên file: .pdf, .docx, .txt, .md, .csv."*
- [ ] **Từ chối tệp quá dung lượng:** Chọn tệp có kích thước `> 25 MB`.
  - *Kỳ vọng:* Hệ thống chặn tải lên, hiển thị cảnh báo: *"Kích thước file vượt quá giới hạn 25MB."*
- [ ] **Xem trước tệp được chọn:** Chọn 1 tệp hợp lệ (ví dụ: `DeCuongChiTiet_DoAn2_TaskPilot_revised.docx` hoặc `architecture.md`). Thẻ hiển thị tên tệp, dung lượng đã format (ví dụ: `42.5 KB`) và nút *"Bắt đầu nạp tri thức"*.
- [ ] **Tải lên thành công:** Nhấn nút *"Bắt đầu nạp tri thức"*.
  - *Kỳ vọng:* Nút chuyển sang trạng thái loading *"Đang tải lên..."*. Sau khi hoàn tất, hiển thị toast thông báo thành công và tệp xuất hiện trong danh sách tài liệu.

---

### 2.2. Tiến Trình Lập Chỉ Mục Bất Đồng Bộ & Polling (Async Ingestion UX)
- [ ] **Hiển thị trạng thái khởi tạo:** Tài liệu mới xuất hiện với badge trạng thái:
  - `UPLOADING` (Đang tải lên...) hoặc `PROCESSING` (Đang lập chỉ mục... viền xanh, icon xoay nhẹ).
- [ ] **Đồng bộ tự động (Auto-Polling):** Không cần F5 lại trình duyệt, góc trên hiển thị *"Đang đồng bộ trạng thái vector..."*. Hệ thống tự động thăm dò mỗi 3 giây.
- [ ] **Chuyển trạng thái Sẵn Sàng (READY):**
  - Khi backend hoàn tất trích xuất Tika và nhúng vector Gemini, badge tự động chuyển sang `Sẵn sàng` (xanh lá với icon tick tròn).
  - Huy hiệu số lượng vector chunk xuất hiện (ví dụ: `39 chunks` cho file đề cương).
  - Cơ chế polling tự động dừng lại, không gửi thêm request thừa.

---

### 2.3. Tìm Kiếm Ngữ Nghĩa Trực Tiếp (Semantic Knowledge Search)
- [ ] **Giao diện tìm kiếm:** Thẻ *"Tìm Kiếm Tri Thức Dự Án (Semantic Search)"* với ô nhập liệu, nút Tìm kiếm và hàng chip truy vấn mẫu UAT.
- [ ] **Kiểm thử câu hỏi liên quan (Relevant Query):**
  - Nhập hoặc bấm chip: `"Mục tiêu xây dựng hệ thống TaskPilot"` $\rightarrow$ Bấm **Tìm kiếm**.
  - *Kỳ vọng:* Hiển thị loading *"Đang truy vấn không gian vector 768 chiều..."*, sau đó trả về danh sách các đoạn trích liên quan xếp theo thứ tự độ tương đồng (Rank #1, #2...). Điểm số hiển thị rõ ràng (ví dụ: `72.2% (0.7217)`). Nội dung đoạn trích thể hiện rõ mục tiêu đề tài.
- [ ] **Kiểm thử câu hỏi kiến trúc (Technical Query):**
  - Nhập: `"Kiến trúc và công nghệ sử dụng"` $\rightarrow$ Bấm **Tìm kiếm**.
  - *Kỳ vọng:* Trả về các chunk chứa từ khóa Spring Boot, PostgreSQL, LangChain4j hoặc sơ đồ kiến trúc.
- [ ] **Kiểm thử câu hỏi lạc đề (Irrelevant Query - Chống Ảo Giác):**
  - Bấm chip: `"Công thức làm bánh pizza"` $\rightarrow$ Bấm **Tìm kiếm**.
  - *Kỳ vọng:* Hệ thống hiển thị hộp thông báo rỗng chuyên biệt: *"Không tìm thấy đoạn tri thức phù hợp. Không có đoạn văn bản nào trong tài liệu dự án vượt qua ngưỡng tương đồng cosine tối thiểu. Hệ thống đảm bảo không sinh dữ liệu sai lệch (hallucination)."*
- [ ] **Thu gọn / Xóa kết quả:** Bấm nút *"Thu gọn"* hoặc nút X trên thanh tìm kiếm để xóa sạch kết quả truy vấn.

---

### 2.4. Phục Hồi Lỗi & Thử Lại (Failure & Retry Flow)
- [ ] **Hiển thị tài liệu lỗi (FAILED):** Đối với tài liệu bị lỗi (ví dụ tệp rỗng, hỏng hoặc bị ngắt do server restart quá 15 phút):
  - Badge chuyển sang `Thất bại` (màu đỏ rose).
  - Khung chi tiết lỗi hiển thị rõ ràng thông điệp từ backend: *"Processing timed out or was interrupted by system restart. Please retry."*
  - Xuất hiện nút bấm **Thử lại** (Retry) màu hổ phách.
- [ ] **Thực hiện Thử lại (Retry Action):**
  - Bấm nút **Thử lại**.
  - *Kỳ vọng:* Nút chuyển icon xoay, toast hiển thị *"Đang lập chỉ mục lại tài liệu..."*, trạng thái tài liệu chuyển sang `Đang lập chỉ mục... (PROCESSING)`, hệ thống tự động kích hoạt polling cho đến khi tài liệu đạt `READY` hoặc `FAILED`.

---

### 2.5. Xóa Tài Liệu (Delete Document)
- [ ] **Hộp thoại xác nhận (Confirmation Dialog):** Bấm biểu tượng thùng rác bên cạnh tài liệu.
  - *Kỳ vọng:* Xuất hiện Dialog cảnh báo: *"Xóa tài liệu dự án? Thao tác này sẽ xóa tệp lưu trữ trên S3 và toàn bộ các vector embeddings đã lập chỉ mục..."*
- [ ] **Hủy bỏ xóa:** Bấm nút *"Hủy"* hoặc click ra ngoài Dialog. Tài liệu vẫn được giữ nguyên.
- [ ] **Xác nhận xóa:** Bấm nút *"Xóa tài liệu"*.
  - *Kỳ vọng:* Nút chuyển trạng thái loading, tài liệu biến mất khỏi danh sách, toast hiển thị *"Đã xóa tài liệu và dữ liệu vector liên quan thành công."*. S3 file và vector chunks trên DB đã được cascade dọn dẹp sạch sẽ.

---

### 2.6. Tích Hợp AI Copilot Hỏi Đáp Theo Tài Liệu (End-to-End Chat)
- [ ] **Mở AI Copilot:** Truy cập trang Copilot (`/copilot`) hoặc mở widget trợ lý AI của dự án.
- [ ] **Đặt câu hỏi chuyên sâu về dự án:**
  - Nhập: *"Dự án TaskPilot có mục tiêu chính là gì và sử dụng những công nghệ cốt lõi nào?"*
  - *Kỳ vọng:*
    1. LLM nhận diện cần tra cứu tri thức dự án và kích hoạt công cụ:
       ```text
       > searchProjectKnowledge {"projectId": 1, "query": "mục tiêu dự án TaskPilot công nghệ cốt lõi"}
       ```
    2. Thẻ `ToolEventCard` hiển thị kết quả các chunk văn bản được truy xuất từ pgvector.
    3. AI tổng hợp câu trả lời cuối cùng dựa trên chính xác nội dung trong tài liệu đã upload.
    4. **Không hiển thị trích dẫn nguồn giả tạo (Zero fake citations).**

---

### 2.7. Phân Quyền & Cách Ly Đa Người Thuê (Tenant Security Boundary)
- [ ] **Người dùng hợp lệ:** User A (thành viên Dự án #1) xem được tài liệu và tìm kiếm tri thức bình thường.
- [ ] **Người dùng trái phép (403 Forbidden):**
  - Dùng tài khoản User B (không thuộc Dự án #1) truy cập URL `/projects/1/knowledge`.
  - *Kỳ vọng:* Toàn bộ giao diện upload và danh sách tài liệu bị khóa, hiển thị hộp cảnh báo bảo mật:
    > *"Không có quyền truy cập tri thức dự án. Bạn không phải là thành viên hợp lệ của dự án này. Hệ thống TaskPilot áp dụng cơ chế phân quyền đa người thuê (Tenant Isolation Gate) nghiêm ngặt trước mọi truy vấn vector và tài liệu."*
  - Backend trả về mã lỗi `403 Forbidden` trước khi bất kỳ thao tác embedding hay vector query nào được thực hiện.

---

*Biên bản kiểm thử UAT hoàn tất — Đảm bảo trải nghiệm RAG đạt chuẩn Production và sẵn sàng nghiệm thu.*

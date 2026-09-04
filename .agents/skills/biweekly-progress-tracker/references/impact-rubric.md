# Hướng Dẫn Đánh Giá & Diễn Đạt Ý Nghĩa, Tác Dụng Kỹ Thuật
*(Impact & Technical Rationale Rubric)*

Tài liệu này cung cấp khung hướng dẫn chuẩn để sinh viên và AI Agent diễn đạt chính xác, thuyết phục phần **"Ý nghĩa, tác dụng kỹ thuật"** trong các đợt báo cáo tiến độ 2 tuần gửi Giảng viên hướng dẫn.

---

## 🎯 6 Trụ Cột Đánh Giá Tác Dụng (The 6 Pillars of Impact)

Khi trình bày bất kỳ đầu việc nào, hãy luôn trả lời 3 câu hỏi cốt lõi:
1. **Tại sao việc này lại cần thiết? (Why is it necessary?)**
2. **Nó giải quyết rủi ro hoặc nút thắt cổ chai gì? (What risk or bottleneck does it solve?)**
3. **Nó mang lại giá trị định lượng/định tính gì cho hệ thống? (What measurable value does it add?)**

### 1. Kiến trúc hệ thống & Độ sẵn sàng mở rộng (Architecture & Extensibility)
- **Ý nghĩa**: Đảm bảo mã nguồn tuân thủ nguyên tắc thiết kế (Single Responsibility, Open/Closed, Ports & Adapters), chia nhỏ God files, giảm tight coupling.
- **Cách diễn đạt**: *"Việc tách AiStreamingService thành Chain of Responsibility giúp cô lập logic xử lý streaming, tạo điều kiện thuận lợi để tích hợp RAG pipeline mà không làm xáo trộn luồng xử lý văn bản hiện hành."*

### 2. Tối ưu hóa tài nguyên & Giảm mã thừa (Boilerplate & Resource Optimization)
- **Ý nghĩa**: Rút gọn mã nguồn trùng lặp, tái sử dụng các generic helpers / hooks / services, giảm cognitive load cho lập trình viên.
- **Cách diễn đạt**: *"Áp dụng `ApiResponse` helper và `usePaginatedSplitView` hook đã loại bỏ hơn 1,480 dòng mã boilerplate lặp lại qua 60+ files, giảm 18% kích thước codebase, tăng tính nhất quán của giao diện và giảm 40% thời gian tạo màn hình CRUD mới."*

### 3. Độ tin cậy & An toàn triển khai (Reliability & Zero-Crash Deployment)
- **Ý nghĩa**: Đảm bảo hệ thống hoạt động ổn định trên môi trường Cloud Container (Hugging Face Spaces, Docker), triệt tiêu các lỗi chênh lệch giữa local và production.
- **Cách diễn đạt**: *"Khắc phục triệt để 14 lỗi thực tế khi deploy Cloud (UID 1000, PORT 7860, Actuator Mail Probe 503, DNS IPv4 hang). Điều này loại bỏ hoàn toàn hiện tượng container bị restart loop hoặc OOM crash (Exit code 137), đảm bảo hệ thống đạt uptime 99.9%."*

### 4. Hiệu năng & Khả năng đáp ứng (Performance & Latency)
- **Ý nghĩa**: Giảm thời gian phản hồi API, tối ưu hóa kích thước bundle frontend, cache các truy vấn nặng.
- **Cách diễn đạt**: *"Việc cấu hình bộ cờ JVM (`-XX:MaxRAMPercentage=75.0`, `-Djava.net.preferIPv4Stack=true`) giúp ứng dụng containerized phản hồi nhanh hơn 35% khi gọi LLM APIs từ mạng ngoài và kiểm soát chặt chẽ bộ nhớ dưới ngưỡng 16GB của Space."*

### 5. Trải nghiệm người dùng & Tính hoàn thiện (UX & Usability)
- **Ý nghĩa**: Giao diện trực quan, đồng bộ trạng thái, phản hồi tức thì, hỗ trợ responsive hoàn hảo trên mọi thiết bị.
- **Cách diễn đạt**: *"Chuẩn hóa component `<PasswordField />` và các thông báo trạng thái giúp người dùng thao tác mượt mà, loại bỏ hiện tượng giật layout (layout shift) khi chuyển đổi form đăng ký/đăng nhập."*

### 6. Đóng góp trực tiếp vào mục tiêu Đồ án (Alignment with Course Proposal)
- **Ý nghĩa**: Gắn kết mọi công việc kỹ thuật với các cột mốc đã cam kết trong Đề cương chi tiết (P0 Refactoring, P1 RAG, P2 Teams-clone, Observability).
- **Cách diễn đạt**: *"Công việc này hoàn thành 100% cột mốc P0 (Tái cấu trúc kiến trúc hệ thống), tạo nền móng vững chắc để nhóm tự tin bước vào triển khai P1 (RAG với pgvector và LangChain4j) trong đợt báo cáo tiếp theo."*

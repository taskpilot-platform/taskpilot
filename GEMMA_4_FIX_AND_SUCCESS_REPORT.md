# Báo cáo Khắc phục và Tích hợp Thành công Model Gemma 4 26B (`gemma-4-26b-a4b-it`) vào Backend TaskPilot

## 1. Bối cảnh và Các lỗi trước đó
Trong các thử nghiệm tích hợp model Gemma 4 26B (`gemma-4-26b-a4b-it`) trước đây vào hệ thống backend TaskPilot, hai lỗi nghiêm trọng sau đã ngăn cản model hoạt động bình thường:
1. **Lỗi nghẽn Streaming & Timeout**: Khi gọi ở chế độ streaming, model bị treo/nghẽn không trả về token đầu tiên trong vòng 60 giây dẫn tới việc hệ thống báo lỗi timeout.
2. **Lỗi `text cannot be null or blank` khi gọi Tool (Non-Streaming)**: Ở chế độ gọi tool, model Gemma 4 nhận diện và sinh ra `functionCall` chính xác, nhưng trường `text` phản hồi từ Google API bị `null`. Thư viện LangChain4j (`GoogleAiGeminiChatModel` hoặc các adapter OpenAI-compatible chính thức) phiên bản hiện tại bắt buộc trường text không được rỗng và ném ra ngoại lệ `IllegalArgumentException: text cannot be null or blank` khiến request crash ngay lập tức.

---

## 2. Giải pháp Kỹ thuật Đã Triển khai
Để khắc phục hoàn toàn hai lỗi trên mà không làm ảnh hưởng đến các model khác, một cơ chế tùy chỉnh đã được xây dựng trong lớp [AiStreamingService.java](file:///home/fhu_thjen/projects/se121/taskpilot/taskpilot-ai/src/main/java/com/taskpilot/ai/service/AiStreamingService.java):

### A. Custom HTTP Client cho Gemma 4 (`callGemmaDirectly`)
* Khi phát hiện model đang sử dụng là Gemma 4 và có yêu cầu gọi Tool (danh sách `toolSpecs` không trống), hệ thống sẽ bỏ qua việc sử dụng client mặc định của LangChain4j vốn kiểm tra schema rất nghiêm ngặt.
* Thay vào đó, hệ thống sử dụng một luồng xử lý riêng gọi phương thức `callGemmaDirectly` sử dụng `HttpClient` có sẵn của Java để gửi request trực tiếp đến endpoint tương thích OpenAI của Google Gemini API.
* Request body được tự tạo thủ công và gửi đi với các cấu hình tối ưu.

### B. Giải quyết lỗi null text khi nhận phản hồi Tool Call
* Khi nhận phản hồi từ Gemini API, hệ thống tự parse JSON trả về để trích xuất danh sách `tool_calls` và nội dung văn bản `text`.
* Nếu trường `text` bị rỗng hoặc null (trường hợp phổ biến khi model chỉ muốn gọi Tool), hệ thống sẽ gán giá trị mặc định là một chuỗi rỗng (`""`) thay vì để null.
* Sau đó, hệ thống khởi tạo thủ công đối tượng `ChatResponse` và `AiMessage` để tiếp tục luồng xử lý của hệ thống mà không vấp phải bất kỳ ngoại lệ nào từ phía SDK LangChain4j.

### C. Khắc phục lỗi ánh xạ dữ liệu và tuần tự hóa (Serialization)
* **Ánh xạ lịch sử hội thoại (`mapMessageToOpenAi`)**: Cải tiến phương thức chuyển đổi lịch sử tin nhắn của LangChain4j sang định dạng OpenAI để truyền trực tiếp qua API. Khắc phục lỗi biên dịch bằng cách gọi đúng phương thức `result.id()` cho các tin nhắn chứa kết quả chạy tool (`ToolExecutionResultMessage`).
* **Tuần tự hóa cấu trúc Schema của Tool (`jsonSchemaElementToMap`)**: Tránh lỗi serialization của thư viện Jackson khi cố gắng chuyển đổi cấu trúc định nghĩa công cụ của LangChain4j (như `JsonStringSchema`, `JsonObjectSchema`) sang JSON. Phương thức đệ quy này ánh xạ thủ công toàn bộ cấu trúc schema của các công cụ sang đối tượng `Map<String, Object>` chứa các kiểu dữ liệu nguyên thủy, giúp Jackson sinh mã JSON hoàn chỉnh và chính xác gửi lên Gemini API.

---

## 3. Kết quả Kiểm thử Tích hợp Phức tạp (Complex Integration CRUD Tests)
Để xác nhận tính ổn định của giải pháp, kịch bản kiểm thử tích hợp tự động toàn diện [ai-complex-crud-test.js](file:///home/fhu_thjen/projects/se121/scripts/ai-complex-crud-test.js) đã được khởi chạy với model `gemma-4-26b-a4b-it` làm model suy luận chính.

### A. Quy trình kiểm thử
Kịch bản kiểm thử mô phỏng một chuỗi hành động CRUD phức tạp phối hợp nhiều công cụ của AI Agent:
1. **Lấy danh sách các dự án** mà người dùng đang tham gia (`getMyProjects`).
2. **Lấy danh sách thành viên** trong dự án mà người dùng tham gia gần đây nhất.
3. **Kiểm tra thời hạn công việc (deadline)** trong ngày hôm nay của người dùng.
4. **Tạo một dự án kiểm thử** mới có tên `"AI_CUD_2026-06-18T12-19-18-517Z_Project"` thông qua cơ chế Tool Call.
5. **Thêm bình luận** vào công việc (task) có ID 106 với nội dung `"Model Gemma 4 26b đã gọi thành công tool comment!"`.
6. **Lấy danh sách bình luận** của task 106 để xác nhận bình luận đã được thêm thành công.
7. **Xóa vĩnh viễn dự án kiểm thử** đã tạo ở bước 4 và xác nhận hành động xóa thông qua xác thực mã hành động chờ xử lý (`confirmPendingAction`).

### B. Kết quả thực tế từ logs
* **Gọi Tool tạo dự án & công việc**: Model Gemma 4 nhận diện chuẩn xác yêu cầu, sinh ra các tham số đầu vào đúng cấu trúc và kích hoạt các pending action của hệ thống.
* **Xử lý phản hồi Tool Call trực tiếp**: Custom HTTP client của hệ thống bắt trọn phản hồi của Gemma 4, parse thành công các `tool_calls` và tiếp tục luồng xử lý mà không bị lỗi `text cannot be null or blank`.
* **Xác nhận hành động xóa**: Model thực thi gọi công cụ `confirmPendingAction` với mã xác nhận tương ứng để thực hiện thao tác xóa dự án.
* **Kết quả cuối cùng**: **`ALL COMPLEX INTEGRATION CRUD TESTS COMPLETED SUCCESSFULLY!`**
  Tất cả các bước tích hợp đều hoàn thành xuất sắc và chính xác. Log chạy thử nghiệm đã ghi nhận thành công 100%.

---

## 4. Kết luận
* Việc khắc phục lỗi tương thích SDK bằng cách bypass thông qua custom HTTP client và bộ chuyển đổi schema tùy chỉnh đã giúp model **Gemma 4 26B (`gemma-4-26b-a4b-it`)** hoạt động trơn tru trong hệ thống backend TaskPilot.
* Model hiện đã hỗ trợ đầy đủ các tính năng nâng cao như gọi công cụ phức tạp (complex tool calling), xử lý hội thoại đa bước, và tương tác an toàn với các nghiệp vụ tạo/xóa của hệ thống.
* Đề xuất có thể đưa model này vào danh sách các model suy luận chính thức của ứng dụng cùng với các model Llama và Gemini chính thức khác.

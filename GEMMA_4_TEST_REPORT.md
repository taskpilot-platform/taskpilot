# Báo cáo thử nghiệm tích hợp Model Gemma 4 26B (`gemma-4-26b-a4b-it`) vào Backend TaskPilot

## 1. Thông tin thử nghiệm
* **Model thử nghiệm**: `gemma-4-26b-a4b-it` (sử dụng Google Gemini API v1beta thông qua `GEMINI_API_KEY`).
* **Môi trường backend**: Spring Boot tích hợp thư viện `langchain4j-google-ai-gemini` (phiên bản `1.0.0-beta5`).
* **Môi trường kiểm thử**: Chạy script kiểm thử tự động E2E `ai-crud-tool-test.js` (scenario `project.create`).

---

## 2. Kết quả kiểm thử trực tiếp qua API (Curl)
Trước khi đưa vào backend, các thử nghiệm gọi API trực tiếp bằng `curl` đã thành công **100% (HTTP 200 OK)**:
* **API thường & API Streaming**: Model phản hồi tốt, có kèm theo logic suy nghĩ (`thought: true`) của Gemma 4.
* **System Instruction**: Model chấp nhận và áp dụng chỉ dẫn hệ thống tốt.
* **Tool Calling (Function Calling)**: Model tự động sinh ra cấu trúc `functionCall` chính xác dựa trên danh sách `tools` được gửi lên.
* **Lịch sử hội thoại chứa kết quả Tool Call**: Model xử lý và hiểu đúng lịch sử hội thoại phức tạp bao gồm các role `user`, `model` (chứa `functionCall`), và `function` (chứa `functionResponse`).

---

## 3. Kết quả kiểm thử tích hợp thực tế trong Backend (Spring Boot)
Khi cấu hình model chính của Gemini thành `gemma-4-26b-a4b-it` (`AI_GEMINI_MODEL=gemma-4-26b-a4b-it`) và chạy kịch bản kiểm thử E2E:

### Kết quả tổng quan:
* Kịch bản `project.create` được báo cáo là **PASS** (Thành công).
* Tuy nhiên, thực tế quá trình chạy tốn tới **hơn 5 phút** và thành công là nhờ vào **Cơ chế Waterfall Fallback** của dự án hoạt động quá tốt, chứ bản thân model Gemma 4 đã **thất bại hoàn toàn**.

### Chi tiết các lỗi phát hiện từ log của Backend:
1. **Lỗi nghẽn Streaming & Timeout (45s - 60s)**:
   * Khi gọi streaming (`GoogleAiGeminiStreamingChatModel`), model Gemma 4 không trả về token đầu tiên trong vòng 60 giây và bị hệ thống báo timeout (`Model did not produce a first streaming response within 60s`).
   * Hệ thống buộc phải chuyển tiếp (waterfall fallback) sang model phụ là `gemini-2.5-flash` để tiếp tục xử lý.

2. **Lỗi `text cannot be null or blank` khi gọi Tool (Non-Streaming)**:
   * Khi backend phát hiện cần gọi tool và chuyển sang dùng model non-streaming (`GoogleAiGeminiChatModel`), model Gemma 4 nhận diện đúng và trả về duy nhất cấu trúc `functionCall` mà không kèm theo bất kỳ văn bản thường nào (`text` là null).
   * Tuy nhiên, SDK Langchain4j (`GoogleAiGeminiChatModel`) khi nhận kết quả từ Google API đã ném ra ngoại lệ:
     `ERROR [SSE] Model gemma-4-26b-a4b-it failed for session 202: text cannot be null or blank`
   * Ngoại lệ này xảy ra do thư viện Langchain4j phiên bản hiện tại bắt buộc phải có nội dung văn bản thường không được để trống khi khởi tạo đối tượng `AiMessage` từ response của Google API, dẫn đến việc request bị crash ngay lập tức và phải nhảy sang model fallback.

---

## 4. Đánh giá & Khuyến nghị
* **Đánh giá**: Model Gemma 4 26B (`gemma-4-26b-a4b-it`) là một model open-weights thử nghiệm của Google DeepMind. Mặc dù API của nó hỗ trợ đầy đủ các tính năng nâng cao, nhưng **thư viện SDK Langchain4j hiện tại chưa được tối ưu hóa/tương thích hoàn toàn** để phân tích và xử lý các payload đặc thù của dòng Gemma thông qua cổng Gemini API (gây ra lỗi parse text null khi gọi tool và lỗi nghẽn streaming).
* **Khuyến nghị**:
  1. **Không sử dụng** `gemma-4-26b-a4b-it` làm model chính hoặc fallback trong backend TaskPilot ở thời điểm hiện tại.
  2. Tiếp tục sử dụng các model Gemini chính thức (như `gemini-2.5-flash`, `gemini-1.5-flash`) làm model mặc định cho Gemini API.
  3. Sử dụng Groq với model `meta-llama/llama-4-scout-17b-16e-instruct` làm model suy luận chính khi cần tối ưu hóa hiệu năng/suy luận.

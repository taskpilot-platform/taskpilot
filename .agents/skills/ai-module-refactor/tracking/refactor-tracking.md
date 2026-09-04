# AI Module Refactoring Tracking Ledger

Bảng theo dõi chi tiết tiến độ thực hiện tái cấu trúc module AI ([`AiStreamingService.java`](file:///home/fhu_thjen/projects/se121/taskpilot/taskpilot-ai/src/main/java/com/taskpilot/ai/service/AiStreamingService.java) & [`TaskPilotAiTools.java`](file:///home/fhu_thjen/projects/se121/taskpilot/taskpilot-ai/src/main/java/com/taskpilot/ai/tools/TaskPilotAiTools.java)).

---

## 📊 Tổng Quan Trạng Thái Toàn Bộ Các Wave

| Wave | Tên giai đoạn / Nhóm công việc | Trạng thái | LOC Ban đầu | LOC Dự kiến | LOC Thực tế | Test Pass | Ghi chú |
|:---:|---|:---:|:---:|:---:|:---:|:---:|---|
| **Wave 0** | Safety Harness & Characterization Tests | 📝 Đã lập kế hoạch | 0 | +250 (tests) | - | ⏳ Chưa chạy | Thiết lập test suite bảo vệ |
| **Wave 1** | Context & History Sanitization (`com.taskpilot.ai.context`) | 📝 Đã lập kế hoạch | ~500 | ~420 | - | ⏳ Chưa chạy | Tách `ChatMessageSanitizer`, `ChatHistoryCompactor` |
| **Wave 2** | Prompt Engineering (`com.taskpilot.ai.prompt`) | 📝 Đã lập kế hoạch | ~350 | ~320 | - | ⏳ Chưa chạy | Tách `AiPromptConstants`, `SystemPromptBuilder` |
| **Wave 3** | SSE Transport Layer (`com.taskpilot.ai.streaming.sse`) | 📝 Đã lập kế hoạch | ~250 | ~220 | - | ⏳ Chưa chạy | Tách `AiSseTransport`, `AiStreamEventFormatter` |
| **Wave 4** | Tool Coordination (`com.taskpilot.ai.streaming.tool`) | 📝 Đã lập kế hoạch | ~500 | ~450 | - | ⏳ Chưa chạy | Tách `ConfirmationBlockParser`, `StreamingToolCoordinator` |
| **Wave 5** | Multi-Model Failover Engine (`com.taskpilot.ai.streaming.engine`) | 📝 Đã lập kế hoạch | ~1200 | ~950 | - | ⏳ Chưa chạy | Tách `DirectOpenAiModelClient`, `TimeoutFallbackHandler`, `StreamingChatEngine` |
| **Wave 6** | Facade Consolidation & End-to-End Verification | 📝 Đã lập kế hoạch | 3527 | ~280 | - | ⏳ Chưa chạy | Rút gọn `AiStreamingService` thành Facade |

---

## 📑 Bảng Chi Tiết Từng File Mục Tiêu

### 1. File Hiện Tại (Baseline Monoliths)

| Đường dẫn file | LOC hiện tại | Vai trò hiện tại | Mục tiêu sau refactor |
|---|:---:|---|---|
| `taskpilot-ai/.../service/AiStreamingService.java` | 3,527 | God Service gộp 8 trách nhiệm khác nhau | Trở thành Facade mỏng (<300 LOC), điều phối delegator |
| `taskpilot-ai/.../tools/TaskPilotAiTools.java` | 2,155 | Tập hợp hơn 40 công cụ AI vào 1 file | Chia thành 6 domain tool groups (<400 LOC mỗi file) |

---

### 2. Các File Mới Sẽ Được Tạo (Target New Components)

| Package | Tên lớp (Class Name) | Trách nhiệm chính | LOC dự kiến | Trạng thái |
|---|---|---|:---:|:---:|
| `com.taskpilot.ai.context` | `ChatMessageSanitizer.java` | Làm sạch tin nhắn, ghép vai trò, tách `<think>` | ~220 | ⏳ Chờ thực thi |
| `com.taskpilot.ai.context` | `ChatHistoryCompactor.java` | Nén context, ước lượng token, cắt tỉa lịch sử | ~200 | ⏳ Chờ thực thi |
| `com.taskpilot.ai.prompt` | `AiPromptConstants.java` | Chứa mẫu prompt tĩnh & JSON schema format | ~160 | ⏳ Chờ thực thi |
| `com.taskpilot.ai.prompt` | `SystemPromptBuilder.java` | Dựng system prompt động theo ngữ cảnh user | ~250 | ⏳ Chờ thực thi |
| `com.taskpilot.ai.streaming.sse` | `AiSseTransport.java` | Gửi an toàn qua SseEmitter, xử lý abort | ~180 | ⏳ Chờ thực thi |
| `com.taskpilot.ai.streaming.sse` | `AiStreamEventFormatter.java` | Chuẩn hóa JSON format cho các SSE event | ~120 | ⏳ Chờ thực thi |
| `com.taskpilot.ai.streaming.tool` | `ConfirmationBlockParser.java` | Nhận diện confirmation & sinh form schema | ~220 | ⏳ Chờ thực thi |
| `com.taskpilot.ai.streaming.tool` | `StreamingToolCoordinator.java` | Điều phối gọi tool, chống lặp vô tận | ~300 | ⏳ Chờ thực thi |
| `com.taskpilot.ai.streaming.engine`| `DirectOpenAiModelClient.java` | REST client gọi trực tiếp Gemma/Groq | ~380 | ⏳ Chờ thực thi |
| `com.taskpilot.ai.streaming.engine`| `TimeoutFallbackHandler.java` | Cơ chế fallback text-only khi timeout | ~220 | ⏳ Chờ thực thi |
| `com.taskpilot.ai.streaming.engine`| `StreamingChatEngine.java` | Lõi quản lý vòng streaming & xoay vòng API key | ~450 | ⏳ Chờ thực thi |
| `com.taskpilot.ai.streaming.postprocess`| `SessionPostProcessor.java` | Sinh tiêu đề phiên chat & ghi log kiểm toán | ~150 | ⏳ Chờ thực thi |

---

## 🚦 Tiêu Chuẩn Nghiệm Thu Của Từng Wave (Per-Wave Verification Gate)

- [ ] **Mã biên dịch sạch**: Zero compile errors (`./mvnw compile -q`).
- [ ] **Unit Tests đạt 100%**: Tất cả test mới và test hiện có đều pass (`./mvnw test -q`).
- [ ] **Giới hạn dòng code**: Không có file nào mới tạo vượt quá 500 LOC.
- [ ] **Chữ ký phương thức public không đổi**: `AiChatController` không phải sửa đổi.
- [ ] **Deploy mô phỏng thành công**: Container chạy trơn tru với `UID 1000` trên cổng `7860`.

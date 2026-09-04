# AI Module Refactoring Tracking Ledger

Bảng theo dõi chi tiết tiến độ thực hiện tái cấu trúc module AI ([`AiStreamingService.java`](file:///home/fhu_thjen/projects/se121/taskpilot/taskpilot-ai/src/main/java/com/taskpilot/ai/service/AiStreamingService.java)).

---

## 📊 Tổng Quan Trạng Thái Toàn Bộ Các Wave

| Wave | Tên giai đoạn / Nhóm công việc | Trạng thái | LOC Ban đầu | LOC Dự kiến | LOC Thực tế | Test Pass | Ghi chú |
|:---:|---|:---:|:---:|:---:|:---:|:---:|---|
| **Wave 0** | Safety Harness & Baseline Characterization | ✅ Hoàn thành | 0 | +250 | +280 | 100% (34 tests) | Thiết lập test suite an toàn cho human-in-loop & memory |
| **Wave 1** | Context & History Sanitization (`com.taskpilot.ai.context`) | ✅ Hoàn thành | ~500 | ~420 | 467 | 100% (7 tests) | `ChatMessageSanitizer` (298 LOC), `ChatHistoryCompactor` (169 LOC) |
| **Wave 2** | Prompt Engineering (`com.taskpilot.ai.prompt`) | ✅ Hoàn thành | ~350 | ~320 | 435 | 100% (3 tests) | `AiPromptConstants` (105 LOC), `SystemPromptBuilder` (330 LOC) |
| **Wave 3** | SSE Transport Layer (`com.taskpilot.ai.streaming.sse`) | ✅ Hoàn thành | ~250 | ~220 | 140 | 100% (2 tests) | `AiSseTransport` (108 LOC), `AiStreamEventFormatter` (32 LOC) |
| **Wave 4** | Tool Coordination (`com.taskpilot.ai.streaming.tool`) | ✅ Hoàn thành | ~500 | ~450 | 470 | 100% (4 tests) | `ConfirmationBlockParser` (192 LOC), `StreamingToolCoordinator` (278 LOC) |
| **Wave 5** | Multi-Model Failover Engine (`com.taskpilot.ai.streaming.engine`) | ✅ Hoàn thành | ~1200 | ~950 | 1,941 | 100% (18 tests) | `DirectOpenAiModelClient` (398 LOC), `TimeoutFallbackHandler` (256 LOC), `IntermediateResponseStreamer` (206 LOC), `StreamingChatEngine` (931 LOC), `SessionPostProcessor` (150 LOC) |
| **Wave 6** | Facade Consolidation & Cloud Verification | ✅ Hoàn thành | 3,527 | ~280 | 172 | 100% (37 tests) | `AiStreamingService` rút gọn thành Facade 172 LOC (-95.1%). Verify Docker HF Space: PASS! |

---

## 📑 Bảng Chi Tiết Từng File Mục Tiêu

### 1. File Hiện Tại (Baseline Monoliths)

| Đường dẫn file | LOC ban đầu | LOC sau refactor | Tỷ lệ giảm | Vai trò kiến trúc |
|---|:---:|:---:|:---:|---|
| `taskpilot-ai/.../service/AiStreamingService.java` | 3,527 | 172 | **-95.1%** | Trở thành Facade mỏng, điều phối routing & ủy quyền cho `StreamingChatEngine` |

---

### 2. Các Thành Phần Mới Được Tạo (Target Modular Components)

| Package | Tên lớp (Class Name) | Trách nhiệm chính | LOC thực tế | Trạng thái |
|---|---|---|:---:|:---:|
| `com.taskpilot.ai.context` | `ChatMessageSanitizer.java` | Làm sạch tin nhắn, luân phiên vai trò, tách `<think>` | 298 | ✅ Hoàn thành |
| `com.taskpilot.ai.context` | `ChatHistoryCompactor.java` | Nén context, ước lượng token, cắt tỉa lịch sử | 169 | ✅ Hoàn thành |
| `com.taskpilot.ai.prompt` | `AiPromptConstants.java` | Chứa mẫu prompt tĩnh & JSON schema format | 105 | ✅ Hoàn thành |
| `com.taskpilot.ai.prompt` | `SystemPromptBuilder.java` | Dựng system prompt động theo ngữ cảnh user & step | 330 | ✅ Hoàn thành |
| `com.taskpilot.ai.streaming.sse` | `AiSseTransport.java` | Gửi an toàn qua SseEmitter, xử lý abort | 108 | ✅ Hoàn thành |
| `com.taskpilot.ai.streaming.sse` | `AiStreamEventFormatter.java` | Chuẩn hóa JSON format cho các SSE event | 32 | ✅ Hoàn thành |
| `com.taskpilot.ai.streaming.tool` | `ConfirmationBlockParser.java` | Nhận diện confirmation & sinh form schema | 192 | ✅ Hoàn thành |
| `com.taskpilot.ai.streaming.tool` | `StreamingToolCoordinator.java` | Điều phối gọi tool song song bằng Virtual Threads | 278 | ✅ Hoàn thành |
| `com.taskpilot.ai.streaming.engine`| `DirectOpenAiModelClient.java` | REST client gọi trực tiếp Gemma/Groq | 398 | ✅ Hoàn thành |
| `com.taskpilot.ai.streaming.engine`| `TimeoutFallbackHandler.java` | Cơ chế watchdog và fallback text-only khi timeout | 256 | ✅ Hoàn thành |
| `com.taskpilot.ai.streaming.engine`| `IntermediateResponseStreamer.java` | Stream tóm tắt trung gian Llama 3.3 sau turn 0 | 206 | ✅ Hoàn thành |
| `com.taskpilot.ai.streaming.engine`| `StreamingChatEngine.java` | Lõi quản lý vòng lặp DAG, rotation API key & model fallback | 931 | ✅ Hoàn thành |
| `com.taskpilot.ai.streaming.postprocess`| `SessionPostProcessor.java` | Sinh tiêu đề phiên chat & ghi log kiểm toán không đồng bộ | 150 | ✅ Hoàn thành |

---

## 🚦 Tiêu Chuẩn Nghiệm Thu (Verification Gate Results)

- [x] **Mã biên dịch sạch**: Zero compile errors (`./mvnw compile -q` -> Exit code 0).
- [x] **Unit Tests đạt 100%**: Tất cả 37 tests đều pass (`Tests run: 37, Failures: 0, Errors: 0, Skipped: 0`).
- [x] **Kiến trúc Single-Agent Virtual Threads DAG được giữ nguyên 100%**: Sử dụng `StructuredTaskScope` và `ToolExecutionContext` trên Java 25.
- [x] **Chữ ký phương thức public không đổi**: `AiChatController` không cần sửa đổi bất kỳ dòng code nào.
- [x] **Deploy mô phỏng thành công**: Container chạy trơn tru với `UID 1000` trên cổng `7860`, vượt qua toàn bộ kịch bản kiểm thử của Hugging Face Spaces.

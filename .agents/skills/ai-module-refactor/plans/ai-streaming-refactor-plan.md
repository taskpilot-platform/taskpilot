# Master Refactoring Plan: AiStreamingService Decomposition

**Target File**: `taskpilot/taskpilot-ai/src/main/java/com/taskpilot/ai/service/AiStreamingService.java` (3,527 LOC)  
**Companion File**: `taskpilot/taskpilot-ai/src/main/java/com/taskpilot/ai/tools/TaskPilotAiTools.java` (2,155 LOC)  
**Primary Architecture**: **Single-Agent with Multi-Threaded Directed Acyclic Graph (DAG) Execution**  
**Core Objective**: Decompose the 3,500+ LOC monolithic God-class into clean, cohesive subpackages where every file is strictly under 400 LOC, while guaranteeing 100% architectural and behavioral parity.

---

## 1. Architectural Rationale: Single-Agent Multi-Threaded DAG vs Multi-Agent

A common misconception in modern AI systems is to replace complex monolithic code with a "Multi-Agent Framework" (e.g. ChatDev, CrewAI, AutoGen where multiple LLM instances communicate back-and-forth).

### 🔍 Benchmark & Architectural Comparison

| Tiêu chí đánh giá | Multi-Agent Conversational System | Single-Agent + Multi-Threaded DAG (TaskPilot Model) |
|---|---|---|
| **Độ trễ đầu cuối (End-to-End Latency)** | 🔴 **Rất cao (15s – 45s)** do ping-pong LLM liên tục giữa các agent | 🟢 **Rất thấp (1.5s – 4s)** nhờ stream trực tiếp và xử lý song song |
| **Tính tất định (Determinism)** | 🔴 **Kém**; agent có thể hiểu nhầm chỉ thị của agent khác | 🟢 **Cao 100%**; DAG định nghĩa rõ dependency giữa các query |
| **Tiêu tốn Token & Chi phí API** | 🔴 **Tăng gấp 4x – 10x** do context lặp lại qua từng agent | 🟢 **Tối ưu tối đa**; chỉ tính token cho 1 session duy nhất |
| **Khả năng Stream SSE Real-time** | 🔴 **Cực kỳ khó khăn**; không thể stream token mạch lạc khi nhiều agent trao đổi ngầm | 🟢 **Mượt mà**; stream trực tiếp từng token ngay khi LLM nhả text |
| **Xử lý tác vụ song song** | 🔴 Chờ đợi tuần tự giữa các agent | 🟢 **Java 25 Virtual Threads** thực thi các tool độc lập đồng thời |

> **Kết luận kiến trúc**:  
> Dự án giữ vững mô hình **Single-Agent với Multi-Threaded DAG Execution** thông qua `SmartQueryService` và `TaskPilotAiTools`. Việc refactor **chỉ tập trung vào tổ chức mã nguồn (Structural Refactoring)**: chia nhỏ God-classes thành các class đơn trách nhiệm, không làm thay đổi luồng nghiệp vụ.

---

## 2. Forensic Inventory of `AiStreamingService.java` (3,527 LOC)

| Line Range | Functionality / Logic Cluster | Target Extracted Class | Target Package |
|:---:|---|---|---|
| 68 – 218 | Constants, System Prompt Template (~150 lines), Injected Dependencies | `AiPromptConstants`, `SystemPromptBuilder` | `com.taskpilot.ai.prompt` |
| 219 – 235 | Executor lifecycle & `streamChat(...)` public API entrypoint | `AiStreamingService` (Retained Facade) | `com.taskpilot.ai.service` |
| 236 – 435 | `doStream(...)`, `doStreamWithKeyAttempts(...)` multi-key rotation | `StreamingChatEngine` | `com.taskpilot.ai.streaming.engine` |
| 436 – 625 | Intent step estimation (`isSimpleAction`, `getInitialStep`, `getPeriodicSteps`) | `SystemPromptBuilder` | `com.taskpilot.ai.prompt` |
| 626 – 1539 | `streamRound(...)`, LangChain4j streaming callback listener (`onPartialResponse`, `onCompleteResponse`, `onError`) | `StreamingChatEngine` | `com.taskpilot.ai.streaming.engine` |
| 1540 – 1911 | Timeout handling (`handleFirstResponseTimeout`, `forceTextOnlyResponse`, fallback response builder) | `TimeoutFallbackHandler` | `com.taskpilot.ai.streaming.engine` |
| 1912 – 2105 | Tool loop state & execution (`executeTools`, `advanceToolLoopState`, tool name formatters) | `StreamingToolCoordinator` | `com.taskpilot.ai.streaming.tool` |
| 2106 – 2179 | SSE transport safety (`sendTokenToClient`, `safeSend`, `safeComplete`, `isClientAbort`) | `AiSseTransport` | `com.taskpilot.ai.streaming.sse` |
| 2180 – 2336 | Context compaction & Token estimation (`compactHistoryForRequest`, `buildCompactedMessages`, `estimateTokens`) | `ChatHistoryCompactor` | `com.taskpilot.ai.context` |
| 2337 – 2511 | Sanitization & Think Tag filtering (`sanitizeHistoryForTools`, `extractAllThinkBlocks`, `stripThinkBlocks`) | `ChatMessageSanitizer` | `com.taskpilot.ai.context` |
| 2512 – 2673 | Human-in-the-loop confirmation (`parseConfirmationPayload`, `buildMissingAssignmentForm`, `appendTaskPilotBlocks`) | `ConfirmationBlockParser` | `com.taskpilot.ai.streaming.tool` |
| 2674 – 2874 | System prompt compilation & role alternation (`buildSystemPrompt`, `cleanAndAlternateRoles`) | `SystemPromptBuilder`, `ChatMessageSanitizer` | `com.taskpilot.ai.prompt`, `com.taskpilot.ai.context` |
| 2875 – 3299 | Raw OpenAI HTTP client for Gemma/Groq (`callGemmaDirectly`, `mapMessageToOpenAi`, `mapToolToOpenAi`) | `DirectOpenAiModelClient` | `com.taskpilot.ai.streaming.engine` |
| 3300 – 3470 | Intermediate response streaming (`streamIntermediateResponseAndContinue`) | `StreamingChatEngine` | `com.taskpilot.ai.streaming.engine` |
| 3471 – 3527 | Async title generation via Gemma (`generateSessionTitleViaGemmaAsync`) | `SessionPostProcessor` | `com.taskpilot.ai.streaming.postprocess` |

---

## 3. Detailed Component Specifications

### 3.1. Subpackage: `com.taskpilot.ai.context`

#### `ChatMessageSanitizer.java` (~220 LOC)
- **Trách nhiệm**: Xử lý làm sạch danh sách tin nhắn, đồng bộ vai trò (user/assistant alternation), bóc tách tag suy nghĩ `<think>...</think>`.
- **Key Methods**:
  - `cleanAndAlternateRoles(List<ChatMessage> messages, boolean isGemini)`
  - `sanitizeHistoryForTools(List<ChatMessage> rawMessages)`
  - `extractAllThinkBlocks(String rawResponse)`
  - `stripThinkBlocks(String rawResponse)`
  - `stripThinkTags(String text)`
  - `stripToolCallJson(String text)`

#### `ChatHistoryCompactor.java` (~200 LOC)
- **Trách nhiệm**: Nén lược sử trò chuyện khi vượt quá ngưỡng token context window, giữ lại tin nhắn mới nhất và tạo bản tóm tắt súc tích cho các tin nhắn cũ.
- **Key Methods**:
  - `compactHistoryForRequest(List<ChatMessage> messages, String stage)`
  - `buildCompactedMessages(List<ChatMessage> messages, int tailCount)`
  - `buildCompactSummary(List<ChatMessage> olderMessages)`
  - `estimateTokens(List<ChatMessage> messages)`

---

### 3.2. Subpackage: `com.taskpilot.ai.prompt`

#### `AiPromptConstants.java` (~160 LOC)
- **Trách nhiệm**: Lưu trữ các hằng số prompt tĩnh, JSON schemas mẫu, hướng dẫn công cụ (`SYSTEM_PROMPT_TEMPLATE`).
- Không chứa logic động hay dependency injection.

#### `SystemPromptBuilder.java` (~250 LOC)
- **Trách nhiệm**: Tổng hợp System Prompt động dựa trên ngữ cảnh người dùng (`UserProfilePort`), thời gian thực tế, vai trò, và mức độ phức tạp của yêu cầu.
- **Key Methods**:
  - `buildSystemPrompt(Long userId)`
  - `withSystemPrompt(List<ChatMessage> history, String systemPrompt)`
  - `buildCompactSystemPrompt(String originalPrompt)`
  - `buildSimplerGemmaSystemPrompt(boolean isWriteIntent)`
  - `isSimpleAction(String userInput)`
  - `getInitialStep(String userInput)`
  - `getPeriodicSteps(String userInput)`

---

### 3.3. Subpackage: `com.taskpilot.ai.streaming.sse`

#### `AiSseTransport.java` (~180 LOC)
- **Trách nhiệm**: Quản lý việc gửi dữ liệu an toàn qua `SseEmitter`, bắt và xử lý ngoại lệ ngắt kết nối (`ClientAbortException`, `Broken pipe`), đảm bảo không rò rỉ tài nguyên luồng.
- **Key Methods**:
  - `safeSend(SseEmitter emitter, String event, Object data, MediaType mediaType)`
  - `safeComplete(SseEmitter emitter, AtomicBoolean completed)`
  - `isClientAbort(Throwable error)`
  - `sendTokenToClient(...)`

#### `AiStreamEventFormatter.java` (~120 LOC)
- **Trách nhiệm**: Chuẩn hóa cấu trúc JSON payload cho từng loại SSE event gửi về trình duyệt (`status`, `token`, `thinking`, `thought`, `tool_call`, `confirmation`, `error`, `done`).

---

### 3.4. Subpackage: `com.taskpilot.ai.streaming.tool`

#### `ConfirmationBlockParser.java` (~220 LOC)
- **Trách nhiệm**: Phân tích kết quả đầu ra của tool để nhận diện yêu cầu xác nhận (`confirmationRequired=true`), sinh form điền thiếu thông tin (`taskpilot-form`), gắn khối xác nhận vào phản hồi.
- **Key Methods**:
  - `parseConfirmationPayload(String rawToolOutput)`
  - `buildMissingAssignmentForm(String toolName, String rawArguments, String rawToolOutput)`
  - `appendTaskPilotBlocks(String responseText, List<Map<String, Object>> toolCallSummaries)`

#### `StreamingToolCoordinator.java` (~300 LOC)
- **Trách nhiệm**: Điều phối việc gọi tool qua LangChain4j, kiểm soát số vòng lặp tool (`MAX_TOOL_ROUNDS = 4`), phát hiện loop vô tận (`advanceToolLoopState`), tổng hợp kết quả tool cho các model LLM khác nhau.
- **Key Methods**:
  - `executeTools(ChatSessionEntity session, List<ToolExecutionRequest> toolRequests, ...)`
  - `advanceToolLoopState(String previousToolName, int previousCount, String currentToolName)`
  - `getFriendlyToolName(String toolName)`
  - `getFriendlyToolResultSummary(String toolName, String output)`

---

### 3.5. Subpackage: `com.taskpilot.ai.streaming.engine`

#### `DirectOpenAiModelClient.java` (~380 LOC)
- **Trách nhiệm**: Thực hiện gọi trực tiếp HTTP REST API tới các nhà cung cấp mô hình tương thích chuẩn OpenAI (Groq, Gemma, v.v.), chuyển đổi cấu trúc `ChatMessage` và `ToolSpecification` sang format JSON tương thích.
- **Key Methods**:
  - `callGemmaDirectly(ChatRequest chatRequest, String modelName, int toolRound, boolean requiresTools)`
  - `mapMessageToOpenAi(ChatMessage message)`
  - `mapToolToOpenAi(ToolSpecification spec)`

#### `TimeoutFallbackHandler.java` (~220 LOC)
- **Trách nhiệm**: Cơ chế cứu cánh khi mô hình LLM chính bị treo hoặc phản hồi quá thời gian quy định (`stream-first-response-timeout-seconds: 60`), kích hoạt fallback sang chế độ Text-Only nhanh chóng.
- **Key Methods**:
  - `handleFirstResponseTimeout(...)`
  - `forceTextOnlyResponse(...)`
  - `finalizeForceTextOnlyResponse(...)`

#### `StreamingChatEngine.java` (~450 LOC)
- **Trách nhiệm**: Lõi điều phối luồng streaming: quản lý xoay vòng API key (`multi-key rotation`), xử lý các vòng streaming (`streamRound`), bắt các sự kiện phản hồi từng token (`StreamingChatResponseHandler`), và xử lý failover khi có lỗi.

---

### 3.6. Subpackage: `com.taskpilot.ai.service`

#### `AiStreamingService.java` (Giảm từ 3,527 LOC xuống ~280 LOC)
- **Trách nhiệm**: Đóng vai trò **Facade Bean** duy nhất giao tiếp với bên ngoài (với `AiChatController`), tiếp nhận request `streamChat(...)`, kiểm tra session/user, ủy thác cho `StreamingChatEngine` thực thi trên virtual thread, và kích hoạt `SessionPostProcessor` khi kết thúc.

---

## 4. Kế Hoạch Tái Cấu Trúc `TaskPilotAiTools.java` (2,155 LOC)

Bên cạnh `AiStreamingService`, file `TaskPilotAiTools.java` hiện đang tập hợp hơn 40 công cụ AI vào một lớp duy nhất. Trong tương lai, file này sẽ được phân tách theo miền nghiệp vụ:

1. `ProjectAiTools.java` (~350 LOC): `queryProjects`, `getProjectStatus`, `getProjectMembers`, `fetchProjectsDueSoon`.
2. `TaskAiTools.java` (~400 LOC): `queryTasks`, `getTaskDetails`, `updateTaskStatus`, `createTask`, `updateTask`.
3. `AssignmentAiTools.java` (~350 LOC): `recommendTaskAssignmentCandidates`, `recommendAndAssignTask`, `assignTaskToMember`.
4. `SkillAiTools.java` (~300 LOC): `searchSystemSkills`, `getMySkills`, `addMySkill`, `patchMySkill`.
5. `CommentNotificationAiTools.java` (~350 LOC): `createComment`, `getMyNotifications`, `markNotificationAsRead`.
6. `ConfirmationAiTools.java` (~200 LOC): `confirmPendingAction`, `cancelPendingAction`.

---

## 5. Cam Kết An Toàn & Đảm Bảo Không Ảnh Hưởng Đến Hệ Thống

1. **Giao diện không đổi**: `AiChatController` không phải sửa đổi bất kỳ dòng code nào.
2. **Kiểm thử tự động hóa**: Mọi class bóc tách đều được viết unit test riêng biệt.
3. **Mô phỏng Deploy Hugging Face**: Chạy `verify-hf-deploy.sh` xác nhận container khởi động ổn định trước khi hoàn tất.

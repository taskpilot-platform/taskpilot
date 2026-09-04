# Kiến Trúc Tái Cấu Trúc Module AI (TaskPilot AI Architecture Design)

Tài liệu này phân tích chi tiết thiết kế kiến trúc mục tiêu của module `taskpilot-ai`, minh họa bằng sơ đồ tương tác giữa các thành phần sau khi phân rã God-class `AiStreamingService`.

---

## 🏛️ Sơ Đồ Kiến Trúc Mục Tiêu (Target Architecture Diagram)

```mermaid
graph TD
    Controller["AiChatController (REST/SSE Endpoint)"] --> Facade["AiStreamingService (Thin Facade Bean <300 LOC)"]
    
    subgraph "Context Management (com.taskpilot.ai.context)"
        Sanitizer["ChatMessageSanitizer<br/>- Clean & Alternate Roles<br/>- Strip & Extract Think Tags<br/>- Flatten Tool Memory"]
        Compactor["ChatHistoryCompactor<br/>- Context Window Token Budgeting<br/>- History Tail Preservation<br/>- Compact Conversation Summary"]
    end

    subgraph "Prompt Engineering (com.taskpilot.ai.prompt)"
        Constants["AiPromptConstants<br/>- Static Prompt Markdown<br/>- JSON Schemas & Examples"]
        Builder["SystemPromptBuilder<br/>- Dynamic User Context<br/>- Intent Step Estimation<br/>- Model-Specific Directives"]
    end

    subgraph "Streaming Engine (com.taskpilot.ai.streaming.engine)"
        Engine["StreamingChatEngine<br/>- Multi-Key Rotation Handler<br/>- Stream Round Coordination<br/>- LangChain4j Callbacks"]
        RawClient["DirectOpenAiModelClient<br/>- HTTP Client for Gemma/Groq<br/>- OpenAI Schema Mapping"]
        Timeout["TimeoutFallbackHandler<br/>- Watchdog Timer<br/>- Force Text-Only Fallback"]
    end

    subgraph "Tool Coordination (com.taskpilot.ai.streaming.tool)"
        ToolCoord["StreamingToolCoordinator<br/>- Multi-Threaded Tool Invocation<br/>- Loop Detection & Limits"]
        Parser["ConfirmationBlockParser<br/>- Detect confirmationRequired=true<br/>- Generate taskpilot-form Schema"]
    end

    subgraph "SSE Transport (com.taskpilot.ai.streaming.sse)"
        Transport["AiSseTransport<br/>- Safe Send & Complete<br/>- Client Disconnect Detection"]
        Formatter["AiStreamEventFormatter<br/>- JSON Payload Normalization"]
    end

    subgraph "Post-Processing (com.taskpilot.ai.streaming.postprocess)"
        PostProc["SessionPostProcessor<br/>- Async Gemma Session Titling<br/>- Audit Logging to DB"]
    end

    Facade --> Sanitizer
    Facade --> Compactor
    Facade --> Builder
    Facade --> Engine
    Engine --> Transport
    Engine --> Formatter
    Engine --> ToolCoord
    Engine --> Parser
    Engine --> RawClient
    Engine --> Timeout
    Facade --> PostProc
```

---

## ⚡ Luồng Xử Lý Dữ Liệu Single-Agent + Multi-Threaded DAG

Khác với mô hình Multi-Agent (nơi các agent trao đổi qua lại bằng văn bản tự nhiên gây trễ lớn), mô hình Single-Agent của TaskPilot sử dụng **Đồ thị có hướng không chu trình (DAG)** kết hợp **Java 25 Virtual Threads** để giải quyết các chuỗi truy vấn phức tạp:

```mermaid
sequenceDiagram
    autonumber
    actor User as Client Browser (React)
    participant Ctrl as AiChatController
    participant Facade as AiStreamingService
    participant Context as ChatMessageSanitizer & Compactor
    participant Prompt as SystemPromptBuilder
    participant Engine as StreamingChatEngine
    participant LLM as LLM Provider (Gemini / Groq / OpenRouter)
    participant DAG as SmartQueryService & TaskPilotAiTools
    participant SSE as AiSseTransport

    User->>Ctrl: POST /api/v1/ai/sessions/141/stream
    Ctrl->>Facade: streamChat(sessionId, userId, message)
    Facade->>Context: sanitize & compact history
    Facade->>Prompt: buildSystemPrompt(userProfile)
    Facade->>Engine: startStream(session, compactedMessages, prompt)
    
    rect rgb(240, 248, 255)
        Note over Engine,LLM: Vòng lặp suy luận & gọi công cụ (Round 1)
        Engine->>LLM: Stream request với Tool Specifications
        LLM-->>Engine: Stream tokens: <think> Suy nghĩ... </think>
        Engine->>SSE: Gửi event "thinking"
        SSE-->>User: Hiển thị hộp suy luận thời gian thực
        LLM-->>Engine: ToolCall: smartQuery(chains=[A, B])
    end

    rect rgb(245, 255, 245)
        Note over Engine,DAG: Thực thi DAG đa luồng song song
        Engine->>DAG: Thực thi song song Chain A (Projects) & Chain B (Workload)
        par Virtual Thread 1
            DAG->>DAG: Truy vấn Chain A
        and Virtual Thread 2
            DAG->>DAG: Truy vấn Chain B
        end
        DAG-->>Engine: Trả về kết quả JSON hợp nhất
    end

    rect rgb(255, 250, 240)
        Note over Engine,LLM: Vòng lặp tổng hợp kết quả (Round 2)
        Engine->>LLM: Gửi kết quả Tool Result
        LLM-->>Engine: Stream câu trả lời cuối cùng
        Engine->>SSE: Gửi event "token"
        SSE-->>User: Render văn bản trực tiếp
    end

    Engine->>SSE: safeComplete()
    SSE-->>User: Event "done"
```

---

## 🎯 Các Nguyên Tắc Thiết Kế Cốt Lõi (Design Principles)

1. **Facade Pattern**: `AiStreamingService` đóng vai trò cổng vào duy nhất, ẩn giấu toàn bộ sự phức tạp của việc xoay vòng key, timeout, tool parsing và SSE transport phía sau.
2. **Single Responsibility Principle (SRP)**: Mỗi class chỉ xử lý một miền logic duy nhất:
   - `ChatMessageSanitizer`: Chỉ biến đổi và làm sạch data cấu trúc tin nhắn.
   - `AiSseTransport`: Chỉ chịu trách nhiệm về giao thức mạng Server-Sent Events.
   - `ConfirmationBlockParser`: Chỉ bóc tách JSON và sinh schema form tương tác.
3. **Immutability & Stateless Beans**: Tất cả các component bóc tách đều là Spring Singletons phi trạng thái (Stateless), an toàn tuyệt đối khi chạy đồng thời trên hàng ngàn Virtual Threads.
4. **Resilience & Graceful Degradation**:
   - Khi hết quota key -> Xoay sang key phụ.
   - Khi LLM chính nghẽn -> Fallback sang model dự phòng.
   - Khi timeout 60s -> Fallback sang Text-Only thông báo rõ ràng cho người dùng.

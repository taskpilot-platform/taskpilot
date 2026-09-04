---
name: ai-module-refactor
description: Plan, govern, and verify the structural decomposition of taskpilot-ai God files (AiStreamingService 3500+ LOC, TaskPilotAiTools 2100+ LOC) into clean single-responsibility packages while preserving the Single-Agent multi-threaded DAG architecture and 100% logic parity.
---

# AI Module Refactoring & Architecture Governance Skill

This skill governs the systematic, risk-free structural refactoring of the `taskpilot-ai` module in TaskPilot, with primary focus on decomposing the **3,527-line God class** ([`AiStreamingService.java`](file:///home/fhu_thjen/projects/se121/taskpilot/taskpilot-ai/src/main/java/com/taskpilot/ai/service/AiStreamingService.java)) and its companion tool collection ([`TaskPilotAiTools.java`](file:///home/fhu_thjen/projects/se121/taskpilot/taskpilot-ai/src/main/java/com/taskpilot/ai/tools/TaskPilotAiTools.java)).

---

## 🏛️ Core Architectural Invariants (Non-Negotiable)

1. **Single-Agent Paradigm with Multi-Threaded DAG Execution**:
   - **DO NOT** convert to a complex multi-agent framework (e.g. agent-to-agent conversational ping-pong).
   - Real-world benchmarks demonstrate that Single-Agent with Multi-Threaded DAG (Directed Acyclic Graph) query execution achieves **3x–5x lower latency**, deterministic execution, and predictable token costs compared to multi-agent architectures.
   - Maintain LangChain4j virtual threads and parallel chain evaluation in `SmartQueryService`.

2. **100% Business Logic & Behavioral Parity**:
   - Every prompt instruction, system prompt rule, `<think>` reasoning block parsing, tool-call JSON fallback, confirmation modal schema (`confirmationRequired=true`), and timeout policy must remain functionally identical.
   - Zero change to external REST & SSE API contracts (`POST /api/v1/ai/sessions/{sessionId}/stream`).

3. **Max File Size Limit**:
   - No single file in `taskpilot-ai` may exceed **400–500 LOC** post-refactor.
   - Every extracted component must satisfy the **Single Responsibility Principle (SRP)**.

4. **Zero-Regression Verification Gate**:
   - Each wave must compile with zero errors (`./mvnw compile -q`).
   - Dedicated unit tests must be established for extracted components before deprecating legacy methods.

---

## 📂 Target Package Architecture

```text
com.taskpilot.ai/
├── controller/                 # REST & SSE Controller endpoints (Unchanged API contracts)
│   └── AiChatController.java
├── service/                    # Public Service Facade (Thin, backward-compatible delegator)
│   └── AiStreamingService.java # RETAINED as Facade (<350 LOC)
├── streaming/                  # Core SSE Streaming Subsystem
│   ├── engine/                 # Multi-model execution & failover engine
│   │   ├── StreamingChatEngine.java       # Stream round orchestration & key rotation
│   │   ├── DirectOpenAiModelClient.java   # Raw HTTP client for OpenAI-compatible APIs (Gemma/Groq)
│   │   └── TimeoutFallbackHandler.java    # First-response timeout & text-only fallback
│   ├── sse/                    # Transport layer for Server-Sent Events
│   │   ├── AiSseTransport.java            # Safe emitter send/complete/disconnect handler
│   │   └── AiStreamEventFormatter.java    # Event payload builder (token, thinking, status)
│   └── tool/                   # Streaming tool execution & confirmation
│       ├── StreamingToolCoordinator.java  # Tool execution & LangChain4j dispatch
│       └── ConfirmationBlockParser.java   # TaskPilot form & confirmation JSON parsing
├── prompt/                     # Prompt Engineering & System Instructions
│   ├── SystemPromptBuilder.java           # Dynamic prompt construction with user context
│   └── AiPromptConstants.java             # Static prompt templates & instruction blocks
└── context/                    # Chat History, Token Budgeting & Sanitization
    ├── ChatHistoryCompactor.java          # Message window budgeting & summarization
    └── ChatMessageSanitizer.java          # Role alternating, think tag stripping, memory format
```

---

## 📋 Governance Protocol for AI Agents

Whenever tasked with refactoring the AI module:
1. **Never refactor ahead of the plan**: Follow the wave sequence defined in [`workflows/ai-refactor-workflow.md`](workflows/ai-refactor-workflow.md).
2. **Safety Harness First**: Create unit tests for pure functions (e.g. `ChatMessageSanitizerTest`, `ChatHistoryCompactorTest`) *before* extracting them.
3. **Preserve Legacy Signatures**: Keep `AiStreamingService` as a Spring `@Service` bean with its exact `public SseEmitter streamChat(...)` signature so `AiChatController` requires zero code changes.
4. **Log Progress**: Record every wave completion in [`tracking/refactor-tracking.md`](tracking/refactor-tracking.md) and report metrics in `progress-tracker/`.

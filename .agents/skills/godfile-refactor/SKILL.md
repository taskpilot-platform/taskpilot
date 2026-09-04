---
name: godfile-refactor
description: Plan, govern, and verify the structural decomposition of TaskPilot remaining God files (TaskPilotAiTools.java 2155 LOC and AiChatPage.tsx 3352 LOC) into clean, single-responsibility components (<400-500 LOC) while reducing boilerplate and guaranteeing 100% logic, schema, and UI parity.
---

# Full God-File Refactoring & Architecture Governance Skill

This skill governs the systematic, risk-free structural refactoring of the remaining monolithic God-files across the TaskPilot codebase:
1. **Backend God-File**: [`TaskPilotAiTools.java`](file:///home/fhu_thjen/projects/se121/taskpilot/taskpilot-ai/src/main/java/com/taskpilot/ai/tools/TaskPilotAiTools.java) (2,155 LOC)
2. **Frontend God-File**: [`AiChatPage.tsx`](file:///home/fhu_thjen/projects/se121/taskpilot-frontend/src/pages/AiChatPage.tsx) (3,352 LOC)

---

## 🏛️ Core Architectural Invariants (Non-Negotiable)

1. **Zero-Regression & 100% Logic Parity**:
   - **Backend**: Every `@Tool` name, parameter name (`@P`), description, return type, confirmation policy (`confirmationRequired=true`), and JSON normalization rule must remain 100% identical. External REST & SSE APIs and tool dispatch contracts must not change.
   - **Frontend**: Zero visual or behavioral divergence. Markdown rendering, typewriter effect, `<think>` reasoning accordion, task confirmation cards, dynamic form schemas, streaming phase badges, and session management must look and behave identically.

2. **Strict File Size Budget**:
   - Every newly created/extracted class or component must be **strictly under 400–500 LOC**.
   - God-file facades (`TaskPilotAiTools.java` and `AiChatPage.tsx`) must be reduced to **<350 LOC** as clean delegators/orchestrators.

3. **Boilerplate Elimination & Reuse**:
   - Use built-in standard library utilities (e.g. Jackson, Java Stream/Comparator, React hooks, Lodash/built-ins) instead of verbose hand-written loops and repetitive parsing logic.
   - Consolidate common patch parsing, date validation, and ID extraction into shared, reusable utility modules (`AiToolSupport.java`, `aiChatHelpers.ts`).

4. **Preserve Single-Agent Multi-Threaded DAG Execution**:
   - Retain the high-performance Single-Agent model with virtual threads in backend AI tool invocation.
   - Do not introduce agent-to-agent chatter or complex overhead.

5. **Deploy Environment Invariance**:
   - Full build must pass without errors: `./mvnw clean test -pl taskpilot-ai` and `npm run build`.
   - Docker container execution must be verified against Hugging Face environment rules (UID 1000, port 7860, no root filesystem assumptions).

---

## 📂 Architecture Blueprint

### 1. Backend Decomposition: `TaskPilotAiTools` (2,155 LOC -> Delegator + Domain Components)

```text
com.taskpilot.ai.tools/
├── TaskPilotAiTools.java               # Public facade delegator (<350 LOC, all @Tool annotations preserved)
├── support/
│   └── AiToolSupport.java              # Common patch parsing, ID extraction, text normalizers (<250 LOC)
└── domain/                             # Domain-focused tool components (@Component beans)
    ├── ProjectAiTools.java             # Project CRUD, status, labels, due date queries (<300 LOC)
    ├── TaskAiTools.java                # Task CRUD, status, kanban moves, required skills (<320 LOC)
    ├── SprintAiTools.java              # Sprint CRUD, backlog, board, lifecycle management (<220 LOC)
    ├── CommentAiTools.java             # Task comment CRUD and mention queries (<180 LOC)
    ├── NotificationAiTools.java        # Notification listing, unread counts, mark-read (<100 LOC)
    ├── SkillAiTools.java               # System skill and user skill management (<180 LOC)
    ├── AhpAssignmentAiTools.java       # Member workload, assignment, AHP recommendations (<280 LOC)
    └── SystemAiTools.java              # Pending action confirmation/cancel, SQL, smartQuery (<180 LOC)
```

### 2. Frontend Decomposition: `AiChatPage` (3,352 LOC -> Orchestrator + Subcomponents)

```text
taskpilot-frontend/src/
├── pages/
│   └── AiChatPage.tsx                  # Main chat page orchestrator (<300 LOC)
├── hooks/
│   └── useAiChatStream.ts              # SSE EventSource streaming, polling fallback, typewriter loop (<350 LOC)
└── components/ai/
    ├── aiChatTypes.ts                  # Shared TypeScript interfaces & types (<100 LOC)
    ├── aiChatHelpers.ts                # Pure formatting, extraction & deduplication utilities (<250 LOC)
    ├── ChatComposer.tsx                # Prompt input composer with history & char count (<180 LOC)
    ├── TypewriterMarkdown.tsx          # Smooth token streaming markdown renderer (<40 LOC)
    ├── ThinkingAccordion.tsx           # Collapsible reasoning tag display with freeze logic (<180 LOC)
    ├── ToolEventCard.tsx               # Tool event cards & post-processing status steps (<200 LOC)
    ├── CreateTaskConfirmCard.tsx       # Interactive task creation confirmation card (<180 LOC)
    ├── AiSessionSidebar.tsx            # Session list, rename/delete modals, new chat button (<220 LOC)
    ├── DynamicFormRenderer.tsx         # Dynamic schema form renderer for missing fields (<260 LOC)
    ├── AssignmentRequestForm.tsx       # Multi-task assignment row editor (<220 LOC)
    ├── CombinedConfirmAndPlanCard.tsx  # Plan review, modify input, and confirmation cards (<260 LOC)
    └── AiMessageItem.tsx               # Individual message row orchestrator (<220 LOC)
```

---

## 📋 Governance Protocol

1. **Safety First**: Verify existing test suites (`TaskPilotAiToolsHumanInLoopTest`, `npm run build`) before making destructive changes.
2. **Backward-Compatible Signatures**: Keep all existing public method signatures on `TaskPilotAiTools` and component exports on `AiChatPage`.
3. **Audit Gates**: No file may exceed 500 lines upon completion.
4. **Deploy Verification**: Must pass `scripts/verify-hf-deploy.sh` prior to pushing to `main`.

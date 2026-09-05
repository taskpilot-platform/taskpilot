# God-File Refactoring Execution Workflow

This document defines the 5-wave execution workflow for decomposing **TaskPilotAiTools.java** (2,155 LOC) and **AiChatPage.tsx** (3,352 LOC) into maintainable, single-responsibility components under 400-500 LOC.

---

## 🌊 Wave 1: Common Infrastructure & Pure Helpers
- **Backend**:
  - Extract `com.taskpilot.ai.tools.support.AiToolSupport.java` containing patch parsing, type casting, date checks, and name normalization.
  - Verify zero business logic is altered.
- **Frontend**:
  - Extract `src/components/ai/aiChatTypes.ts` with all shared interfaces.
  - Extract `src/components/ai/aiChatHelpers.ts` with all pure formatting, extraction, and deduplication functions.
- **Verification Gate**:
  - `./mvnw compile -q` passes.
  - `npm run build` passes.

---

## 🌊 Wave 2: Backend Domain Tool Extraction
- Extract 8 domain tool components into `com.taskpilot.ai.tools.domain`:
  1. `ProjectAiTools.java` (Project CRUD, status, labels, upcoming queries)
  2. `TaskAiTools.java` (Task CRUD, status, kanban, required skills)
  3. `SprintAiTools.java` (Sprint CRUD, backlog, board, lifecycle)
  4. `CommentAiTools.java` (Task comment CRUD and mention search)
  5. `NotificationAiTools.java` (Notification listing and status)
  6. `SkillAiTools.java` (System skills and user profile skills)
  7. `AhpAssignmentAiTools.java` (Member workload and AHP recommendations)
  8. `SystemAiTools.java` (Pending actions, raw SQL, smartQuery)
- Turn `TaskPilotAiTools.java` into a thin delegator bean with all `@Tool` annotations preserved.
- **Verification Gate**:
  - `./mvnw clean test -pl taskpilot-ai` passes 100% (37/37 tests pass, including `TaskPilotAiToolsHumanInLoopTest`).

---

## 🌊 Wave 3: Frontend Subcomponent Extraction
- Extract isolated UI components into `src/components/ai/`:
  - `ChatComposer.tsx`
  - `TypewriterMarkdown.tsx`
  - `ThinkingAccordion.tsx`
  - `ToolEventCard.tsx`
  - `CreateTaskConfirmCard.tsx`
  - `AiSessionSidebar.tsx`
  - `DynamicFormRenderer.tsx`
  - `AssignmentRequestForm.tsx`
  - `CombinedConfirmAndPlanCard.tsx`
  - `AiMessageItem.tsx`
- Extract custom streaming hook `src/hooks/useAiChatStream.ts`.
- Recompose `AiChatPage.tsx` to a lean orchestrator (<300 LOC).
- **Verification Gate**:
  - `npm run build` passes with zero TypeScript errors.

---

## 🌊 Wave 4: Integration & Hugging Face Deploy Verification
- Run `bash scripts/verify-hf-deploy.sh`.
- Confirm container builds and runs cleanly under UID 1000 on port 7860.
- Check actuator health and log output.

---

## 🌊 Wave 5: Version Control, Audit & Report Synchronization
- Merge refactor branches into `main` for `taskpilot` and `taskpilot-frontend`.
- Update biweekly progress tracker and audit metrics.
- Push changes to remote GitHub repositories.

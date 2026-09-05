# Master Plan: Monolithic God-File Decomposition

## 1. Executive Summary
This plan governs the complete structural refactoring of the remaining God-files in TaskPilot:
- **Backend**: `TaskPilotAiTools.java` (2,155 LOC) -> Decomposed into 8 domain components + 1 support utility + 1 delegator facade (<350 LOC).
- **Frontend**: `AiChatPage.tsx` (3,352 LOC) -> Decomposed into 10 modular subcomponents + 1 custom hook + 1 page orchestrator (<300 LOC).

## 2. Line of Code (LOC) Target Budget

| Target Component | Role | Current LOC | Target LOC | Expected Reduction |
| :--- | :--- | :--- | :--- | :--- |
| **Backend: TaskPilotAiTools.java** | Facade Delegator | 2,155 | < 350 | **-83.7%** |
| `AiToolSupport.java` | Shared Support Utils | - | ~230 | New |
| `ProjectAiTools.java` | Domain Tools | - | ~280 | New |
| `TaskAiTools.java` | Domain Tools | - | ~320 | New |
| `SprintAiTools.java` | Domain Tools | - | ~220 | New |
| `CommentAiTools.java` | Domain Tools | - | ~180 | New |
| `NotificationAiTools.java` | Domain Tools | - | ~90 | New |
| `SkillAiTools.java` | Domain Tools | - | ~170 | New |
| `AhpAssignmentAiTools.java` | Domain Tools | - | ~270 | New |
| `SystemAiTools.java` | Domain Tools | - | ~170 | New |
| **Frontend: AiChatPage.tsx** | Page Orchestrator | 3,352 | < 300 | **-91.0%** |
| `useAiChatStream.ts` | Custom SSE Hook | - | ~340 | New |
| `aiChatTypes.ts` | Type Definitions | - | ~90 | New |
| `aiChatHelpers.ts` | Pure Helpers | - | ~250 | New |
| `ChatComposer.tsx` | Input Composer | - | ~170 | New |
| `ThinkingAccordion.tsx` | Thought Tag Viewer | - | ~170 | New |
| `ToolEventCard.tsx` | Tool Card & Steps | - | ~190 | New |
| `CreateTaskConfirmCard.tsx` | Task Confirm Card | - | ~160 | New |
| `AiSessionSidebar.tsx` | Sidebar & Modals | - | ~220 | New |
| `DynamicFormRenderer.tsx` | Form Generator | - | ~260 | New |
| `AssignmentRequestForm.tsx` | Multi-task Editor | - | ~220 | New |
| `CombinedConfirmAndPlanCard.tsx` | Plan Review Card | - | ~250 | New |
| `AiMessageItem.tsx` | Message Row Renderer | - | ~220 | New |
| `TypewriterMarkdown.tsx` | Token Markdown Stream | - | ~35 | New |

## 3. Strict Invariant Guarantees
- All 55 `@Tool` method signatures remain unchanged for LangChain4j reflection.
- All dynamic form JSON blocks (`taskpilot-form`, confirmation IDs) remain identical.
- Zero breaking changes to REST/SSE endpoints or UI styles.

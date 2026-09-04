---
name: code-refactor
description: Refactor TaskPilot codebase (BE + FE) to reduce boilerplate and shorten code while preserving existing architecture, logic, and UI. Agent must follow strict evaluation criteria and tracking workflow.
---

# Code Refactor Skill — TaskPilot

> **Mục tiêu duy nhất:** Rút ngắn code, giảm boilerplate, KHÔNG thay đổi kiến trúc / logic / giao diện.

## Phạm vi (Scope)

| Aspect | Allowed ✅ | Forbidden ❌ |
|---|---|---|
| **Code length** | Rút ngắn, gộp pattern lặp, xóa code thừa | Thêm abstraction layer mới phức tạp hơn |
| **Architecture** | Giữ nguyên modular multi-module (BE) | Thay đổi module boundaries, rename packages |
| **Logic** | Giữ nguyên 100% business logic | Thay đổi flow, thêm/bớt validation |
| **UI/UX** | Giữ nguyên giao diện hiện tại | Thay đổi layout, colors, component hierarchy |
| **API contracts** | Giữ nguyên tất cả endpoints + request/response | Rename, thêm/bớt fields |
| **Dependencies** | Giữ nguyên | Thêm library mới, upgrade major version |
| **Tests** | Thêm/update nếu cần | Xóa test hiện có |

## Quy tắc Refactor

### Backend (Java / Spring Boot)

1. **Controller boilerplate** — `ApiResponse.success(HttpStatus.OK.value(), "...", data)` lặp đi lặp lại ở mọi endpoint (16 controllers, ~60 endpoints). Tạo helper `ApiResponse.ok(data)`, `ApiResponse.created(data)`.
2. **`getCurrentUserIdByEmail` duplication** — Method 5-line identical lặp ở **7 files** (`ProjectServiceImpl`, `TaskService`, `SprintService`, `LabelService`, `TaskCommentService`, `NotificationService`, `AiChatController`). Centralize vào `taskpilot-infrastructure` hoặc custom `@CurrentUser` resolver.
3. **`buildSafePageable` duplication** — 15-line method copy-paste ở **3 files** (`AdminUserService`, `AdminSkillService`, `ProjectServiceImpl`). Move to `PageableUtils` in infrastructure.
4. **Project permission validations** — `validateMember`, `validateManager`, `validateProjectNotArchived` copy-paste ở **4 services** (`ProjectServiceImpl`, `TaskService`, `SprintService`, `TimelineService`). Extract `ProjectSecurityService`.
5. **Date range validation** — `validateDateRange` lặp ở **3 files**. Centralize.
6. **Authentication boilerplate** — `Authentication authentication` + `authentication.getName()` lặp ở mọi controller method. Có thể dùng `@CurrentUserEmail` resolver.
7. **Import cleanup** — Xóa unused imports.
8. **Duplicate DTOs** — `AdminUserResponse` ≡ `UserProfileResponse` (identical fields + factory method). Consolidate.
9. **God-class splitting** — `TaskPilotAiTools.java` (2,156 lines!) split thành domain-specific tool classes mà không đổi logic.
10. **MapStruct** — Không dùng MapStruct (hiện tại handwrite conversion). Giữ nguyên pattern, chỉ rút gọn nếu có thể.

### Frontend (React + TypeScript)

1. **Service boilerplate** — Mỗi service method đều có pattern `const response = await http.get<ApiResponse<T>>(...); return response.data;`. Tạo wrapper function rút gọn (~250 lines savings across 11 files, 60+ methods).
2. **Dead code removal** — Xóa code/files không dùng:
   - `src/App.tsx` (50 lines) + `src/App.css` (35 lines) — Vite template leftovers
   - `src/components/LiquidCard.tsx` (60 lines) — unused demo card
   - `src/components/ui/toast.tsx` + `toaster.tsx` + `src/hooks/use-toast.ts` (~350 lines) — shadcn toast không dùng (dự án dùng `react-toastify`)
   - Unused npm packages: `@assistant-ui/react`, `@assistant-ui/react-markdown`
3. **Duplicate handlers** — Code copy-paste giữa các files:
   - `handleLeaveProject` — 30 lines identical giữa `ProjectSettingsPage` và `ProjectWorkspacePage`
   - `mergeById` — identical giữa `NotificationsPage` và `CommentsPage`
   - Task mutation logic (`onUpdateTask`, `handleDeleteTask`, `onCreateSubtask`) — identical giữa `TaskDetailPage` và `ProjectWorkspacePage`
4. **Pagination state boilerplate** — `AdminUsersPage`, `AdminGlobalSkillsPage`, `ProjectsPage`, `MySkillsPage` có ~100 lines identical state setup. Extract `usePaginatedSplitView` hook.
5. **Auth form hand-rolling** — 4 auth pages dùng 6-10 `useState` hooks + manual regex thay vì `react-hook-form` + `zod` (đã cài sẵn). Tạo `<PasswordField />` component reusable.
6. **Long page files** — `AiChatPage.tsx` (3352 lines, có `@ts-nocheck`!), `ProjectWorkspacePage.tsx` (1815 lines). Extract sub-components + hooks.
7. **Data fetching pattern** — Lặp `isLoading + try/catch/finally + toast.error` ở mọi page. Có thể extract `useAsyncData` hook.
8. **Type definitions** — Không đổi type contracts, chỉ gộp re-export nếu cần.
9. **UI Components (shadcn/ui)** — KHÔNG chạm vào `components/ui/*` (trừ dead code toast).

## Workflow cho Agent

Đọc file: `references/workflow.md`

## Tiêu chuẩn đánh giá

Đọc file: `references/evaluation-criteria.md`

## Tracking Progress

Đọc file: `references/tracking-template.md`

## Pre-Refactor Checklist

Trước khi refactor bất kỳ file nào, agent PHẢI:

1. ✅ Đọc file gốc hoàn chỉnh
2. ✅ Xác định pattern cần refactor thuộc danh sách cho phép ở trên
3. ✅ Đếm số dòng TRƯỚC refactor
4. ✅ Chạy build/compile check SAU refactor
5. ✅ Đếm số dòng SAU refactor
6. ✅ So sánh reduction % >= 10% (ngưỡng tối thiểu)
7. ✅ Verify UI/API không thay đổi (chạy test nếu có)
8. ✅ Cập nhật tracking sheet

## Post-Refactor Verification

```bash
# Backend - compile check
cd taskpilot && ./mvnw compile -pl taskpilot-infrastructure,taskpilot-contracts,taskpilot-projects,taskpilot-users,taskpilot-ai,taskpilot-app -q

# Backend - test
cd taskpilot && ./mvnw test -q

# Frontend - type check
cd taskpilot-frontend && npx tsc --noEmit

# Frontend - build check
cd taskpilot-frontend && npm run build
```

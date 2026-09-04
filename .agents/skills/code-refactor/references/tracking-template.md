# Tracking Template — Refactor Progress

## Hướng dẫn sử dụng

Agent tạo 1 bản copy của file này tại:
```
<artifactDir>/refactor-tracking.md
```

Cập nhật sau mỗi file/wave hoàn thành. User có thể xem progress real-time.

---

## Overall Progress Dashboard

| Phase | Status | Progress | Files Done | LOC Saved |
|---|---|---|---|---|
| Phase 0: Preparation | ⬜ Not Started | 0% | - | - |
| Phase 1: Backend | ⬜ Not Started | 0% | 0/274 | 0 |
| Phase 2: Frontend | ⬜ Not Started | 0% | 0/91 | 0 |
| Phase 3: Verification | ⬜ Not Started | 0% | - | - |
| **TOTAL** | ⬜ Not Started | **0%** | **0/365** | **0** |

Status icons: ⬜ Not Started | 🔄 In Progress | ✅ Complete | ❌ Failed | ⏸️ Paused

---

## Phase 1: Backend Tracking

### Wave 1.1 — Foundation Utilities
| Step | File | Status | Before LOC | After LOC | Saved | % | Gate |
|---|---|---|---|---|---|---|---|
| 1.1.1 | `ApiResponse.java` | ⬜ | - | - | - | - | ⬜ |
| 1.1.2 | `UserResolverUtils` | ⬜ | - | - | - | - | ⬜ |
| 1.1.3 | `PageableUtils` | ⬜ | - | - | - | - | ⬜ |
| 1.1.4 | `ProjectSecurityService` | ⬜ | - | - | - | - | ⬜ |
| 1.1.5 | `ValidationUtils` | ⬜ | - | - | - | - | ⬜ |
| 1.1.6 | `@CurrentUserEmail` resolver | ⬜ | - | - | - | - | ⬜ |
| | **Wave 1.1 Total** | ⬜ | **-** | **-** | **~375** | **-** | ⬜ |

### Wave 1.2 — Controllers
| Step | File | Status | Before LOC | After LOC | Saved | % | Gate |
|---|---|---|---|---|---|---|---|
| 1.2.1 | `ProjectController.java` | ⬜ | 157 | - | - | - | ⬜ |
| 1.2.2 | `TaskController.java` | ⬜ | 108 | - | - | - | ⬜ |
| 1.2.3 | `SprintController.java` | ⬜ | 107 | - | - | - | ⬜ |
| 1.2.4 | `TaskCommentController.java` | ⬜ | 99 | - | - | - | ⬜ |
| 1.2.5 | `AuthController.java` | ⬜ | 104 | - | - | - | ⬜ |
| 1.2.6 | `NotificationController.java` | ⬜ | 88 | - | - | - | ⬜ |
| 1.2.7 | `AdminUserController.java` | ⬜ | 73 | - | - | - | ⬜ |
| 1.2.8 | `SkillController.java` | ⬜ | 68 | - | - | - | ⬜ |
| 1.2.9 | Remaining controllers | ⬜ | ~200 | - | - | - | ⬜ |
| | **Wave 1.2 Total** | ⬜ | **~1004** | **-** | **-** | **-** | ⬜ |

### Wave 1.3 — Services
| Step | File | Status | Before LOC | After LOC | Saved | % | Gate |
|---|---|---|---|---|---|---|---|
| 1.3.1 | `ProjectServiceImpl.java` | ⬜ | 493 | - | - | - | ⬜ |
| 1.3.2 | `TaskService.java` | ⬜ | 397 | - | - | - | ⬜ |
| 1.3.3 | `SprintService.java` | ⬜ | 253 | - | - | - | ⬜ |
| 1.3.4 | `TaskCommentService.java` | ⬜ | 634 | - | - | - | ⬜ |
| 1.3.5 | `AuthService.java` | ⬜ | 152 | - | - | - | ⬜ |
| 1.3.6 | `AdminUserService.java` | ⬜ | 183 | - | - | - | ⬜ |
| 1.3.7 | `NotificationService.java` | ⬜ | 122 | - | - | - | ⬜ |
| | **Wave 1.3 Total** | ⬜ | **~2234** | **-** | **-** | **-** | ⬜ |

### Wave 1.4 — AI Service
| Step | File | Status | Before LOC | After LOC | Saved | % | Gate |
|---|---|---|---|---|---|---|---|
| 1.4.1 | `AiStreamingService.java` | ⬜ | 3527 | - | - | - | ⬜ |
| 1.4.2 | `TaskPilotAiTools.java` | ⬜ | 2156 | - | - | - | ⬜ |
| 1.4.3 | `SmartRoutingService.java` | ⬜ | 824 | - | - | - | ⬜ |
| 1.4.4 | `ToolCallingRegistryService.java` | ⬜ | 724 | - | - | - | ⬜ |
| 1.4.5 | `SmartQueryService.java` | ⬜ | 719 | - | - | - | ⬜ |
| 1.4.6 | `AutoAssignmentService.java` | ⬜ | 376 | - | - | - | ⬜ |
| | **Wave 1.4 Total** | ⬜ | **~8326** | **-** | **-** | **-** | ⬜ |

### Wave 1.5 — DTOs & Entities
| Step | Action | Status | Files | Before LOC | After LOC | Saved | % | Gate |
|---|---|---|---|---|---|---|---|---|
| 1.5.1 | DTO → Java records | ⬜ | ~35 | ~504 | - | - | - | ⬜ |
| 1.5.2 | Lombok cleanup | ⬜ | - | - | - | - | - | ⬜ |
| 1.5.3 | MapStruct policy | ⬜ | 1 | - | - | - | - | ⬜ |
| | **Wave 1.5 Total** | ⬜ | **-** | **-** | **-** | **-** | **-** | ⬜ |

---

## Phase 2: Frontend Tracking

### Wave 2.0 — Dead Code Removal
| Step | File/Item | Status | Lines Saved | Gate |
|---|---|---|---|---|
| 2.0.1 | `src/App.tsx` | ⬜ | 50 | ⬜ |
| 2.0.2 | `src/App.css` | ⬜ | 35 | ⬜ |
| 2.0.3 | `LiquidCard.tsx` | ⬜ | 60 | ⬜ |
| 2.0.4 | shadcn toast (3 files) | ⬜ | ~350 | ⬜ |
| 2.0.5 | Unused npm packages | ⬜ | - | ⬜ |
| | **Wave 2.0 Total** | ⬜ | **~495** | ⬜ |

### Wave 2.1 — Foundation Utilities
| Step | File | Status | Before LOC | After LOC | Saved | % | Gate |
|---|---|---|---|---|---|---|---|
| 2.1.1 | `lib/http.ts` wrapper | ⬜ | - | - | - | - | ⬜ |
| 2.1.2 | `hooks/useAsyncData.ts` | ⬜ | - | - | - | - | ⬜ |
| 2.1.3 | `hooks/usePaginatedSplitView.ts` | ⬜ | - | - | - | - | ⬜ |
| 2.1.4 | `hooks/useLeaveProject.ts` | ⬜ | - | - | - | - | ⬜ |
| 2.1.5 | `hooks/useTaskActions.ts` | ⬜ | - | - | - | - | ⬜ |
| 2.1.6 | `utils/mergeById.ts` | ⬜ | - | - | - | - | ⬜ |
| 2.1.7 | `components/PasswordField.tsx` | ⬜ | - | - | - | - | ⬜ |
| | **Wave 2.1 Total** | ⬜ | **-** | **-** | **-** | **-** | ⬜ |

### Wave 2.2 — Service Files
| Step | File | Status | Before LOC | After LOC | Saved | % | Gate |
|---|---|---|---|---|---|---|---|
| 2.2.1 | `project.service.ts` | ⬜ | 82 | - | - | - | ⬜ |
| 2.2.2 | `task.service.ts` | ⬜ | 99 | - | - | - | ⬜ |
| 2.2.3 | `sprint.service.ts` | ⬜ | 57 | - | - | - | ⬜ |
| 2.2.4 | `admin.service.ts` | ⬜ | 146 | - | - | - | ⬜ |
| 2.2.5 | Remaining services (7 files) | ⬜ | ~296 | - | - | - | ⬜ |
| | **Wave 2.2 Total** | ⬜ | **~680** | **-** | **-** | **-** | ⬜ |

### Wave 2.3 — Page Components
| Step | File | Status | Before LOC | After LOC | Saved | % | Gate |
|---|---|---|---|---|---|---|---|
| 2.3.1 | `AiChatPage.tsx` | ⬜ | 3352 | - | - | - | ⬜ |
| 2.3.2 | `ProjectWorkspacePage.tsx` | ⬜ | 1815 | - | - | - | ⬜ |
| 2.3.3 | `AdminSettingsPage.tsx` | ⬜ | 855 | - | - | - | ⬜ |
| 2.3.4 | `ProjectsPage.tsx` | ⬜ | 690 | - | - | - | ⬜ |
| 2.3.5 | `MySkillsPage.tsx` | ⬜ | 606 | - | - | - | ⬜ |
| 2.3.6 | `ProjectSettingsPage.tsx` | ⬜ | 549 | - | - | - | ⬜ |
| 2.3.7 | `AdminUsersPage.tsx` | ⬜ | 520 | - | - | - | ⬜ |
| 2.3.8 | `AdminGlobalSkillsPage.tsx` | ⬜ | 453 | - | - | - | ⬜ |
| 2.3.9 | `ProfilePage.tsx` | ⬜ | 440 | - | - | - | ⬜ |
| 2.3.10 | Remaining pages | ⬜ | ~1828 | - | - | - | ⬜ |
| | **Wave 2.3 Total** | ⬜ | **~11108** | **-** | **-** | **-** | ⬜ |

### Wave 2.4 — Components
| Step | File | Status | Before LOC | After LOC | Saved | % | Gate |
|---|---|---|---|---|---|---|---|
| 2.4.1 | `ActivityTimeline.tsx` | ⬜ | 925 | - | - | - | ⬜ |
| 2.4.2 | `TaskMetadataSidebar.tsx` | ⬜ | 185 | - | - | - | ⬜ |
| 2.4.3 | `LabelSelector.tsx` | ⬜ | 172 | - | - | - | ⬜ |
| 2.4.4 | `JSONRenderer.tsx` | ⬜ | 143 | - | - | - | ⬜ |
| 2.4.5 | `MainLayout.tsx` | ⬜ | 585 | - | - | - | ⬜ |
| | **Wave 2.4 Total** | ⬜ | **~2010** | **-** | **-** | **-** | ⬜ |

### Wave 2.5 — Auth Form Migration
| Step | File | Status | Before LOC | After LOC | Saved | % | Gate |
|---|---|---|---|---|---|---|---|
| 2.5.1 | `LoginPage.tsx` | ⬜ | 114 | - | - | - | ⬜ |
| 2.5.2 | `RegisterPage.tsx` | ⬜ | 158 | - | - | - | ⬜ |
| 2.5.3 | `ForgotPasswordPage.tsx` | ⬜ | 113 | - | - | - | ⬜ |
| 2.5.4 | `ResetPasswordPage.tsx` | ⬜ | 139 | - | - | - | ⬜ |
| | **Wave 2.5 Total** | ⬜ | **~524** | **-** | **-** | **-** | ⬜ |

---

## Phase 3: Final Verification

| Check | Status | Result |
|---|---|---|
| `./mvnw compile -q` | ⬜ | - |
| `./mvnw test -q` | ⬜ | - |
| `npx tsc --noEmit` | ⬜ | - |
| `npm run build` | ⬜ | - |
| UI visual check | ⬜ | - |
| API smoke test | ⬜ | - |

---

## Summary Report

| Metric | Before | After | Change | Rating |
|---|---|---|---|---|
| **BE Total LOC** | 22,760 | - | - | - |
| **FE Total LOC** | 17,937 | - | - | - |
| **Total LOC** | 40,697 | - | - | - |
| **BE Files** | 274 | - | - | - |
| **FE Files** | 91 | - | - | - |
| **All Tests Pass** | ✅ | - | - | - |
| **Build Success** | ✅ | - | - | - |

### Final Score

| Category | Weight | Score | Weighted |
|---|---|---|---|
| LOC Reduction | 30% | -/100 | - |
| Zero Regression | 30% | -/100 | - |
| Code Quality | 20% | -/100 | - |
| Architecture | 20% | -/100 | - |
| **TOTAL** | **100%** | - | **-/100** |

---

## Git Commit Log

| Wave | Commit Hash | Message | LOC Saved |
|---|---|---|---|
| - | - | - | - |

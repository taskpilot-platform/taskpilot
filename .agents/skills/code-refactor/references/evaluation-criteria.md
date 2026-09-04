# Tiêu Chuẩn Đánh Giá Refactor — Evaluation Criteria

## 1. Metrics Đo Lường (Quantitative)

### 1.1 Lines of Code (LOC) Reduction

| Rating | Reduction % | Description |
|---|---|---|
| ⭐⭐⭐⭐⭐ Excellent | ≥ 25% | Significant boilerplate elimination |
| ⭐⭐⭐⭐ Good | 15-24% | Strong reduction, patterns well-consolidated |
| ⭐⭐⭐ Acceptable | 10-14% | Meaningful reduction |
| ⭐⭐ Marginal | 5-9% | Minor cleanup only |
| ⭐ Insufficient | < 5% | Not worth the risk/effort |

**Ngưỡng tối thiểu:** Mỗi file refactored phải giảm ≥ **10%** LOC.
**Target tổng dự án:** Giảm ≥ **15%** tổng LOC.

### 1.2 Baseline Metrics (Đo trước khi bắt đầu)

| Metric | Backend (Java) | Frontend (TS/TSX) |
|---|---|---|
| Total files | 274 (271 prod + 3 test) | 91 |
| Total LOC | ~21,750 | ~17,287 |
| Target LOC | ~18,490 (-15%) | ~14,700 (-15%) |
| Heaviest area | AI module: 12,200 LOC (56%!) | Pages: 10,126 LOC (59%) |
| God-classes | `AiStreamingService` (3,528) + `TaskPilotAiTools` (2,156) = 5,684 | `AiChatPage` (3,352) + `ProjectWorkspacePage` (1,815) = 5,167 |
| Controllers/Pages | 1,379 LOC / 16 files | 11,108 LOC / 18 pages |
| Services | 10,875 LOC / 40 files | 733 LOC / 11 files |
| Dead code | Duplicate DTOs | ~495 LOC (App.tsx, LiquidCard, shadcn toast) |

### 1.3 Complexity Metrics

- **Cyclomatic Complexity:** Không được tăng
- **File count:** Có thể tăng nhẹ nếu extract sub-components (FE), nhưng tổng LOC phải giảm
- **Import count:** Phải giảm hoặc giữ nguyên per file

---

## 2. Tiêu Chuẩn Chất Lượng (Qualitative)

### 2.1 Zero Regression (Bắt buộc — P0)

| Check | Tool | Pass Criteria |
|---|---|---|
| BE Compilation | `./mvnw compile -q` | Exit code 0, no warnings |
| BE Tests | `./mvnw test -q` | All existing tests pass |
| FE Type Check | `npx tsc --noEmit` | Zero type errors |
| FE Build | `npm run build` | Successful build, no errors |
| API Contract | Manual/automated | All endpoints return same response shape |
| UI Visual | Screenshot comparison | No visible layout changes |

### 2.2 Code Quality (Bắt buộc — P1)

| Criteria | Pass | Fail |
|---|---|---|
| Readability | Code dễ đọc hơn hoặc tương đương | Code khó đọc hơn |
| Naming | Tên biến/method rõ ràng | Tên viết tắt khó hiểu |
| DRY principle | Giảm repetition | Tạo abstraction quá phức tạp |
| Single Responsibility | Mỗi file/function có 1 trách nhiệm rõ | God-class/god-function |
| Consistency | Pattern nhất quán across codebase | Mỗi nơi 1 kiểu |

### 2.3 Architecture Preservation (Bắt buộc — P0)

| Check | Allowed | Forbidden |
|---|---|---|
| Module boundaries | Giữ nguyên 6 modules BE | Merge/split modules |
| Package structure | Giữ nguyên | Rename packages |
| Dependency direction | `contracts` ← others | Circular dependencies |
| Component hierarchy (FE) | Extract child components | Flatten/restructure routes |
| State management | Zustand stores giữ nguyên shape | Switch to Redux/Context |

---

## 3. Per-Wave Gate Criteria

### Wave Gate Template

Mỗi wave phải pass TẤT CẢ criteria trước khi chuyển sang wave tiếp theo:

```
□ Compilation pass (BE: mvnw compile / FE: tsc --noEmit)
□ Tests pass (BE: mvnw test / FE: npm run build)
□ LOC reduction ≥ 10% (per modified file average)
□ No new dependencies added
□ No API contract changes
□ No UI layout changes
□ Git commit created with proper message format
□ Tracking sheet updated
```

---

## 4. Điểm Tổng Kết (Final Score)

| Category | Weight | Max Score |
|---|---|---|
| LOC Reduction (target ≥15%) | 30% | 100 |
| Zero Regression (all checks pass) | 30% | 100 |
| Code Quality Improvement | 20% | 100 |
| Architecture Preservation | 20% | 100 |

**Passing Score:** ≥ **75/100**

### Scoring Guide

- **LOC Reduction:** `score = min(100, (actual_reduction% / 15%) * 100)`
- **Zero Regression:** Binary — 100 if all pass, 0 if any regression
- **Code Quality:** Subjective review (readability + consistency + DRY)
- **Architecture:** Binary — 100 if preserved, 0 if violated

---

## 5. Specific Anti-Patterns to Watch

### Backend
- ❌ Tạo `AbstractBaseController` quá generic → khó debug
- ❌ Dùng reflection/dynamic proxy để giảm code → runtime overhead
- ❌ Merge DTOs khác domain → coupling
- ❌ Xóa Swagger annotations để giảm LOC → mất documentation

### Frontend
- ❌ Dùng `any` type để bypass TypeScript → type safety loss
- ❌ Over-abstracting form components → config object phức tạp hơn JSX
- ❌ Inline tất cả sub-components vào 1 file → ngược mục tiêu
- ❌ Xóa error handling để code ngắn hơn → UX regression

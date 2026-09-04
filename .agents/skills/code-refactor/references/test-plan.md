# Refactor Test Plan

## 1. Test Strategy

### Nguyên tắc
- **Refactor không thay đổi behavior** → tất cả test hiện có PHẢI pass
- **Thêm test mới** cho các utility/helper mới tạo (foundation layer)
- **Regression testing** bằng build checks tự động tại mỗi wave gate

### Test Pyramid

```
        /  UI Visual  \          ← Manual: screenshot comparison
       / Integration    \        ← Existing tests (keep passing)
      /  Unit Tests      \       ← Existing + new for utilities
     /  Compile/Type Check\      ← Automated at every step
```

---

## 2. Automated Test Commands

### Backend

```bash
# Level 1: Compilation (chạy sau MỖI file change)
cd taskpilot && ./mvnw compile -pl taskpilot-infrastructure,taskpilot-contracts,taskpilot-projects,taskpilot-users,taskpilot-ai,taskpilot-app -q

# Level 2: Unit tests (chạy sau mỗi wave)
cd taskpilot && ./mvnw test -q

# Level 3: Full build (chạy sau mỗi phase)
cd taskpilot && ./mvnw clean package -DskipTests -q
```

### Frontend

```bash
# Level 1: Type check (chạy sau MỖI file change)
cd taskpilot-frontend && npx tsc --noEmit

# Level 2: Build (chạy sau mỗi wave)
cd taskpilot-frontend && npm run build

# Level 3: Full verification (chạy sau mỗi phase)
cd taskpilot-frontend && npx tsc --noEmit && npm run build
```

---

## 3. New Tests to Write (Foundation Layer)

### 3.1 Backend — `ApiResponse` helper methods

```java
// Test file: taskpilot-infrastructure/src/test/java/.../ApiResponseTest.java
@Test void ok_withData_shouldReturn200() {
    var resp = ApiResponse.ok(someData);
    assertEquals(200, resp.getStatus());
    assertEquals(someData, resp.getData());
}

@Test void created_withData_shouldReturn201() {
    var resp = ApiResponse.created(someData);
    assertEquals(201, resp.getStatus());
}
```

### 3.2 Backend — Base Repository `findByIdOrThrow`

```java
// Test: verify throws BusinessException with correct message
@Test void findByIdOrThrow_notFound_shouldThrow() {
    assertThrows(BusinessException.class, () ->
        repo.findByIdOrThrow(999L, "Project"));
}
```

### 3.3 Frontend — HTTP wrapper

```typescript
// Test: verify typed wrapper returns correct shape
// (If vitest/jest is configured)
test('api.get returns ApiResponse directly', async () => {
    const result = await api.get<Project[]>('/v1/projects/my');
    expect(result).toHaveProperty('status');
    expect(result).toHaveProperty('data');
});
```

---

## 4. Manual Test Checklist

### 4.1 API Smoke Test (sau mỗi BE wave)

| Endpoint | Method | Expected | Check |
|---|---|---|---|
| `/api/v1/projects/my` | GET | 200 + project list | ⬜ |
| `/api/v1/projects/{id}` | GET | 200 + project detail | ⬜ |
| `/api/v1/tasks?projectId={id}` | GET | 200 + task list | ⬜ |
| `/api/v1/tasks/{id}` | GET | 200 + task detail | ⬜ |
| `/api/v1/sprints?projectId={id}` | GET | 200 + sprint list | ⬜ |
| `/api/v1/auth/login` | POST | 200 + JWT token | ⬜ |
| `/api/v1/ai/chat` | POST | 200 + SSE stream | ⬜ |

### 4.2 UI Visual Check (sau mỗi FE wave)

| Page | Check | Pass |
|---|---|---|
| Login page | Layout, form fields, buttons | ⬜ |
| Dashboard | Cards, charts, stats | ⬜ |
| Project list | Table, search, pagination | ⬜ |
| Project workspace | Kanban board, task cards | ⬜ |
| Task detail | Metadata sidebar, comments | ⬜ |
| AI Chat | Message bubbles, input, streaming | ⬜ |
| Profile | Avatar, form fields | ⬜ |
| Admin pages | Tables, modals | ⬜ |

### 4.3 Mobile Responsive Check (sau FE Phase 2)

Sử dụng skill `mobile-responsive-test` hiện có:
```bash
node .agents/skills/mobile-responsive-test/scripts/responsive-check.js
```

---

## 5. Regression Detection Script

Agent tạo script tự động chạy tất cả checks:

```bash
#!/bin/bash
# scripts/refactor-verify.sh
set -e

echo "=== Backend Compilation ==="
cd taskpilot && ./mvnw compile -q && cd ..

echo "=== Backend Tests ==="
cd taskpilot && ./mvnw test -q && cd ..

echo "=== Frontend Type Check ==="
cd taskpilot-frontend && npx tsc --noEmit && cd ..

echo "=== Frontend Build ==="
cd taskpilot-frontend && npm run build && cd ..

echo ""
echo "=== ✅ All Refactor Verification Checks PASSED ==="
```

---

## 6. LOC Measurement Script

```bash
#!/bin/bash
# scripts/measure-loc.sh
echo "=== Backend LOC ==="
find taskpilot -name "*.java" -exec cat {} + | wc -l

echo "=== Frontend LOC ==="
find taskpilot-frontend/src \( -name "*.tsx" -o -name "*.ts" \) -exec cat {} + | wc -l

echo "=== Frontend Pages LOC ==="
find taskpilot-frontend/src/pages -name "*.tsx" -exec cat {} + | wc -l

echo "=== Frontend Services LOC ==="
find taskpilot-frontend/src/services -name "*.ts" -exec cat {} + | wc -l

echo "=== Backend Controllers LOC ==="
find taskpilot -name "*Controller.java" -exec cat {} + | wc -l

echo "=== Backend Services LOC ==="
find taskpilot -name "*Service*.java" -exec cat {} + | wc -l
```

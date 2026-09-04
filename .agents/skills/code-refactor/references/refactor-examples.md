# Refactor Examples — Before/After Patterns

Tài liệu này chứa các ví dụ cụ thể về cách refactor, để agent tham khảo khi thực hiện.

---

## Backend Examples

### Example 1: ApiResponse Shorthand

**Before (hiện tại):**
```java
@GetMapping("/{projectId}")
public ApiResponse<ProjectResponse> getProjectDetail(
        @PathVariable Long projectId,
        Authentication authentication) {
    return ApiResponse.success(HttpStatus.OK.value(), "Project retrieved successfully",
            projectService.getProjectDetail(projectId, authentication.getName()));
}
```

**After (refactored):**
```java
@GetMapping("/{projectId}")
public ApiResponse<ProjectResponse> getProjectDetail(
        @PathVariable Long projectId, Authentication authentication) {
    return ApiResponse.ok(projectService.getProjectDetail(projectId, authentication.getName()));
}
```

**How:** Thêm vào `ApiResponse.java`:
```java
public static <T> ApiResponse<T> ok(T data) {
    return success(200, "Success", data);
}
public static <T> ApiResponse<T> ok(String message, T data) {
    return success(200, message, data);
}
public static <T> ApiResponse<T> created(T data) {
    return success(201, "Created", data);
}
```

**Savings:** ~15 chars per endpoint × 40+ endpoints = ~600 chars = ~20 lines total

---

### Example 2: Authentication Parameter Simplification

**Before:**
```java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public ApiResponse<ProjectResponse> createProject(
        @Valid @RequestBody CreateProjectRequest request,
        Authentication authentication) {
    return ApiResponse.ok(projectService.createProject(request, authentication.getName()));
}
```

**After (với custom annotation):**
```java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public ApiResponse<ProjectResponse> createProject(
        @Valid @RequestBody CreateProjectRequest request,
        @CurrentUserEmail String email) {
    return ApiResponse.ok(projectService.createProject(request, email));
}
```

**How:** Tạo `@CurrentUserEmail` annotation + `HandlerMethodArgumentResolver`

---

### Example 3: Service findById Pattern

**Before:**
```java
ProjectEntity project = projectRepository.findById(projectId)
        .orElseThrow(() -> new BusinessException("Project not found with id: " + projectId));
```

**After (với repository default method):**
```java
ProjectEntity project = projectRepository.findOrThrow(projectId, "Project");
```

**How:** Trong base repository hoặc utility class:
```java
default T findOrThrow(ID id, String entityName) {
    return findById(id).orElseThrow(() ->
        new BusinessException(entityName + " not found with id: " + id));
}
```

---

## Frontend Examples

### Example 4: Service Method Shorthand

**Before (hiện tại):**
```typescript
export const projectService = {
  async getProjectDetail(projectId: number): Promise<ApiResponse<Project>> {
    const response = await http.get<ApiResponse<Project>>(`/v1/projects/${projectId}`);
    return response.data;
  },
  async createProject(payload: CreateProjectRequest): Promise<ApiResponse<Project>> {
    const response = await http.post<ApiResponse<Project>>("/v1/projects", payload);
    return response.data;
  },
  // ... 10 more methods like this
};
```

**After (với helper wrapper):**
```typescript
// lib/http.ts - thêm typed helpers
const api = {
  get: <T>(url: string, params?: object) =>
    http.get<ApiResponse<T>>(url, { params }).then(r => r.data),
  post: <T>(url: string, data?: unknown) =>
    http.post<ApiResponse<T>>(url, data).then(r => r.data),
  put: <T>(url: string, data?: unknown) =>
    http.put<ApiResponse<T>>(url, data).then(r => r.data),
  patch: <T>(url: string, data?: unknown) =>
    http.patch<ApiResponse<T>>(url, data).then(r => r.data),
  del: <T>(url: string) =>
    http.delete<ApiResponse<T>>(url).then(r => r.data),
};

// project.service.ts - sử dụng api helper
export const projectService = {
  getProjectDetail: (projectId: number) =>
    api.get<Project>(`/v1/projects/${projectId}`),
  createProject: (payload: CreateProjectRequest) =>
    api.post<Project>("/v1/projects", payload),
  // ... much shorter
};
```

**Savings:** ~3 lines → 2 lines per method × 50+ methods = ~50 lines saved

---

### Example 5: Extract Large Page Sub-Components

**Before:** `AiChatPage.tsx` — 3352 lines, single file

**After:** Extract into:
```
pages/
  AiChatPage.tsx              (~800 lines - main orchestrator)
  ai-chat/
    ChatMessageList.tsx        (~400 lines)
    ChatInputArea.tsx          (~300 lines)
    ChatSidebar.tsx            (~200 lines)
    ToolCallRenderer.tsx       (~200 lines)
    FormRenderer.tsx           (~300 lines)
    ConfirmationDialog.tsx     (~150 lines)
    useChatStream.ts           (~400 lines - hook)
    chat-utils.ts              (~100 lines)
```

**Note:** Tổng LOC có thể tương đương hoặc giảm nhẹ, nhưng mỗi file ngắn hơn nhiều → dễ đọc, maintain.

---

### Example 6: Ponytail Philosophy Application

Theo triết lý Ponytail "Lazy Senior Developer":
- **YAGNI**: Không tạo generic factory nếu chỉ có 2-3 instances
- **Use platform**: Dùng Java records thay Lombok DTO khi có thể
- **Minimal**: Chọn cách refactor đơn giản nhất có hiệu quả
- **Tag shortcuts**: `// ponytail: simplified from verbose ApiResponse wrapping`

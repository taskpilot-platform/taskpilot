package com.taskpilot.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskpilot.ai.tools.ToolExecutionContext;
import com.taskpilot.contracts.aiquery.dto.*;
import com.taskpilot.contracts.aiquery.port.out.*;
import com.taskpilot.contracts.skill.port.out.SkillPort;
import com.taskpilot.contracts.skill.dto.SkillDto;
import com.taskpilot.contracts.user.port.out.UserNotificationQueryPort;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmartQueryService {

    private final ProjectInsightsPort projectInsightsPort;
    private final TaskCommandPort taskCommandPort;
    private final MemberAnalyticsPort memberAnalyticsPort;
    private final SprintQueryPort sprintQueryPort;
    private final TaskCommentQueryPort taskCommentQueryPort;
    private final SkillPort skillPort;
    private final UserNotificationQueryPort userNotificationQueryPort;

    private static final ExecutorService CHAIN_EXECUTOR = Executors.newFixedThreadPool(4);
    private static final ObjectMapper OBJECT_MAPPER = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .build();

    public SmartQueryResponseDto execute(SmartQueryRequestDto request, Long userId) {
        long start = System.currentTimeMillis();
        List<SmartQueryRequestDto.QueryChain> chains = request.chains();

        if (chains == null || chains.isEmpty()) {
            return new SmartQueryResponseDto(Map.of(), Map.of(), List.of(), System.currentTimeMillis() - start);
        }

        if (chains.size() == 1) {
            // Fast path: chỉ có 1 chuỗi, chạy tuần tự trên thread hiện tại để tối ưu overhead
            ChainResult cr = executeChain(0, chains.get(0).steps(), userId);
            Map<String, Object> results = cr.results();
            Map<String, String> errors = cr.errors();
            SmartQueryResponseDto.ChainStatus status = new SmartQueryResponseDto.ChainStatus(
                    cr.chainIndex(),
                    cr.totalSteps(),
                    cr.completedSteps(),
                    cr.durationMs()
            );
            return new SmartQueryResponseDto(results, errors, List.of(status), System.currentTimeMillis() - start);
        }

        // Multi-chain: chạy song song qua thread pool
        final ToolExecutionContext.Context context = ToolExecutionContext.get();
        List<CompletableFuture<ChainResult>> futures = new ArrayList<>();

        for (int i = 0; i < chains.size(); i++) {
            final int chainIdx = i;
            final List<SmartQueryRequestDto.QueryStep> chain = chains.get(i).steps();

            CompletableFuture<ChainResult> future = CompletableFuture.supplyAsync(() -> {
                if (context != null) {
                    ToolExecutionContext.set(context);
                }
                try {
                    return executeChain(chainIdx, chain, userId);
                } finally {
                    ToolExecutionContext.clear();
                }
            }, CHAIN_EXECUTOR);

            futures.add(future);
        }

        try {
            // Chờ tối đa 10 giây cho toàn bộ các luồng hoàn thành
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(10, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            log.warn("Parallel chains execution hit timeout or exception", e);
        }

        return mergeResults(futures, start);
    }

    private ChainResult executeChain(int chainIndex, List<SmartQueryRequestDto.QueryStep> steps, Long userId) {
        long chainStart = System.currentTimeMillis();
        Map<String, Object> chainResults = new LinkedHashMap<>();
        Map<String, String> chainErrors = new LinkedHashMap<>();
        int completed = 0;

        for (SmartQueryRequestDto.QueryStep step : steps) {
            String stepKey = step.key();
            if (stepKey == null || stepKey.isBlank()) {
                stepKey = "step_" + (completed + 1);
            }
            try {
                // 1. Giải quyết các tham chiếu chéo (ref resolution)
                ResolvedRefs refs = resolveRefs(step.ref(), chainResults);

                // 2. Định tuyến truy vấn (entity dispatch)
                Object result = dispatchQuery(step, refs, userId);

                // 3. Áp dụng sắp xếp và giới hạn (sort & limit)
                if (result instanceof List) {
                    result = applySortAndLimit((List<?>) result, step.sort(), step.limit());
                }

                chainResults.put(stepKey, result != null ? result : List.of());
                completed++;
            } catch (Exception e) {
                log.error("Error executing step key={} in chain {}: {}", stepKey, chainIndex, e.getMessage(), e);
                chainErrors.put(stepKey, e.getMessage());
                // Fail-fast: Nếu một bước trong chuỗi bị lỗi, dừng ngay chuỗi đó
                break;
            }
        }

        return new ChainResult(chainIndex, chainResults, chainErrors,
                steps.size(), completed, System.currentTimeMillis() - chainStart);
    }

    private Object dispatchQuery(SmartQueryRequestDto.QueryStep step, ResolvedRefs refs, Long userId) {
        if (step.entity() == null) {
            throw new IllegalArgumentException("Entity type is required in query step");
        }
        return switch (step.entity().toLowerCase()) {
            case "projects"      -> resolveProjects(step, userId);
            case "tasks"         -> resolveTasks(step, refs.projectId(), refs.sprintId(), userId);
            case "members"       -> resolveMembers(step, refs.projectId(), userId);
            case "sprints"       -> resolveSprints(step, refs.projectId(), userId);
            case "comments"      -> resolveComments(step, refs.taskId(), userId);
            case "workload"      -> resolveWorkload(step, refs.projectId(), userId);
            case "notifications" -> resolveNotifications(step, userId);
            case "skills"        -> resolveSkills(step, userId);
            default -> throw new IllegalArgumentException("Unknown entity: " + step.entity());
        };
    }

    private List<ProjectOverviewDto> resolveProjects(SmartQueryRequestDto.QueryStep step, Long userId) {
        List<ProjectOverviewDto> all = projectInsightsPort.getMyProjects(userId);
        if (all == null) return List.of();

        String aggregate = step.aggregate();
        if (aggregate != null && !aggregate.isBlank()) {
            switch (aggregate) {
                case "$latest":
                    return all.stream()
                            .max(Comparator.comparing(ProjectOverviewDto::joinedAt, Comparator.nullsLast(String::compareTo)))
                            .map(List::of)
                            .orElse(List.of());
                case "$mostMembers":
                    Map<Long, Integer> memberCounts = new ConcurrentHashMap<>();
                    List<CompletableFuture<Void>> memberFutures = all.stream()
                            .map(p -> CompletableFuture.runAsync(() -> {
                                try {
                                    int count = projectInsightsPort.getProjectMembers(p.projectId(), userId).size();
                                    memberCounts.put(p.projectId(), count);
                                } catch (Exception e) {
                                    memberCounts.put(p.projectId(), 0);
                                }
                            }, CHAIN_EXECUTOR))
                            .toList();
                    try {
                        CompletableFuture.allOf(memberFutures.toArray(new CompletableFuture[0]))
                                .orTimeout(5, TimeUnit.SECONDS)
                                .join();
                    } catch (Exception e) {
                        log.warn("Timeout counting project members for $mostMembers aggregate", e);
                    }
                    return all.stream()
                            .max(Comparator.comparingInt(p -> memberCounts.getOrDefault(p.projectId(), 0)))
                            .map(List::of)
                            .orElse(List.of());
                case "$mostTasks":
                    Map<Long, Integer> taskCounts = new ConcurrentHashMap<>();
                    List<CompletableFuture<Void>> taskFutures = all.stream()
                            .map(p -> CompletableFuture.runAsync(() -> {
                                try {
                                    int count = taskCommandPort.getTasksByProject(p.projectId(), userId).size();
                                    taskCounts.put(p.projectId(), count);
                                } catch (Exception e) {
                                    taskCounts.put(p.projectId(), 0);
                                }
                            }, CHAIN_EXECUTOR))
                            .toList();
                    try {
                        CompletableFuture.allOf(taskFutures.toArray(new CompletableFuture[0]))
                                .orTimeout(5, TimeUnit.SECONDS)
                                .join();
                    } catch (Exception e) {
                        log.warn("Timeout counting project tasks for $mostTasks aggregate", e);
                    }
                    return all.stream()
                            .max(Comparator.comparingInt(p -> taskCounts.getOrDefault(p.projectId(), 0)))
                            .map(List::of)
                            .orElse(List.of());
                default:
                    throw new IllegalArgumentException("Unknown project aggregate: " + aggregate);
            }
        }

        // Apply filters
        Map<String, String> filters = step.filters();
        if (filters != null) {
            String status = filters.get("status");
            String role = filters.get("role");
            String searchTerm = filters.get("searchTerm");

            return all.stream()
                    .filter(p -> status == null || status.isBlank() || status.equalsIgnoreCase(p.status()))
                    .filter(p -> role == null || role.isBlank() || role.equalsIgnoreCase(p.role()))
                    .filter(p -> searchTerm == null || searchTerm.isBlank() ||
                            (p.name() != null && p.name().toLowerCase().contains(searchTerm.toLowerCase())) ||
                            (p.description() != null && p.description().toLowerCase().contains(searchTerm.toLowerCase())))
                    .collect(Collectors.toList());
        }

        return all;
    }

    private List<TaskSummaryDto> resolveTasks(SmartQueryRequestDto.QueryStep step, Long projectId, Long sprintId, Long userId) {
        // Lấy projectId từ ref hoặc filters
        Long targetProjectId = projectId;

        if (targetProjectId == null && step.filters() != null) {
            String taskIdStr = step.filters().get("taskId");
            if (taskIdStr == null || taskIdStr.isBlank()) {
                taskIdStr = step.filters().get("id");
            }
            if (taskIdStr != null && !taskIdStr.isBlank()) {
                try {
                    Long taskId = Long.parseLong(taskIdStr.trim());
                    com.taskpilot.contracts.aiquery.dto.TaskDetailDto task = taskCommandPort.getTaskDetails(taskId, userId);
                    if (task != null) {
                        targetProjectId = task.projectId();
                    }
                } catch (Exception ignored) {}
            }
        }

        if (targetProjectId == null && step.filters() != null) {
            String pIdStr = step.filters().get("projectId");
            if (pIdStr != null && !pIdStr.isBlank()) {
                try {
                    targetProjectId = Long.parseLong(pIdStr);
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }

        if (targetProjectId == null) {
            List<ProjectOverviewDto> userProjects = projectInsightsPort.getMyProjects(userId);
            if (userProjects != null && !userProjects.isEmpty()) {
                targetProjectId = userProjects.get(0).projectId();
            }
        }

        if (targetProjectId == null) {
            throw new IllegalArgumentException("projectId is required to query tasks");
        }

        List<TaskSummaryDto> all = taskCommandPort.getTasksByProject(targetProjectId, userId);
        if (all == null) return List.of();

        // Apply filters
        Map<String, String> filters = step.filters();
        java.util.stream.Stream<TaskSummaryDto> stream = all.stream();

        // Lọc theo sprintId nếu được chỉ định
        if (sprintId != null) {
            stream = stream.filter(t -> sprintId.equals(t.sprintId()));
        }

        if (filters != null) {
            String sprintIdStr = filters.get("sprintId");
            if (sprintId == null && sprintIdStr != null && !sprintIdStr.isBlank()) {
                try {
                    Long targetSprintId = Long.parseLong(sprintIdStr);
                    stream = stream.filter(t -> targetSprintId.equals(t.sprintId()));
                } catch (NumberFormatException e) {
                    // ignore
                }
            }

            String status = filters.get("status");
            String assigneeIdStr = filters.get("assigneeId");
            String unassignedOnly = filters.get("unassignedOnly");
            String dueToday = filters.get("dueToday");
            String isOverdue = filters.get("isOverdue");
            String searchTerm = filters.get("searchTerm");

            if (status != null && !status.isBlank()) {
                stream = stream.filter(t -> status.equalsIgnoreCase(t.status()));
            }

            if ("true".equalsIgnoreCase(unassignedOnly)) {
                stream = stream.filter(t -> t.assigneeId() == null);
            } else if (assigneeIdStr != null && !assigneeIdStr.isBlank()) {
                if ("me".equalsIgnoreCase(assigneeIdStr)) {
                    stream = stream.filter(t -> userId.equals(t.assigneeId()));
                } else {
                    try {
                        Long targetAssigneeId = Long.parseLong(assigneeIdStr);
                        stream = stream.filter(t -> targetAssigneeId.equals(t.assigneeId()));
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                }
            }

            if ("true".equalsIgnoreCase(dueToday)) {
                String todayStr = java.time.LocalDate.now().toString();
                stream = stream.filter(t -> t.dueDate() != null && t.dueDate().startsWith(todayStr));
            }

            if ("true".equalsIgnoreCase(isOverdue)) {
                String todayStr = java.time.LocalDate.now().toString();
                stream = stream.filter(t -> t.dueDate() != null && t.dueDate().compareTo(todayStr) < 0 
                        && !"COMPLETED".equalsIgnoreCase(t.status()) && !"DONE".equalsIgnoreCase(t.status()));
            }

            if (searchTerm != null && !searchTerm.isBlank()) {
                stream = stream.filter(t -> (t.title() != null && t.title().toLowerCase().contains(searchTerm.toLowerCase())) ||
                        (t.description() != null && t.description().toLowerCase().contains(searchTerm.toLowerCase())));
            }
        }

        return stream.collect(Collectors.toList());
    }

    private List<ProjectMemberDto> resolveMembers(SmartQueryRequestDto.QueryStep step, Long projectId, Long userId) {
        Long targetProjectId = projectId;
        if (targetProjectId == null && step.filters() != null) {
            String pIdStr = step.filters().get("projectId");
            if (pIdStr != null && !pIdStr.isBlank()) {
                try {
                    targetProjectId = Long.parseLong(pIdStr);
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }

        if (targetProjectId == null) {
            List<ProjectOverviewDto> userProjects = projectInsightsPort.getMyProjects(userId);
            if (userProjects != null && !userProjects.isEmpty()) {
                targetProjectId = userProjects.get(0).projectId();
            }
        }

        if (targetProjectId == null) {
            throw new IllegalArgumentException("projectId is required to query members");
        }

        List<ProjectMemberDto> all = projectInsightsPort.getProjectMembers(targetProjectId, userId);
        if (all == null) return List.of();

        Map<String, String> filters = step.filters();
        if (filters != null) {
            String role = filters.get("role");
            String searchTerm = filters.get("searchTerm");

            return all.stream()
                    .filter(m -> role == null || role.isBlank() || role.equalsIgnoreCase(m.role()))
                    .filter(m -> searchTerm == null || searchTerm.isBlank() ||
                            (m.fullName() != null && m.fullName().toLowerCase().contains(searchTerm.toLowerCase())))
                    .collect(Collectors.toList());
        }
        return all;
    }

    private List<SprintSummaryDto> resolveSprints(SmartQueryRequestDto.QueryStep step, Long projectId, Long userId) {
        Long targetProjectId = projectId;
        if (targetProjectId == null && step.filters() != null) {
            String pIdStr = step.filters().get("projectId");
            if (pIdStr != null && !pIdStr.isBlank()) {
                try {
                    targetProjectId = Long.parseLong(pIdStr);
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }

        if (targetProjectId == null) {
            List<ProjectOverviewDto> userProjects = projectInsightsPort.getMyProjects(userId);
            if (userProjects != null && !userProjects.isEmpty()) {
                targetProjectId = userProjects.get(0).projectId();
            }
        }

        if (targetProjectId == null) {
            throw new IllegalArgumentException("projectId is required to query sprints");
        }

        List<SprintSummaryDto> all = sprintQueryPort.getSprintsByProject(targetProjectId, userId);
        if (all == null) return List.of();

        Map<String, String> filters = step.filters();
        if (filters != null) {
            String status = filters.get("status");

            return all.stream()
                    .filter(s -> status == null || status.isBlank() || status.equalsIgnoreCase(s.status()))
                    .collect(Collectors.toList());
        }
        return all;
    }

    private List<?> resolveComments(SmartQueryRequestDto.QueryStep step, Long taskId, Long userId) {
        Long targetTaskId = taskId;
        if (targetTaskId == null && step.filters() != null) {
            String tIdStr = step.filters().get("taskId");
            if (tIdStr != null && !tIdStr.isBlank()) {
                try {
                    targetTaskId = Long.parseLong(tIdStr);
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }

        if (targetTaskId == null) {
            return taskCommentQueryPort.getMyTaskComments(null, null, false, 20, userId);
        }
        return taskCommentQueryPort.getTaskComments(targetTaskId, userId);
    }

    private List<MemberWorkloadDto> resolveWorkload(SmartQueryRequestDto.QueryStep step, Long projectId, Long userId) {
        Long targetProjectId = projectId;
        if (targetProjectId == null && step.filters() != null) {
            String pIdStr = step.filters().get("projectId");
            if (pIdStr != null && !pIdStr.isBlank()) {
                try {
                    targetProjectId = Long.parseLong(pIdStr);
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }

        if (targetProjectId == null) {
            List<ProjectOverviewDto> userProjects = projectInsightsPort.getMyProjects(userId);
            if (userProjects != null && !userProjects.isEmpty()) {
                targetProjectId = userProjects.get(0).projectId();
            }
        }

        if (targetProjectId == null) {
            throw new IllegalArgumentException("projectId is required to query workloads");
        }
        return memberAnalyticsPort.getMemberWorkloadForProject(targetProjectId, userId);
    }

    private Object resolveNotifications(SmartQueryRequestDto.QueryStep step, Long userId) {
        Map<String, String> filters = step.filters();
        boolean unreadOnly = true;
        int limit = 10;

        if (filters != null) {
            String unreadOnlyStr = filters.get("unreadOnly");
            if (unreadOnlyStr != null) {
                unreadOnly = Boolean.parseBoolean(unreadOnlyStr);
            }
            String limitStr = filters.get("limit");
            if (limitStr != null) {
                try {
                    limit = Integer.parseInt(limitStr);
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }
        if (step.limit() != null) {
            limit = step.limit();
        }

        return userNotificationQueryPort.getMyNotifications(userId, unreadOnly, limit);
    }

    private List<SkillDto> resolveSkills(SmartQueryRequestDto.QueryStep step, Long userId) {
        String keyword = "";
        if (step.filters() != null) {
            String kw = step.filters().get("keyword");
            if (kw != null) {
                keyword = kw;
            }
        }
        return skillPort.search(keyword);
    }

    // --- Core helper: Ref Resolution ---
    private ResolvedRefs resolveRefs(Map<String, String> refMap, Map<String, Object> chainResults) {
        if (refMap == null || refMap.isEmpty()) {
            return new ResolvedRefs(null, null, null);
        }

        Long projectId = resolveRefId("projectId", refMap.get("projectId"), chainResults);
        Long sprintId = resolveRefId("sprintId", refMap.get("sprintId"), chainResults);
        Long taskId = resolveRefId("taskId", refMap.get("taskId"), chainResults);

        return new ResolvedRefs(projectId, sprintId, taskId);
    }

    private Long resolveRefId(String refKey, String sourceStepKey, Map<String, Object> chainResults) {
        if (sourceStepKey == null) return null;
        Object sourceObj = chainResults.get(sourceStepKey);
        if (sourceObj == null) return null;

        if (sourceObj instanceof List) {
            List<?> list = (List<?>) sourceObj;
            if (list.isEmpty()) return null;
            sourceObj = list.get(0);
        }

        Object value = getFieldValue(sourceObj, refKey);
        if (value == null) {
            value = getFieldValue(sourceObj, "id");
        }
        if (value == null) {
            if ("memberId".equals(refKey)) {
                value = getFieldValue(sourceObj, "id");
            } else if ("id".equals(refKey)) {
                value = getFieldValue(sourceObj, "memberId");
            }
        }

        if (value == null) return null;

        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Object getFieldValue(Object obj, String fieldName) {
        if (obj == null) return null;
        if (obj instanceof Map) {
            return ((Map<?, ?>) obj).get(fieldName);
        }
        if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            if (list.isEmpty()) return null;
            return getFieldValue(list.get(0), fieldName);
        }

        try {
            if (obj.getClass().isRecord()) {
                for (var rc : obj.getClass().getRecordComponents()) {
                    if (rc.getName().equals(fieldName)) {
                        return rc.getAccessor().invoke(obj);
                    }
                }
            }
            // Thử tìm direct getter: fieldName()
            try {
                Method method = obj.getClass().getMethod(fieldName);
                return method.invoke(obj);
            } catch (NoSuchMethodException e) {
                // Thử getFieldName()
                String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
                try {
                    Method method = obj.getClass().getMethod(getterName);
                    return method.invoke(obj);
                } catch (NoSuchMethodException ex) {
                    // Thử truy cập field trực tiếp
                    var field = obj.getClass().getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field.get(obj);
                }
            }
        } catch (Exception e) {
            // Fallback: sử dụng ObjectMapper để chuyển đổi
            try {
                Map<?, ?> map = OBJECT_MAPPER.convertValue(obj, Map.class);
                return map.get(fieldName);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    // --- Generic Sort and Limit ---
    private List<?> applySortAndLimit(List<?> list, String sort, Integer limit) {
        if (list == null || list.isEmpty()) return list;
        List<Object> mutableList = new ArrayList<>(list);

        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.trim().split("\\s+");
            String field = parts[0];
            boolean asc = parts.length < 2 || !"desc".equalsIgnoreCase(parts[1]);

            mutableList.sort((o1, o2) -> {
                Object v1 = getFieldValue(o1, field);
                Object v2 = getFieldValue(o2, field);
                if (v1 == null && v2 == null) return 0;
                if (v1 == null) return asc ? -1 : 1;
                if (v2 == null) return asc ? 1 : -1;

                int cmp;
                if (v1 instanceof Comparable && v2 instanceof Comparable && v1.getClass().isAssignableFrom(v2.getClass())) {
                    cmp = ((Comparable<Object>) v1).compareTo(v2);
                } else {
                    cmp = v1.toString().compareToIgnoreCase(v2.toString());
                }
                return asc ? cmp : -cmp;
            });
        }

        int limitVal = limit != null ? Math.max(1, Math.min(limit, 50)) : 10;
        if (mutableList.size() > limitVal) {
            return mutableList.subList(0, limitVal);
        }
        return mutableList;
    }

    private SmartQueryResponseDto mergeResults(List<CompletableFuture<ChainResult>> futures, long startTime) {
        Map<String, Object> results = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        List<SmartQueryResponseDto.ChainStatus> statuses = new ArrayList<>();

        for (var future : futures) {
            try {
                ChainResult cr = future.join();
                results.putAll(cr.results());
                errors.putAll(cr.errors());
                statuses.add(new SmartQueryResponseDto.ChainStatus(
                        cr.chainIndex(),
                        cr.totalSteps(),
                        cr.completedSteps(),
                        cr.durationMs()
                ));
            } catch (Exception e) {
                log.error("Chain execution failed unexpectedly", e);
            }
        }

        long totalDurationMs = System.currentTimeMillis() - startTime;
        return new SmartQueryResponseDto(results, errors, statuses, totalDurationMs);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down SmartQueryService thread pool...");
        CHAIN_EXECUTOR.shutdown();
        try {
            if (!CHAIN_EXECUTOR.awaitTermination(3, TimeUnit.SECONDS)) {
                CHAIN_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            CHAIN_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record ChainResult(
            int chainIndex,
            Map<String, Object> results,
            Map<String, String> errors,
            int totalSteps,
            int completedSteps,
            long durationMs
    ) {}

    private record ResolvedRefs(
            Long projectId,
            Long sprintId,
            Long taskId
    ) {}
}

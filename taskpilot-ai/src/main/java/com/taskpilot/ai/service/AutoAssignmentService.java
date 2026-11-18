package com.taskpilot.ai.service;

import com.taskpilot.ai.assignment.port.out.AiAuditPort;
import com.taskpilot.ai.dto.AutoAssignmentResponse;
import com.taskpilot.ai.dto.CandidateScore;
import com.taskpilot.ai.entity.AiLogEntity;
import com.taskpilot.ai.heuristic.HeuristicStrategy;
import com.taskpilot.ai.heuristic.HeuristicNormalization;
import com.taskpilot.contracts.assignment.dto.TaskAssignmentMetrics;
import com.taskpilot.contracts.assignment.port.out.TaskPerformanceReadPort;
import com.taskpilot.ai.heuristic.HeuristicStrategyFactory;
import com.taskpilot.ai.heuristic.NormalizedScores;
import com.taskpilot.contracts.assignment.dto.ProjectMemberDto;
import com.taskpilot.contracts.assignment.dto.UserProfileDto;
import com.taskpilot.contracts.assignment.dto.UserSkillDto;
import com.taskpilot.contracts.assignment.port.out.ProjectMemberPort;
import com.taskpilot.contracts.assignment.port.out.ProjectPort;
import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.contracts.assignment.port.out.UserSkillPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

@Slf4j
@Service
public class AutoAssignmentService {
    public static final long DEFAULT_EXPLANATION_TIMEOUT_MS = 5000L;
    public static final String DEFAULT_FALLBACK_EXPLANATION =
            "Ranking uses declared skills, active tasks in this project, and independently reviewed work. Quality uses a neutral value when no reviewed evidence is available.";

    private final ProjectMemberPort projectMemberPort;
    private final TaskPerformanceReadPort taskPerformanceReadPort;
    private final UserSkillPort userSkillPort;
    private final AiAuditPort aiAuditPort;
    private final UserPort userPort;
    private final ProjectPort projectPort;
    private final HeuristicStrategyFactory heuristicStrategyFactory;
    private final StreamingChatModel explanationModel;
    private final long explanationTimeoutMs;
    private final String explanationModelName;

    @Autowired
    public AutoAssignmentService(
            ProjectMemberPort projectMemberPort,
            TaskPerformanceReadPort taskPerformanceReadPort,
            UserSkillPort userSkillPort,
            AiAuditPort aiAuditPort,
            UserPort userPort,
            ProjectPort projectPort,
            HeuristicStrategyFactory heuristicStrategyFactory,
            @Qualifier("geminiFlashModel") StreamingChatModel explanationModel,
            @Value("${ai.assignment.explanation-timeout-ms:5000}") long explanationTimeoutMs,
            @Value("${ai.gemini.model-name:gemini-3.5-flash}") String explanationModelName) {
        this.projectMemberPort = projectMemberPort;
        this.taskPerformanceReadPort = taskPerformanceReadPort;
        this.userSkillPort = userSkillPort;
        this.aiAuditPort = aiAuditPort;
        this.userPort = userPort;
        this.projectPort = projectPort;
        this.heuristicStrategyFactory = heuristicStrategyFactory;
        this.explanationModel = explanationModel;
        this.explanationTimeoutMs = explanationTimeoutMs;
        this.explanationModelName = (explanationModelName != null && !explanationModelName.isBlank())
                ? explanationModelName : "gemini-3.5-flash";
    }

    public AutoAssignmentService(
            ProjectMemberPort projectMemberPort,
            TaskPerformanceReadPort taskPerformanceReadPort,
            UserSkillPort userSkillPort,
            AiAuditPort aiAuditPort,
            UserPort userPort,
            ProjectPort projectPort,
            HeuristicStrategyFactory heuristicStrategyFactory,
            StreamingChatModel explanationModel) {
        this(projectMemberPort, taskPerformanceReadPort, userSkillPort, aiAuditPort, userPort, projectPort,
                heuristicStrategyFactory, explanationModel, DEFAULT_EXPLANATION_TIMEOUT_MS, "gemini-3.5-flash");
    }

    public AutoAssignmentService(
            ProjectMemberPort projectMemberPort,
            TaskPerformanceReadPort taskPerformanceReadPort,
            UserSkillPort userSkillPort,
            AiAuditPort aiAuditPort,
            UserPort userPort,
            ProjectPort projectPort,
            HeuristicStrategyFactory heuristicStrategyFactory,
            StreamingChatModel explanationModel,
            long explanationTimeoutMs) {
        this(projectMemberPort, taskPerformanceReadPort, userSkillPort, aiAuditPort, userPort, projectPort,
                heuristicStrategyFactory, explanationModel, explanationTimeoutMs, "gemini-3.5-flash");
    }

    public AutoAssignmentResponse recommend(Long projectId, List<String> requiredSkills,
            int taskDifficulty, Long requestingUserId) {
        AutoAssignmentResponse base = recommendCandidates(projectId, requiredSkills, taskDifficulty, requestingUserId);
        if (base.candidates().isEmpty()) {
            return base;
        }
        String explanation = generateExplanation(base.candidates().stream().limit(3).toList(),
                base.requiredSkills(), taskDifficulty);
        saveAutoAssignLog(requestingUserId, projectId, base.requiredSkills(), base.candidates(), explanation);
        return base.toBuilder().aiExplanation(explanation).build();
    }

    public AutoAssignmentResponse recommendCandidates(Long projectId, List<String> requiredSkills,
            int taskDifficulty, Long requestingUserId) {
        return recommendCandidates(projectId, requiredSkills, taskDifficulty, requestingUserId, Set.of(), Set.of());
    }

    public AutoAssignmentResponse recommendCandidates(Long projectId, List<String> requiredSkills,
            int taskDifficulty, Long requestingUserId, Set<Long> includeUserIds, Set<Long> excludeUserIds) {
        if (requestingUserId == null || !projectMemberPort.isProjectMember(projectId, requestingUserId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(), "You are not a member of this project");
        }
        List<String> skills = requiredSkills == null ? List.of() : requiredSkills.stream()
                .filter(Objects::nonNull).map(String::trim).filter(skill -> !skill.isEmpty())
                .map(skill -> skill.toLowerCase(Locale.ROOT)).distinct().toList();
        String mode = resolveHeuristicMode(projectId);
        HeuristicStrategy strategy = heuristicStrategyFactory.resolve(mode);
        validateNormalization(strategy, mode);

        Set<Long> included = includeUserIds == null ? Set.of() : includeUserIds;
        Set<Long> excluded = excludeUserIds == null ? Set.of() : excludeUserIds;
        Set<Long> candidateIds = projectMemberPort.findProjectMembers(projectId).stream()
                .map(ProjectMemberDto::userId)
                .filter(id -> included.isEmpty() || included.contains(id))
                .filter(id -> !excluded.contains(id))
                .collect(Collectors.toSet());
        Map<Long, TaskAssignmentMetrics> metrics = taskPerformanceReadPort.readProjectMetrics(projectId, candidateIds);

        List<RankedCandidate> ranked = metrics.values().stream()
                .flatMap(metric -> userPort.findById(metric.userId()).stream()
                        .filter(user -> user.activeAccount() && !isUnavailable(user.status()))
                        .map(user -> scoreCandidate(user, metric, skills, strategy)))
                .sorted(Comparator.comparingDouble(RankedCandidate::score).reversed()
                        .thenComparing(candidate -> candidate.user().id()))
                .toList();
        List<CandidateScore> candidates = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            candidates.add(toView(ranked.get(i), i + 1, !skills.isEmpty()));
        }
        String differentiation = ranked.isEmpty() ? null
                : ranked.size() > 1 && Double.compare(ranked.get(0).score(), ranked.get(1).score()) == 0
                        ? "INSUFFICIENT_TO_DIFFERENTIATE" : "DIFFERENTIATED";

        return AutoAssignmentResponse.builder()
                .projectId(projectId).requiredSkills(skills).candidates(List.copyOf(candidates))
                .heuristicMode(mode).presentationContractVersion("allowlisted-view-v3")
                .scoringModelVersion(NormalizedScores.PROJECT_RANKING_VERSION).differentiationStatus(differentiation)
                .aiExplanation(ranked.isEmpty() ? "No eligible members are currently available for assignment." : null)
                .build();
    }

    private static void validateNormalization(HeuristicStrategy strategy, String mode) {
        var config = strategy.normalization();
        if (config.fit() != HeuristicNormalization.BENCHMARK_BENEFIT
                || config.load() != HeuristicNormalization.BENCHMARK_BENEFIT
                || config.performance() != HeuristicNormalization.BENCHMARK_BENEFIT) {
            throw new BusinessException(HttpStatus.CONFLICT.value(),
                    "Legacy normalization for " + mode + " requires review before "
                            + NormalizedScores.PROJECT_RANKING_VERSION);
        }
    }

    private RankedCandidate scoreCandidate(UserProfileDto user, TaskAssignmentMetrics metrics,
            List<String> requiredSkills, HeuristicStrategy strategy) {
        double fit = requiredSkills.isEmpty() ? 0.5
                : calculateFitScore(userSkillPort.findByUserIdWithSkill(user.id()), requiredSkills);
        var normalized = NormalizedScores.forProjectTasks(fit, metrics.workload().activeTaskCount(),
                metrics.performance().score());
        double score = strategy.score(normalized);
        return new RankedCandidate(user, metrics, fit, score);
    }

    private CandidateScore toView(RankedCandidate candidate, int rank, boolean hasRequirements) {
        var metrics = candidate.metrics();
        var performance = metrics.performance();
        return CandidateScore.builder()
                .userId(candidate.user().id()).fullName(candidate.user().fullName())
                .rank(rank).totalScore(candidate.score()).memberStatus(candidate.user().status())
                .presentationFitValue(hasRequirements ? candidate.fit() : null)
                .fitStatus(hasRequirements ? "MEASURED" : "MISSING_REQUIREMENTS")
                .fitSource(hasRequirements ? "DECLARED_PROFILE" : null)
                .storedWorkloadValue(metrics.workload().activeTaskCount()).workloadStatus("MEASURED")
                .workloadUnit(metrics.workload().unit()).workloadScope(metrics.workload().scope())
                .workloadMeasuredAt(metrics.asOf().toString())
                .performanceStatus(performance.status().name())
                .performanceSampleCount(performance.eligibleTaskCount())
                .performanceWindowLimit(performance.windowLimit()).observedQualityMean(performance.observedQualityMean())
                .evidenceWeight(performance.evidenceWeight())
                .build();
    }

    private double calculateFitScore(List<UserSkillDto> userSkills,
            List<String> requiredSkills) {
        Map<String, Integer> skillLevelMap = userSkills.stream()
                .collect(Collectors.toMap(us -> us.skillName().trim().toLowerCase(Locale.ROOT),
                        UserSkillDto::level, (a, b) -> a));
        int matched = 0;
        int totalLevel = 0;
        for (String required : requiredSkills) {
            String key = required;
            if (skillLevelMap.containsKey(key)) {
                matched++;
                totalLevel += Math.max(0, Math.min(5, skillLevelMap.get(key))); // level 1–5
            }
        }
        if (matched == 0)
            return 0.0;
        double matchRatio = (double) matched / requiredSkills.size();
        double avgLevelNormalized = (double) totalLevel / (matched * 5.0); // 5 is max level
        return (matchRatio * 0.6) + (avgLevelNormalized * 0.4);
    }

    private boolean isUnavailable(String status) {
        if (status == null) {
            return false;
        }
        return "DEACTIVATED".equalsIgnoreCase(status) || "OOO".equalsIgnoreCase(status);
    }

    private String resolveHeuristicMode(Long projectId) {
        return projectPort.findById(projectId)
                .map(config -> config.heuristicMode())
                .filter(mode -> mode != null && !mode.isBlank())
                .map(mode -> mode.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(),
                        "Project heuristic mode is not configured for project: " + projectId));
    }

    /**
     * Generates a natural language explanation for top candidate recommendations.
     * <p>
     * Timeout & Concurrency Semantics:
     * 1. Bounded Wait: Caller thread blocks on future.get(explanationTimeoutMs, TimeUnit.MILLISECONDS).
     * 2. Timeout Fallback: If timeout expires, future.cancel(true) is called to release the caller,
     *    and DEFAULT_FALLBACK_EXPLANATION is returned immediately.
     * 3. Socket Lifecycle Note: future.cancel(true) terminates the local future awaiting completion;
     *    the underlying LangChain4j HTTP socket connection may persist in the background until its socket-level
     *    read timeout expires.
     * 4. Interruption: If interrupted, thread interrupt status is restored and fallback is returned.
     * 5. Invariant: Heuristic candidate scoring occurs prior to this method and is completely decoupled from
     *    LLM availability.
     */
    private String generateExplanation(List<CandidateScore> top3, List<String> requiredSkills, int taskDifficulty) {
        try {
            StringBuilder candidateInfo = new StringBuilder();
            for (CandidateScore candidate : top3) {
                candidateInfo.append(String.format(Locale.ROOT,
                        "%d. %s | declared skill fit: %s (%s) | active project tasks: %d | quality evidence: %s, %d reviewed tasks, observed mean: %s%n",
                        candidate.getRank(), candidate.getFullName(), candidate.getPresentationFitValue(),
                        candidate.getFitStatus(), candidate.getStoredWorkloadValue(), candidate.getPerformanceStatus(),
                        candidate.getPerformanceSampleCount(), candidate.getObservedQualityMean()));
            }
            String prompt = """
                    Explain the supplied assignment ranking briefly. Use only the supplied metrics and keep rank order.
                    Declared skills are self-reported. DEFAULT quality means no measured quality advantage.
                    Task difficulty is descriptive only. Do not infer capacity, confidence or internal scores.
                    Required skills: %s
                    Task difficulty: %d/10
                    Candidates:
                    %s
                    """.formatted(String.join(", ", requiredSkills), taskDifficulty, candidateInfo);
            CompletableFuture<String> future = new CompletableFuture<>();
            StringBuilder fullResponse = new StringBuilder();
            explanationModel.chat(List.of(SystemMessage.from(
                    "You are a helpful project management assistant analyzing team assignment recommendations."),
                    UserMessage.from(prompt)), new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String partial) {
                            fullResponse.append(partial);
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse response) {
                            future.complete(fullResponse.isEmpty() ? DEFAULT_FALLBACK_EXPLANATION : fullResponse.toString());
                        }

                        @Override
                        public void onError(Throwable error) {
                            log.warn("[AutoAssign] LLM reasoning explanation failed: {}",
                                     error != null ? error.getMessage() : "Unknown error");
                            future.complete(DEFAULT_FALLBACK_EXPLANATION);
                        }
                    });
            try {
                return future.get(explanationTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                log.warn("[AutoAssign] LLM explanation timed out after {}ms, using deterministic fallback", explanationTimeoutMs);
                return DEFAULT_FALLBACK_EXPLANATION;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("[AutoAssign] LLM explanation interrupted, using deterministic fallback: {}", ie.getMessage());
                return DEFAULT_FALLBACK_EXPLANATION;
            } catch (ExecutionException ee) {
                log.warn("[AutoAssign] LLM explanation execution exception, using deterministic fallback: {}", ee.getMessage());
                return DEFAULT_FALLBACK_EXPLANATION;
            }
        } catch (Exception e) {
            log.warn("[AutoAssign] Failed to generate AI explanation, using deterministic fallback: {}", e.getMessage());
            return DEFAULT_FALLBACK_EXPLANATION;
        }
    }

    private void saveAutoAssignLog(Long userId, Long projectId, List<String> requiredSkills,
            List<CandidateScore> candidates, String explanation) {
        try {
            String actualModel = (explanation != null && !explanation.equals(DEFAULT_FALLBACK_EXPLANATION))
                    ? explanationModelName
                    : explanationModelName + " (fallback)";
            AiLogEntity log = AiLogEntity.builder().userId(userId).projectId(projectId)
                    .request("Auto-assignment request for skills: " + requiredSkills)
                    .response(explanation).actionTaken("autoAssignCandidates")
                    .toolOutput(candidates).humanFeedback("PENDING").modelUsed(actualModel)
                    .build();
            aiAuditPort.save(log);
        } catch (Exception e) {
            log.error("[AutoAssign] Failed to save audit log: {}", e.getMessage());
        }
    }

    private record RankedCandidate(UserProfileDto user, TaskAssignmentMetrics metrics, double fit, double score) {}
}

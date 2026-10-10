package com.taskpilot.ai.service;

import com.taskpilot.ai.assignment.port.out.AiAuditPort;
import com.taskpilot.ai.dto.AutoAssignmentResponse;
import com.taskpilot.ai.dto.CandidateScore;
import com.taskpilot.ai.dto.InternalCandidateRanking;
import com.taskpilot.ai.dto.MetricDataStatus;
import com.taskpilot.ai.dto.RecommendationDifferentiationStatus;
import com.taskpilot.ai.dto.RecommendationView;
import com.taskpilot.ai.dto.RecommendedCandidateView;
import com.taskpilot.ai.entity.AiLogEntity;
import com.taskpilot.ai.heuristic.HeuristicStrategy;
import com.taskpilot.ai.heuristic.HeuristicStrategyFactory;
import com.taskpilot.ai.heuristic.NormalizedScores;
import com.taskpilot.ai.heuristic.RawScores;
import com.taskpilot.ai.heuristic.ScoreRanges;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
            "Đề xuất dựa trên phân tích mức độ phù hợp kỹ năng và các tiêu chí phân công. Dữ liệu khối lượng công việc hiện chưa có chứng thực độc lập và hiệu suất ở mức mặc định ban đầu.";

    private final ProjectMemberPort projectMemberPort;
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
            UserSkillPort userSkillPort,
            AiAuditPort aiAuditPort,
            UserPort userPort,
            ProjectPort projectPort,
            HeuristicStrategyFactory heuristicStrategyFactory,
            @Qualifier("geminiFlashModel") StreamingChatModel explanationModel,
            @Value("${ai.assignment.explanation-timeout-ms:5000}") long explanationTimeoutMs,
            @Value("${ai.gemini.model-name:gemini-3.5-flash}") String explanationModelName) {
        this.projectMemberPort = projectMemberPort;
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
            UserSkillPort userSkillPort,
            AiAuditPort aiAuditPort,
            UserPort userPort,
            ProjectPort projectPort,
            HeuristicStrategyFactory heuristicStrategyFactory,
            StreamingChatModel explanationModel) {
        this(projectMemberPort, userSkillPort, aiAuditPort, userPort, projectPort,
                heuristicStrategyFactory, explanationModel, DEFAULT_EXPLANATION_TIMEOUT_MS, "gemini-3.5-flash");
    }

    public AutoAssignmentService(
            ProjectMemberPort projectMemberPort,
            UserSkillPort userSkillPort,
            AiAuditPort aiAuditPort,
            UserPort userPort,
            ProjectPort projectPort,
            HeuristicStrategyFactory heuristicStrategyFactory,
            StreamingChatModel explanationModel,
            long explanationTimeoutMs) {
        this(projectMemberPort, userSkillPort, aiAuditPort, userPort, projectPort,
                heuristicStrategyFactory, explanationModel, explanationTimeoutMs, "gemini-3.5-flash");
    }

    private static final int PERFORMANCE_WINDOW_SIZE = 3;
    private static final double NEUTRAL_PERFORMANCE_PRIOR = 0.5;
    private static final double[] DECAY_WEIGHTS = new double[] { 0.5, 0.3, 0.2 };

    @Transactional(readOnly = true)
    public AutoAssignmentResponse recommend(Long projectId, List<String> requiredSkills,
            int taskDifficulty, Long requestingUserId) {
        AutoAssignmentResponse base = recommendCandidates(projectId, requiredSkills, taskDifficulty, requestingUserId);

        if (base.candidates() == null || base.candidates().isEmpty()) {
            return base;
        }

        List<CandidateScore> top3 = base.candidates().stream().limit(3).toList();
        String explanation = generateExplanation(top3, base.requiredSkills(), taskDifficulty,
                requestingUserId, projectId);

        saveAutoAssignLog(requestingUserId, projectId, base.requiredSkills(), base.candidates(),
                explanation);

        return AutoAssignmentResponse.builder()
                .projectId(projectId)
                .requiredSkills(base.requiredSkills())
                .candidates(base.candidates())
                .aiExplanation(explanation)
                .build();
    }

    public void validateProjectMembership(Long projectId, Long userId) {
        if (projectId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Project ID is required");
        }
        if (userId == null || !projectMemberPort.isProjectMember(projectId, userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "User " + userId + " is not authorized to view recommendations for project " + projectId);
        }
    }

    public void validateProjectManager(Long projectId, Long userId) {
        if (projectId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "Project ID is required");
        }
        if (userId == null || !projectMemberPort.isProjectMember(projectId, userId)
                || !projectMemberPort.isProjectManager(projectId, userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN.value(),
                    "User " + userId + " is not authorized as MANAGER for project " + projectId);
        }
    }

    @Transactional(readOnly = true)
    public AutoAssignmentResponse recommendCandidates(Long projectId, List<String> requiredSkills,
            int taskDifficulty, Long requestingUserId) {
        return recommendCandidates(projectId, requiredSkills, taskDifficulty, requestingUserId, Set.of(), Set.of());
    }

    public AutoAssignmentResponse recommendCandidates(Long projectId, List<String> requiredSkills,
            int taskDifficulty, Long requestingUserId, Set<Long> includeUserIds, Set<Long> excludeUserIds) {
        List<String> safeRequiredSkills = requiredSkills == null ? Collections.emptyList() : requiredSkills;
        log.info("[AutoAssign] Starting candidate scoring for project {} with skills: {}", projectId,
                safeRequiredSkills);

        String mode = resolveHeuristicMode(projectId);
        HeuristicStrategy strategy = heuristicStrategyFactory.resolve(mode);
        List<ProjectMemberDto> members = projectMemberPort.findProjectMembers(projectId);
        Set<Long> safeIncludeUserIds = includeUserIds == null ? Set.of() : includeUserIds;
        Set<Long> safeExcludeUserIds = excludeUserIds == null ? Set.of() : excludeUserIds;
        if (!safeIncludeUserIds.isEmpty() || !safeExcludeUserIds.isEmpty()) {
            members = members.stream()
                    .filter(member -> safeIncludeUserIds.isEmpty() || safeIncludeUserIds.contains(member.userId()))
                    .filter(member -> !safeExcludeUserIds.contains(member.userId()))
                    .toList();
        }

        if (members.isEmpty()) {
            return AutoAssignmentResponse.builder().projectId(projectId)
                    .requiredSkills(safeRequiredSkills)
                    .candidates(Collections.emptyList())
                    .aiExplanation("No matching members found in this project.")
                    .build();
        }

        List<CandidateScore> scoredCandidates = computeCandidates(
                members, safeRequiredSkills, strategy, mode);

        if (scoredCandidates.isEmpty()) {
            return AutoAssignmentResponse.builder().projectId(projectId)
                    .requiredSkills(safeRequiredSkills)
                    .candidates(Collections.emptyList())
                    .aiExplanation("No eligible members are currently available for assignment.")
                    .build();
        }

        return AutoAssignmentResponse.builder().projectId(projectId)
                .requiredSkills(safeRequiredSkills)
                .candidates(scoredCandidates)
                .aiExplanation(null)
                .build();
    }

    @Transactional(readOnly = true)
    public RecommendationView recommendView(Long projectId, List<String> requiredSkills,
            int taskDifficulty, Long requestingUserId) {
        RecommendationView base = recommendCandidatesView(projectId, requiredSkills, taskDifficulty, requestingUserId, Set.of(), Set.of());
        if (base.candidates() == null || base.candidates().isEmpty()) {
            return base;
        }

        List<RecommendedCandidateView> top3 = base.candidates().stream().limit(3).toList();
        String explanation = generateExplanationForView(top3, base.requiredSkills(), taskDifficulty,
                base.differentiationStatus(), requestingUserId, projectId);

        saveAutoAssignLog(requestingUserId, projectId, base.requiredSkills(), base.candidates(), explanation);

        return RecommendationView.builder()
                .projectId(base.projectId())
                .requiredSkills(base.requiredSkills())
                .heuristicMode(base.heuristicMode())
                .differentiationStatus(base.differentiationStatus())
                .candidates(base.candidates())
                .aiExplanation(explanation)
                .presentationContractVersion(base.presentationContractVersion())
                .scoringModelVersion(base.scoringModelVersion())
                .build();
    }

    @Transactional(readOnly = true)
    public RecommendationView recommendCandidatesView(Long projectId, List<String> requiredSkills,
            int taskDifficulty, Long requestingUserId, Set<Long> includeUserIds, Set<Long> excludeUserIds) {
        validateProjectMembership(projectId, requestingUserId);
        List<String> safeRequiredSkills = requiredSkills == null ? Collections.emptyList() : requiredSkills;
        String mode = resolveHeuristicMode(projectId);
        HeuristicStrategy strategy = heuristicStrategyFactory.resolve(mode);
        List<ProjectMemberDto> members = projectMemberPort.findProjectMembers(projectId);
        Set<Long> safeIncludeUserIds = includeUserIds == null ? Set.of() : includeUserIds;
        Set<Long> safeExcludeUserIds = excludeUserIds == null ? Set.of() : excludeUserIds;
        if (!safeIncludeUserIds.isEmpty() || !safeExcludeUserIds.isEmpty()) {
            members = members.stream()
                    .filter(member -> safeIncludeUserIds.isEmpty() || safeIncludeUserIds.contains(member.userId()))
                    .filter(member -> !safeExcludeUserIds.contains(member.userId()))
                    .toList();
        }

        if (members.isEmpty()) {
            return RecommendationView.builder().projectId(projectId)
                    .requiredSkills(safeRequiredSkills)
                    .candidates(Collections.emptyList())
                    .differentiationStatus(RecommendationDifferentiationStatus.UNKNOWN)
                    .presentationContractVersion(RecommendationView.PRESENTATION_CONTRACT_VERSION)
                    .scoringModelVersion(RecommendationView.SCORING_MODEL_VERSION)
                    .heuristicMode(mode)
                    .aiExplanation("No matching members found in this project.")
                    .build();
        }

        List<InternalCandidateRanking> internalRankings = computeInternalCandidates(
                members, safeRequiredSkills, strategy, mode);

        if (internalRankings.isEmpty()) {
            return RecommendationView.builder().projectId(projectId)
                    .requiredSkills(safeRequiredSkills)
                    .candidates(Collections.emptyList())
                    .differentiationStatus(RecommendationDifferentiationStatus.UNKNOWN)
                    .presentationContractVersion(RecommendationView.PRESENTATION_CONTRACT_VERSION)
                    .scoringModelVersion(RecommendationView.SCORING_MODEL_VERSION)
                    .heuristicMode(mode)
                    .aiExplanation("No eligible members are currently available for assignment.")
                    .build();
        }

        RecommendationDifferentiationStatus differentiationStatus = evaluateDifferentiationStatus(internalRankings);

        List<RecommendedCandidateView> candidateViews = new ArrayList<>(internalRankings.size());
        int rank = 1;
        for (InternalCandidateRanking ic : internalRankings) {
            candidateViews.add(ic.toView(rank++));
        }

        return RecommendationView.builder().projectId(projectId)
                .requiredSkills(safeRequiredSkills)
                .candidates(candidateViews)
                .differentiationStatus(differentiationStatus)
                .presentationContractVersion(RecommendationView.PRESENTATION_CONTRACT_VERSION)
                .scoringModelVersion(RecommendationView.SCORING_MODEL_VERSION)
                .heuristicMode(mode)
                .aiExplanation(null)
                .build();
    }

    public static RecommendationDifferentiationStatus evaluateDifferentiationStatus(List<InternalCandidateRanking> internalRankings) {
        if (internalRankings == null || internalRankings.size() <= 1) {
            return RecommendationDifferentiationStatus.UNKNOWN;
        }
        boolean anyMissingMeasuredFit = internalRankings.stream()
                .anyMatch(c -> c.fitStatus() != MetricDataStatus.MEASURED);
        if (anyMissingMeasuredFit) {
            return RecommendationDifferentiationStatus.INSUFFICIENT_TO_DIFFERENTIATE;
        }
        double firstFit = internalRankings.get(0).rankingRawFit();
        boolean allFitEqual = internalRankings.stream()
                .allMatch(c -> Math.abs(c.rankingRawFit() - firstFit) < 1e-9);
        return allFitEqual
                ? RecommendationDifferentiationStatus.INSUFFICIENT_TO_DIFFERENTIATE
                : RecommendationDifferentiationStatus.DIFFERENTIATED;
    }

    public List<InternalCandidateRanking> computeInternalCandidates(
            List<ProjectMemberDto> members,
            List<String> requiredSkills,
            HeuristicStrategy strategy,
            String mode) {
        List<RawCandidate> rawCandidates = members.stream()
                .map(member -> userPort.findById(member.userId())
                        .map(user -> buildRawCandidate(user, member.performanceScore(), requiredSkills))
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();

        if (rawCandidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<RawScores> rawScores = rawCandidates.stream().map(RawCandidate::rawScores).toList();
        ScoreRanges ranges = ScoreRanges.from(rawScores);

        return rawCandidates.stream()
                .map(raw -> buildInternalCandidateRanking(raw, strategy, ranges, mode, requiredSkills))
                .sorted(InternalCandidateRanking.STEP_A_COMPARATOR)
                .collect(Collectors.toList());
    }

    private InternalCandidateRanking buildInternalCandidateRanking(
            RawCandidate raw,
            HeuristicStrategy strategy,
            ScoreRanges ranges,
            String mode,
            List<String> requiredSkills) {
        NormalizedScores normalized = strategy.normalizeNeutral(raw.rawScores(), ranges);
        double totalScore = strategy.score(normalized);
        long rankingKey = Math.round(totalScore * 1_000_000_000L);

        MetricDataStatus fitStatus;
        Double presentationFitValue;
        if (requiredSkills == null || requiredSkills.isEmpty()) {
            fitStatus = MetricDataStatus.INSUFFICIENT_DATA;
            presentationFitValue = null;
        } else if (!raw.hasUserSkills()) {
            fitStatus = MetricDataStatus.INSUFFICIENT_DATA;
            presentationFitValue = null;
        } else {
            fitStatus = MetricDataStatus.MEASURED;
            presentationFitValue = raw.rawScores().fit();
        }

        return InternalCandidateRanking.builder()
                .userId(raw.user().id())
                .fullName(raw.user().fullName())
                .email(raw.user().email())
                .rankingRawFit(raw.rawScores().fit())
                .presentationFitValue(presentationFitValue)
                .storedWorkloadValue(raw.currentWorkload())
                .derivedPerformanceInput(raw.rawScores().performance())
                .normalizedScores(normalized)
                .fullPrecisionScore(totalScore)
                .rankingKey(rankingKey)
                .roundedScore(round2(totalScore))
                .confidence(raw.confidence())
                .status(raw.user().status())
                .heuristicMode(mode)
                .fitStatus(fitStatus)
                .workloadStatus(MetricDataStatus.UNVERIFIED)
                .performanceStatus(MetricDataStatus.DEFAULT)
                .build();
    }

    private List<CandidateScore> computeCandidates(
            List<ProjectMemberDto> members,
            List<String> requiredSkills,
            HeuristicStrategy strategy,
            String mode) {
        List<RawCandidate> rawCandidates = members.stream()
                .map(member -> userPort.findById(member.userId())
                        .map(user -> buildRawCandidate(user, member.performanceScore(), requiredSkills))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();

        if (rawCandidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<RawScores> rawScores = rawCandidates.stream().map(RawCandidate::rawScores).toList();
        ScoreRanges ranges = ScoreRanges.from(rawScores);

        return rawCandidates.stream()
                .map(raw -> buildCandidateScore(raw, strategy, ranges, mode))
                .sorted(Comparator.comparingDouble(CandidateScore::getTotalScore).reversed())
                .collect(Collectors.toList());
    }

    private RawCandidate buildRawCandidate(UserProfileDto user, double projectPerformancePrior,
            List<String> requiredSkills) {
        if (isUnavailable(user.status())) {
            return null;
        }

        List<UserSkillDto> userSkills = userSkillPort.findByUserIdWithSkill(user.id());

        double fitScore = calculateFitScore(userSkills, requiredSkills);
        int workload = user.currentWorkload();
        double loadScore = normalizeLoad(workload);
        PerformanceSnapshot performanceSnapshot = calculateTimeDecayPerformanceScore(user.id(),
                projectPerformancePrior);

        return new RawCandidate(user, new RawScores(fitScore, loadScore, performanceSnapshot.performanceScore()),
                performanceSnapshot.confidence(), workload, userSkills != null && !userSkills.isEmpty());
    }

    private CandidateScore buildCandidateScore(RawCandidate raw,
            HeuristicStrategy strategy,
            ScoreRanges ranges,
            String mode) {
        NormalizedScores normalized = strategy.normalize(raw.rawScores(), ranges);
        double totalScore = strategy.score(normalized);

        double roundedFit = round2(normalized.fit());
        double roundedLoad = round2(normalized.load());
        double roundedPerf = round2(normalized.performance());

        return CandidateScore.builder()
                .userId(raw.user().id())
                .fullName(raw.user().fullName())
                .email(raw.user().email())
                .fitScore(roundedFit)
                .loadScore(roundedLoad)
                .performanceScore(roundedPerf)
                .confidenceScore(round2(raw.confidence()))
                .skillScore(roundedFit)
                .workloadScore(round2(1.0 - roundedLoad))
                .totalScore(round2(totalScore))
                .currentWorkload(raw.currentWorkload())
                .status(raw.user().status())
                .heuristicMode(mode)
                .build();
    }

    private double calculateFitScore(List<UserSkillDto> userSkills,
            List<String> requiredSkills) {
        if (requiredSkills == null || requiredSkills.isEmpty())
            return 1.0;
        Map<String, Integer> skillLevelMap = userSkills.stream()
                .collect(Collectors.toMap(us -> us.skillName().toLowerCase(),
                        UserSkillDto::level, (a, b) -> a));
        int matched = 0;
        int totalLevel = 0;
        for (String required : requiredSkills) {
            String key = required.toLowerCase();
            if (skillLevelMap.containsKey(key)) {
                matched++;
                totalLevel += skillLevelMap.get(key); // level 1–5
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

    private PerformanceSnapshot calculateTimeDecayPerformanceScore(Long userId,
            double projectPerformancePrior) {
        double prior = clamp01(projectPerformancePrior);
        List<Double> recentScores = projectMemberPort.findRecentPerformanceScores(userId, PERFORMANCE_WINDOW_SIZE)
                .stream().filter(Objects::nonNull).map(this::normalizePerformanceScore)
                .limit(PERFORMANCE_WINDOW_SIZE).toList();

        if (recentScores.isEmpty()) {
            return new PerformanceSnapshot(prior, 0.0);
        }

        double weightedRecent = weightedAverage(recentScores, DECAY_WEIGHTS);
        double confidence = resolveConfidence(recentScores.size());
        double blended = (confidence * weightedRecent)
                + ((1.0 - confidence) * prior);

        return new PerformanceSnapshot(clamp01(blended), confidence);
    }

    private double resolveConfidence(int evidenceCount) {
        return switch (evidenceCount) {
            case 0 -> 0.0;
            case 1 -> 0.4;
            case 2 -> 0.7;
            default -> 1.0;
        };
    }

    private double weightedAverage(List<Double> values, double[] weights) {
        double weightedSum = 0.0;
        double weightSum = 0.0;

        for (int i = 0; i < values.size() && i < weights.length; i++) {
            weightedSum += values.get(i) * weights[i];
            weightSum += weights[i];
        }

        if (weightSum == 0.0) {
            return NEUTRAL_PERFORMANCE_PRIOR;
        }
        return weightedSum / weightSum;
    }

    private double normalizeLoad(int workload) {
        int bounded = Math.max(0, Math.min(workload, 100));
        return bounded / 100.0;
    }

    private double normalizePerformanceScore(double score) {
        if (score > 1.0) {
            return clamp01(score / 100.0);
        }
        return clamp01(score);
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
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
    public String buildExplanationPrompt(
            List<RecommendedCandidateView> candidates,
            List<String> requiredSkills,
            int taskDifficulty,
            RecommendationDifferentiationStatus differentiationStatus) {
        StringBuilder candidateInfo = new StringBuilder();
        if (candidates != null) {
            for (RecommendedCandidateView c : candidates) {
                String fitDisplay;
                if (c.fitStatus() == MetricDataStatus.MEASURED && c.presentationFitValue() != null) {
                    fitDisplay = String.format("%.0f%% (MEASURED)", c.presentationFitValue() * 100);
                } else if (c.fitStatus() == MetricDataStatus.INSUFFICIENT_DATA) {
                    fitDisplay = "Chưa có dữ liệu kỹ năng (INSUFFICIENT_DATA)";
                } else {
                    fitDisplay = "Chưa xác thực (UNVERIFIED)";
                }

                String workloadDisplay;
                if (c.storedWorkloadValue() != null) {
                    workloadDisplay = String.format("%d điểm (UNVERIFIED: Chưa có dữ liệu workload đáng tin cậy)", c.storedWorkloadValue());
                } else {
                    workloadDisplay = "Chưa có dữ liệu workload đáng tin cậy (UNVERIFIED)";
                }

                String performanceDisplay = "Mặc định 0.50 (DEFAULT: Chưa đủ dữ liệu hiệu suất)";

                candidateInfo.append(String.format(
                        "%d. %s (Trạng thái: %s)\n" +
                        "   - Phù hợp kỹ năng: %s\n" +
                        "   - Khối lượng công việc lưu trữ: %s\n" +
                        "   - Hiệu suất: %s\n",
                        c.rank(),
                        c.displayName(),
                        c.memberStatus() != null ? c.memberStatus() : "AVAILABLE",
                        fitDisplay,
                        workloadDisplay,
                        performanceDisplay));
            }
        }

        String diffNotice = "";
        if (differentiationStatus == RecommendationDifferentiationStatus.INSUFFICIENT_TO_DIFFERENTIATE) {
            diffNotice = "\nLƯU Ý QUAN TRỌNG: Các ứng viên có điểm số tương đương nhau. Dữ liệu hiện tại KHÔNG ĐỦ để phân biệt ai vượt trội hơn. Tuyệt đối KHÔNG khẳng định ứng viên nào là 'tốt nhất' hoặc 'vượt trội'.\n";
        }

        return String.format(
                """
                Dựa trên phân tích phân công công việc cho nhiệm vụ yêu cầu kỹ năng [%s] (độ khó: %d/10):
                Danh sách ứng viên:
                %s
                %s
                Yêu cầu giải thích:
                1. Đưa ra nhận xét ngắn gọn (2-3 câu mỗi ứng viên) về sự phù hợp.
                2. KHÔNG khẳng định ứng viên có 'hiệu suất lịch sử xuất sắc' vì dữ liệu hiệu suất chỉ là mức mặc định (DEFAULT: Chưa đủ dữ liệu hiệu suất).
                3. KHÔNG khẳng định ứng viên 'hoàn toàn rảnh rỗi' từ khối lượng công việc lưu trữ (UNVERIFIED: Chưa có dữ liệu workload đáng tin cậy).
                4. Nếu kỹ năng là INSUFFICIENT_DATA, ghi rõ chưa đủ dữ liệu kỹ năng để đánh giá.
                5. Phản hồi bằng tiếng Việt ngắn gọn, chuyên nghiệp.
                """,
                String.join(", ", requiredSkills != null ? requiredSkills : Collections.emptyList()),
                taskDifficulty,
                candidateInfo.toString(),
                diffNotice);
    }

    public String generateExplanationForView(
            List<RecommendedCandidateView> candidates,
            List<String> requiredSkills,
            int taskDifficulty,
            RecommendationDifferentiationStatus differentiationStatus,
            Long userId,
            Long projectId) {
        if (candidates == null || candidates.isEmpty()) {
            return DEFAULT_FALLBACK_EXPLANATION;
        }
        try {
            String prompt = buildExplanationPrompt(candidates, requiredSkills, taskDifficulty, differentiationStatus);
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
                            future.complete(fullResponse.toString());
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

    private String generateExplanation(List<CandidateScore> top3, List<String> requiredSkills,
            int taskDifficulty, Long userId, Long projectId) {
        if (top3 == null || top3.isEmpty()) {
            return DEFAULT_FALLBACK_EXPLANATION;
        }
        List<RecommendedCandidateView> candidateViews = new ArrayList<>();
        int rank = 1;
        boolean hasReqs = requiredSkills != null && !requiredSkills.isEmpty();
        for (CandidateScore cs : top3) {
            candidateViews.add(RecommendedCandidateView.builder()
                    .rank(rank++)
                    .candidateId(cs.getUserId())
                    .displayName(cs.getFullName())
                    .presentationFitValue(hasReqs ? cs.getFitScore() : null)
                    .fitStatus(hasReqs ? MetricDataStatus.MEASURED : MetricDataStatus.INSUFFICIENT_DATA)
                    .storedWorkloadValue(cs.getCurrentWorkload())
                    .workloadStatus(MetricDataStatus.UNVERIFIED)
                    .performanceStatus(MetricDataStatus.DEFAULT)
                    .memberStatus(cs.getStatus() != null ? cs.getStatus() : "AVAILABLE")
                    .build());
        }
        return generateExplanationForView(candidateViews, requiredSkills, taskDifficulty,
                RecommendationDifferentiationStatus.UNKNOWN, userId, projectId);
    }

    private void saveAutoAssignLog(Long userId, Long projectId, List<String> requiredSkills,
            Object toolOutput, String explanation) {
        if (aiAuditPort == null) {
            return;
        }
        try {
            String actualModel = (explanation != null && !explanation.equals(DEFAULT_FALLBACK_EXPLANATION))
                    ? explanationModelName
                    : explanationModelName + " (fallback)";
            AiLogEntity log = AiLogEntity.builder().userId(userId).projectId(projectId)
                    .request("Auto-assignment request for skills: " + requiredSkills)
                    .response(explanation).actionTaken("autoAssignCandidates")
                    .toolOutput(toolOutput).humanFeedback("PENDING").modelUsed(actualModel)
                    .build();
            aiAuditPort.save(log);
        } catch (Exception e) {
            log.error("[AutoAssign] Failed to save audit log: {}", e.getMessage());
        }
    }

    private record RawCandidate(UserProfileDto user, RawScores rawScores, double confidence,
            int currentWorkload, boolean hasUserSkills) {
    }

    private record PerformanceSnapshot(double performanceScore, double confidence) {
    }
}

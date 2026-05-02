package com.taskpilot.ai.service;

import com.taskpilot.ai.assignment.port.out.AiAuditPort;
import com.taskpilot.ai.dto.AutoAssignmentResponse;
import com.taskpilot.ai.dto.CandidateScore;
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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoAssignmentService {
    private final ProjectMemberPort projectMemberPort;
    private final UserSkillPort userSkillPort;
    private final AiAuditPort aiAuditPort;
    private final UserPort userPort;
    private final ProjectPort projectPort;
    private final HeuristicStrategyFactory heuristicStrategyFactory;

    @Qualifier("deepSeekReasoningModel")
    private final StreamingChatModel deepSeekModel;

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

    @Transactional(readOnly = true)
    public AutoAssignmentResponse recommendCandidates(Long projectId, List<String> requiredSkills,
            int taskDifficulty, Long requestingUserId) {
        List<String> safeRequiredSkills = requiredSkills == null ? Collections.emptyList() : requiredSkills;
        log.info("[AutoAssign] Starting candidate scoring for project {} with skills: {}", projectId,
                safeRequiredSkills);

        String mode = resolveHeuristicMode(projectId);
        HeuristicStrategy strategy = heuristicStrategyFactory.resolve(mode);
        List<ProjectMemberDto> members = projectMemberPort.findProjectMembers(projectId);

        if (members.isEmpty()) {
            return AutoAssignmentResponse.builder().projectId(projectId)
                    .requiredSkills(safeRequiredSkills)
                    .candidates(Collections.emptyList())
                    .aiExplanation("No members found in this project.")
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
                performanceSnapshot.confidence(), workload);
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

    private String generateExplanation(List<CandidateScore> top3, List<String> requiredSkills,
            int taskDifficulty, Long userId, Long projectId) {
        try {
            StringBuilder candidateInfo = new StringBuilder();
            for (int i = 0; i < top3.size(); i++) {
                CandidateScore c = top3.get(i);
                candidateInfo.append(String.format(
                        "%d. %s (Fit: %.0f%%, Load: %.0f%%, Performance: %.0f%%, Total: %.2f, Mode: %s)\n",
                        i + 1, c.getFullName(), c.getFitScore() * 100, c.getLoadScore() * 100,
                        c.getPerformanceScore() * 100,
                        c.getTotalScore(),
                        c.getHeuristicMode()));
                candidateInfo.append(String.format("   Status: %s, Confidence: %.0f%%%n",
                        c.getStatus(), c.getConfidenceScore() * 100));
            }
            String prompt = String.format(
                    """
                            Based on the Heuristic scoring analysis for a task requiring skills [%s] with difficulty level %d/10:
                            Top candidates:
                            %s
                            Please provide a concise explanation (2-3 sentences per candidate) of why each person is recommended,
                            highlighting their strengths and any considerations the project manager should be aware of.
                            Respond in the same language as the task context.
                            """,
                    String.join(", ", requiredSkills), taskDifficulty, candidateInfo);
            CompletableFuture<String> future = new CompletableFuture<>();
            StringBuilder fullResponse = new StringBuilder();
            deepSeekModel.chat(List.of(SystemMessage.from(
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
                            log.warn("[AutoAssign] DeepSeek reasoning failed: {}",
                                    error.getMessage());
                            future.complete(
                                    "AI explanation unavailable. Please refer to the scores above.");
                        }
                    });
            return future.get(); // Blocking wait for auto-assignment (non-streaming endpoint)
        } catch (Exception e) {
            log.error("[AutoAssign] Failed to generate AI explanation: {}", e.getMessage());
            return "AI explanation could not be generated. Please review the candidate scores manually.";
        }
    }

    private void saveAutoAssignLog(Long userId, Long projectId, List<String> requiredSkills,
            List<CandidateScore> candidates, String explanation) {
        try {
            AiLogEntity log = AiLogEntity.builder().userId(userId).projectId(projectId)
                    .request("Auto-assignment request for skills: " + requiredSkills)
                    .response(explanation).actionTaken("autoAssignCandidates")
                    .toolOutput(candidates).humanFeedback("PENDING").modelUsed("DeepSeek-R1")
                    .build();
            aiAuditPort.save(log);
        } catch (Exception e) {
            log.error("[AutoAssign] Failed to save audit log: {}", e.getMessage());
        }
    }

    private record RawCandidate(UserProfileDto user, RawScores rawScores, double confidence,
            int currentWorkload) {
    }

    private record PerformanceSnapshot(double performanceScore, double confidence) {
    }
}

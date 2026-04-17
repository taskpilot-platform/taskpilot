package com.taskpilot.ai.service;

import com.taskpilot.ai.dto.AutoAssignmentResponse;
import com.taskpilot.ai.dto.CandidateScore;
import com.taskpilot.ai.entity.AiLogEntity;
import com.taskpilot.ai.repository.AiLogRepository;
import com.taskpilot.users.entity.SystemSettingEntity;
import com.taskpilot.users.entity.UserEntity;
import com.taskpilot.users.entity.UserSkillEntity;
import com.taskpilot.users.repository.SystemSettingRepository;
import com.taskpilot.users.repository.UserRepository;
import com.taskpilot.users.repository.UserSkillRepository;
import com.taskpilot.projects.common.entity.ProjectMemberEntity;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoAssignmentService {
    private final ProjectMemberRepository projectMemberRepository;
    private final UserSkillRepository userSkillRepository;
    private final SystemSettingRepository systemSettingRepository;
    private final AiLogRepository aiLogRepository;
    private final UserRepository userRepository;
    @Qualifier("deepSeekReasoningModel")
    private final StreamingChatModel deepSeekModel;
    private static final String HEURISTIC_WEIGHTS_KEY = "heuristic.weights";
    private static final double DEFAULT_SKILL_WEIGHT = 0.5;
    private static final double DEFAULT_WORKLOAD_WEIGHT = 0.3;
    private static final double DEFAULT_AVAILABILITY_WEIGHT = 0.2;

    @Transactional(readOnly = true)
    public AutoAssignmentResponse recommend(Long projectId, List<String> requiredSkills,
            int taskDifficulty, Long requestingUserId) {
        log.info("[AutoAssign] Starting recommendation for project {} with skills: {}", projectId,
                requiredSkills);
        HeuristicWeights weights = loadWeights();
        List<ProjectMemberEntity> members = projectMemberRepository.findMembers(projectId);
        if (members.isEmpty()) {
            return AutoAssignmentResponse.builder().projectId(projectId)
                    .candidates(Collections.emptyList())
                    .aiExplanation("No members found in this project.").build();
        }
        List<CandidateScore> scoredCandidates = members.stream()
                .map(member -> userRepository.findById(member.getUserId())
                        .map(user -> scoreMember(user, requiredSkills, weights)).orElse(null))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingDouble(CandidateScore::getTotalScore).reversed())
                .collect(Collectors.toList());
        List<CandidateScore> top3 = scoredCandidates.stream().limit(3).toList();
        String explanation = generateExplanation(top3, requiredSkills, taskDifficulty,
                requestingUserId, projectId);
        saveAutoAssignLog(requestingUserId, projectId, requiredSkills, scoredCandidates,
                explanation);
        return AutoAssignmentResponse.builder().projectId(projectId).requiredSkills(requiredSkills)
                .candidates(scoredCandidates).aiExplanation(explanation).build();
    }

    private CandidateScore scoreMember(UserEntity user, List<String> requiredSkills,
            HeuristicWeights weights) {
        List<UserSkillEntity> userSkills =
                userSkillRepository.findByIdUserIdWithSkill(user.getId());
        double skillScore = calculateSkillScore(userSkills, requiredSkills);
        int workload = user.getCurrentWorkload() != null ? user.getCurrentWorkload() : 0;
        double workloadScore = Math.max(0.0, 1.0 - (workload / 100.0));
        double availabilityScore = switch (user.getStatus()) {
            case AVAILABLE -> 1.0;
            case BUSY -> 0.5;
            case OOO, DEACTIVATED -> 0.0;
        };
        double totalScore =
                (skillScore * weights.skillWeight()) + (workloadScore * weights.workloadWeight())
                        + (availabilityScore * weights.availabilityWeight());
        return CandidateScore.builder().userId(user.getId()).fullName(user.getFullName())
                .email(user.getEmail()).skillScore(Math.round(skillScore * 100.0) / 100.0)
                .workloadScore(Math.round(workloadScore * 100.0) / 100.0)
                .availabilityScore(Math.round(availabilityScore * 100.0) / 100.0)
                .totalScore(Math.round(totalScore * 100.0) / 100.0).currentWorkload(workload)
                .status(user.getStatus().name()).build();
    }

    private double calculateSkillScore(List<UserSkillEntity> userSkills,
            List<String> requiredSkills) {
        if (requiredSkills == null || requiredSkills.isEmpty())
            return 1.0;
        Map<String, Integer> skillLevelMap = userSkills.stream()
                .collect(Collectors.toMap(us -> us.getSkill().getName().toLowerCase(),
                        UserSkillEntity::getLevel, (a, b) -> a));
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

    private String generateExplanation(List<CandidateScore> top3, List<String> requiredSkills,
            int taskDifficulty, Long userId, Long projectId) {
        try {
            StringBuilder candidateInfo = new StringBuilder();
            for (int i = 0; i < top3.size(); i++) {
                CandidateScore c = top3.get(i);
                candidateInfo.append(String.format(
                        "%d. %s (Skill: %.0f%%, Workload: %.0f%%, Availability: %s, Total: %.2f)\n",
                        i + 1, c.getFullName(), c.getSkillScore() * 100, c.getWorkloadScore() * 100,
                        c.getStatus(), c.getTotalScore()));
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

    @SuppressWarnings("unchecked")
    private HeuristicWeights loadWeights() {
        try {
            Optional<SystemSettingEntity> setting =
                    systemSettingRepository.findById(HEURISTIC_WEIGHTS_KEY);
            if (setting.isPresent() && setting.get().getValueJson() instanceof Map<?, ?> map) {
                Map<String, Object> weights = (Map<String, Object>) map;
                double skill = toDouble(weights.get("skill"), DEFAULT_SKILL_WEIGHT);
                double workload = toDouble(weights.get("workload"), DEFAULT_WORKLOAD_WEIGHT);
                double availability =
                        toDouble(weights.get("availability"), DEFAULT_AVAILABILITY_WEIGHT);
                return new HeuristicWeights(skill, workload, availability);
            }
        } catch (Exception e) {
            log.warn("[AutoAssign] Failed to load heuristic weights, using defaults: {}",
                    e.getMessage());
        }
        return new HeuristicWeights(DEFAULT_SKILL_WEIGHT, DEFAULT_WORKLOAD_WEIGHT,
                DEFAULT_AVAILABILITY_WEIGHT);
    }

    private double toDouble(Object value, double defaultValue) {
        if (value instanceof Number n)
            return n.doubleValue();
        return defaultValue;
    }

    private void saveAutoAssignLog(Long userId, Long projectId, List<String> requiredSkills,
            List<CandidateScore> candidates, String explanation) {
        try {
            AiLogEntity log = AiLogEntity.builder().userId(userId).projectId(projectId)
                    .request("Auto-assignment request for skills: " + requiredSkills)
                    .response(explanation).actionTaken("autoAssignCandidates")
                    .toolOutput(candidates).humanFeedback("PENDING").modelUsed("DeepSeek-R1")
                    .build();
            aiLogRepository.save(log);
        } catch (Exception e) {
            log.error("[AutoAssign] Failed to save audit log: {}", e.getMessage());
        }
    }

    private record HeuristicWeights(double skillWeight, double workloadWeight,
            double availabilityWeight) {
    }
}


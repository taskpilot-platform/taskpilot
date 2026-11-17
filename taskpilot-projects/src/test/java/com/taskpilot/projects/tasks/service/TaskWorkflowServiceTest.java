package com.taskpilot.projects.tasks.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.taskpilot.contracts.assignment.port.out.UserPort;
import com.taskpilot.contracts.task.RecoverLegacyReviewCommand;
import com.taskpilot.contracts.task.ReopenTaskCommand;
import com.taskpilot.contracts.task.ReviewDecision;
import com.taskpilot.contracts.task.ReviewTaskCommand;
import com.taskpilot.contracts.task.SubmitTaskCommand;
import com.taskpilot.contracts.task.SupersedeEvaluationCommand;
import com.taskpilot.contracts.task.TaskQualityRubric;
import com.taskpilot.contracts.task.WithdrawTaskCommand;
import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.contracts.user.port.out.AccountAuthStatePort;
import com.taskpilot.contracts.user.port.out.UserNotificationPort;
import com.taskpilot.infrastructure.exception.BusinessException;
import com.taskpilot.projects.common.repository.ProjectFileRepository;
import com.taskpilot.projects.common.repository.ProjectMemberRepository;
import com.taskpilot.projects.common.repository.ProjectRepository;
import com.taskpilot.projects.common.repository.SprintRepository;
import com.taskpilot.projects.common.repository.TaskCommandIdempotencyRepository;
import com.taskpilot.projects.common.repository.TaskDeadlineRevisionRepository;
import com.taskpilot.projects.common.repository.TaskEvaluationInvalidationRepository;
import com.taskpilot.projects.common.repository.TaskEvaluationRepository;
import com.taskpilot.projects.common.repository.TaskEvidenceAvailabilityEventRepository;
import com.taskpilot.projects.common.repository.TaskRepository;
import com.taskpilot.projects.common.repository.TaskRequiredSkillRepository;
import com.taskpilot.projects.common.repository.TaskReviewRepository;
import com.taskpilot.projects.common.repository.TaskSubmissionEvidenceFileRepository;
import com.taskpilot.projects.common.repository.TaskSubmissionRepository;
import com.taskpilot.projects.common.repository.TaskWorkflowJournalRepository;

@ExtendWith(MockitoExtension.class)
class TaskWorkflowServiceTest {

    @Mock private TaskRepository taskRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectMemberRepository projectMemberRepository;
    @Mock private SprintRepository sprintRepository;
    @Mock private ProjectFileRepository projectFileRepository;
    @Mock private TaskRequiredSkillRepository taskRequiredSkillRepository;
    @Mock private TaskDeadlineRevisionRepository deadlineRevisionRepository;
    @Mock private TaskSubmissionRepository submissionRepository;
    @Mock private TaskSubmissionEvidenceFileRepository evidenceFileRepository;
    @Mock private TaskEvidenceAvailabilityEventRepository availabilityEventRepository;
    @Mock private TaskReviewRepository reviewRepository;
    @Mock private TaskEvaluationRepository evaluationRepository;
    @Mock private TaskEvaluationInvalidationRepository invalidationRepository;
    @Mock private TaskWorkflowJournalRepository journalRepository;
    @Mock private TaskCommandIdempotencyRepository idempotencyRepository;
    @Mock private AccountAuthStatePort accountAuthStatePort;
    @Mock private UserPort userPort;
    @Mock private UserNotificationPort notificationPort;
    @Mock private TaskWorkflowJournalDispatcher journalDispatcher;

    private TaskWorkflowService service;
    private CurrentActor actor;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-18T10:00:00Z"), ZoneId.of("UTC"));
        service = new TaskWorkflowService(
                taskRepository,
                projectRepository,
                projectMemberRepository,
                sprintRepository,
                projectFileRepository,
                taskRequiredSkillRepository,
                deadlineRevisionRepository,
                submissionRepository,
                evidenceFileRepository,
                availabilityEventRepository,
                reviewRepository,
                evaluationRepository,
                invalidationRepository,
                journalRepository,
                idempotencyRepository,
                accountAuthStatePort,
                userPort,
                notificationPort,
                journalDispatcher,
                clock);
        actor = new CurrentActor(42L, "user@taskpilot.test", "USER", 1L);
    }

    @Test
    void qualityRubric_containsFiveAnchoredLevelsAndRejectsOutOfRange() {
        assertThat(TaskQualityRubric.RUBRIC_VERSION).isEqualTo("quality-v1");
        assertThat(TaskQualityRubric.QUALITY_V1_ANCHORS).hasSize(5);
        assertThat(TaskQualityRubric.QUALITY_V1_ANCHORS)
                .extracting(TaskQualityRubric.LevelAnchor::rating)
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(TaskQualityRubric.anchorFor(5).label()).contains("5");
        assertThatThrownBy(() -> TaskQualityRubric.anchorFor(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskQualityRubric.anchorFor(6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workflowMutations_failClosedWithoutActorOrRequestIdOrExpectedVersion() {
        UUID reqId = UUID.randomUUID();

        assertThatThrownBy(() -> service.submit(
                new SubmitTaskCommand(1L, 0L, reqId, "Done", List.of()), null))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(401);

        assertThatThrownBy(() -> service.submit(
                new SubmitTaskCommand(1L, null, reqId, "Done", List.of()), actor))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(428);

        assertThatThrownBy(() -> service.submit(
                new SubmitTaskCommand(1L, -1L, reqId, "Done", List.of()), actor))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(400);

        assertThatThrownBy(() -> service.submit(
                new SubmitTaskCommand(1L, 0L, null, "Done", List.of()), actor))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(400);

        assertThatThrownBy(() -> service.withdrawSubmission(
                new WithdrawTaskCommand(1L, 9L, null, reqId, "Wait"), actor))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(428);

        assertThatThrownBy(() -> service.withdrawSubmission(
                new WithdrawTaskCommand(1L, 9L, 1L, null, "Wait"), actor))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(400);

        assertThatThrownBy(() -> service.review(
                new ReviewTaskCommand(1L, 9L, null, reqId, ReviewDecision.ACCEPT, 4, "Ok", "Proof"), actor))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(428);

        assertThatThrownBy(() -> service.review(
                new ReviewTaskCommand(1L, 9L, 1L, null, ReviewDecision.ACCEPT, 4, "Ok", "Proof"), actor))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(400);

        assertThatThrownBy(() -> service.review(
                new ReviewTaskCommand(1L, null, 1L, reqId, ReviewDecision.ACCEPT, 4, "Ok", "Proof"), actor))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(400);

        assertThatThrownBy(() -> service.review(
                new ReviewTaskCommand(1L, 9L, 1L, reqId, null, 4, "Ok", "Proof"), actor))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(400);

        assertThatThrownBy(() -> service.reopen(
                new ReopenTaskCommand(1L, null, reqId, "Rework"), actor))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(428);

        assertThatThrownBy(() -> service.reopen(
                new ReopenTaskCommand(1L, 2L, null, "Rework"), actor))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(400);

        assertThatThrownBy(() -> service.supersedeEvaluation(
                new SupersedeEvaluationCommand(1L, 9L, 10L, 1L, null, reqId, 3, "Fix", "Re-verified"), actor))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(428);

        assertThatThrownBy(() -> service.supersedeEvaluation(
                new SupersedeEvaluationCommand(1L, 9L, 10L, 1L, 2L, null, 3, "Fix", "Re-verified"), actor))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(400);

        assertThatThrownBy(() -> service.recoverLegacyReview(
                new RecoverLegacyReviewCommand(1L, null, reqId, "Legacy"), actor))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(428);

        assertThatThrownBy(() -> service.recoverLegacyReview(
                new RecoverLegacyReviewCommand(1L, 0L, null, "Legacy"), actor))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(400);

        verifyNoInteractions(taskRepository, submissionRepository, evaluationRepository, reviewRepository);
    }
}

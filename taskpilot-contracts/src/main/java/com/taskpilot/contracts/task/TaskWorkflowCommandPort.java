package com.taskpilot.contracts.task;

import com.taskpilot.contracts.user.dto.CurrentActor;

public interface TaskWorkflowCommandPort {

    TaskWorkflowResult submit(SubmitTaskCommand command, CurrentActor actor);

    TaskWorkflowResult review(ReviewTaskCommand command, CurrentActor actor);

    TaskWorkflowResult reopen(ReopenTaskCommand command, CurrentActor actor);

    TaskWorkflowResult withdrawSubmission(WithdrawTaskCommand command, CurrentActor actor);

    TaskWorkflowResult supersedeEvaluation(SupersedeEvaluationCommand command, CurrentActor actor);

    TaskWorkflowResult recoverLegacyReview(RecoverLegacyReviewCommand command, CurrentActor actor);
}

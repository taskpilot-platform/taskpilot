package com.taskpilot.contracts.task;

import java.util.Collection;
import java.util.Map;

public interface TaskEvaluationJournalStatePort {

    JournalHealthSummary summarize(Long projectId);

    int replayDueEntries();

    Map<Long, String> findInvalidationReasons(Collection<Long> evaluationIds);

    record JournalHealthSummary(long pendingCount, long retryPendingCount, long failedCount) {
        public long totalUnresolved() {
            return pendingCount + retryPendingCount + failedCount;
        }
    }
}

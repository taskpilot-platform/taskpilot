package com.taskpilot.contracts.project.exception;

import java.util.Collection;
import java.util.List;

/**
 * Signals that a self-deactivation attempt discovered newly added managerial projects after
 * initial lock acquisition and must fully roll back before the outer coordinator retries.
 */
public class RetryableRosterChangeException extends RuntimeException {

    private final List<Long> missingProjectIds;

    public RetryableRosterChangeException(Collection<Long> missingProjectIds) {
        super("Concurrent roster change detected; transaction must roll back and retry");
        this.missingProjectIds = missingProjectIds == null
                ? List.of()
                : missingProjectIds.stream().distinct().sorted().toList();
    }

    public List<Long> getMissingProjectIds() {
        return missingProjectIds;
    }
}

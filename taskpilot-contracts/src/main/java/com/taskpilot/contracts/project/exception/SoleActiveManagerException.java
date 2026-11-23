package com.taskpilot.contracts.project.exception;

import java.util.Collection;
import java.util.List;

/**
 * Non-retryable conflict thrown when an operation would leave an active project without any
 * effective ACTIVE manager.
 */
public class SoleActiveManagerException extends RuntimeException {

    private final List<Long> blockingProjectIds;

    public SoleActiveManagerException(Collection<Long> blockingProjectIds, String message) {
        super(message);
        this.blockingProjectIds = blockingProjectIds == null
                ? List.of()
                : blockingProjectIds.stream().distinct().sorted().toList();
    }

    public List<Long> getBlockingProjectIds() {
        return blockingProjectIds;
    }
}

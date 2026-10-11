package com.taskpilot.ai.dto;

/**
 * State lifecycle for offline adaptive weight proposals in Phase 2F.
 */
public enum WeightProposalStatus {
    /**
     * Insufficient evidence available in the dataset (< minimumRequiredSamples).
     */
    INSUFFICIENT_EVIDENCE,

    /**
     * Proposal generated from sufficient evidence and awaiting Admin review.
     */
    READY_FOR_REVIEW,

    /**
     * Proposal approved by global Admin (remains shadow; does not activate or mutate active weights).
     */
    APPROVED,

    /**
     * Proposal rejected by global Admin.
     */
    REJECTED
}

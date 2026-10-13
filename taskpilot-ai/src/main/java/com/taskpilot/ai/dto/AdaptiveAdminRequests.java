package com.taskpilot.ai.dto;

public class AdaptiveAdminRequests {

    public record ApproveProposalRequest(String notes) {
    }

    public record RejectProposalRequest(String reason) {
    }
}

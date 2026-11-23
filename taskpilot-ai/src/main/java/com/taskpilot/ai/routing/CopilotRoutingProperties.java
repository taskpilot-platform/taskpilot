package com.taskpilot.ai.routing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Configuration properties for Copilot model chain, verified model registry,
 * blocked model list, and shared per-turn budget.
 */
@Data
@ConfigurationProperties(prefix = "ai.copilot")
public class CopilotRoutingProperties {

    private Turn turn = new Turn();
    private List<VerifiedModel> verifiedModels = new ArrayList<>();
    private List<BlockedModel> blockedModels = new ArrayList<>();

    @Data
    public static class Turn {
        /**
         * Shared turn deadline in seconds across all tool rounds and model failovers.
         */
        private int deadlineSeconds = 60;

        /**
         * Optional hard cap on distinct model attempts in a turn. 0 = uncapped (chain length).
         */
        private int maxModelAttemptsCap = 0;
    }

    @Data
    public static class VerifiedModel {
        private String provider;
        private String model;
        private boolean tools = true;
        private String verifiedBy;

        public VerifiedModel() {}

        public VerifiedModel(String provider, String model, boolean tools, String verifiedBy) {
            this.provider = provider;
            this.model = model;
            this.tools = tools;
            this.verifiedBy = verifiedBy;
        }
    }

    @Data
    public static class BlockedModel {
        private String provider;
        private String model;
        private String reason;

        public BlockedModel() {}

        public BlockedModel(String provider, String model, String reason) {
            this.provider = provider;
            this.model = model;
            this.reason = reason;
        }
    }

    public boolean isBlocked(String provider, String model) {
        if (blockedModels == null || blockedModels.isEmpty()) {
            return false;
        }
        String target = ModelCandidate.identityOf(provider, model);
        for (BlockedModel entry : blockedModels) {
            if (entry != null && target.equals(ModelCandidate.identityOf(entry.getProvider(), entry.getModel()))) {
                return true;
            }
        }
        return false;
    }

    public Optional<VerifiedModel> findVerified(String provider, String model) {
        if (verifiedModels == null || verifiedModels.isEmpty()) {
            return Optional.empty();
        }
        String target = ModelCandidate.identityOf(provider, model);
        for (VerifiedModel entry : verifiedModels) {
            if (entry != null && target.equals(ModelCandidate.identityOf(entry.getProvider(), entry.getModel()))) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    public boolean hasVerifiedRegistry() {
        return verifiedModels != null && !verifiedModels.isEmpty();
    }
}

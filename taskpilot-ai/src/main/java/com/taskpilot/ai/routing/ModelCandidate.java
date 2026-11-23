package com.taskpilot.ai.routing;

import java.util.Locale;

/**
 * Immutable candidate entry in a Copilot turn's ordered model failover chain.
 */
public record ModelCandidate(
        String provider,
        String model,
        boolean toolCapable,
        Source source) {

    public enum Source {
        DB_PRIORITY,
        DEFAULT_CHAIN
    }

    public ModelCandidate {
        provider = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        model = model == null ? "" : model.trim();
        source = source == null ? Source.DEFAULT_CHAIN : source;
    }

    public String identity() {
        return identityOf(provider, model);
    }

    public String label() {
        return provider + ":" + model;
    }

    public static String identityOf(String provider, String model) {
        String p = provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
        String m = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        return p + "|" + m;
    }
}

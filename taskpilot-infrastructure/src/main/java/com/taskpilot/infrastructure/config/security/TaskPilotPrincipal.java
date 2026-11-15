package com.taskpilot.infrastructure.config.security;

import com.taskpilot.contracts.user.dto.CurrentActor;
import com.taskpilot.contracts.user.dto.RealtimeAccessContext;

import java.security.Principal;
import java.util.Objects;

/**
 * Typed security principal carrying immutable numeric {@code uid} ({@link #getName()})
 * and authoritative database-backed {@link CurrentActor} without raw credentials or bearer tokens.
 */
public final class TaskPilotPrincipal implements Principal {

    private final CurrentActor actor;
    private final RealtimeAccessContext realtimeContext;

    public TaskPilotPrincipal(CurrentActor actor) {
        this(actor, null);
    }

    public TaskPilotPrincipal(CurrentActor actor, RealtimeAccessContext realtimeContext) {
        this.actor = Objects.requireNonNull(actor, "actor must not be null");
        if (actor.userId() <= 0) {
            throw new IllegalArgumentException("actor.userId must be positive");
        }
        this.realtimeContext = realtimeContext;
    }

    @Override
    public String getName() {
        return Long.toString(actor.userId());
    }

    public long userId() {
        return actor.userId();
    }

    public String currentEmail() {
        return actor.currentEmail();
    }

    public String role() {
        return actor.role();
    }

    public long tokenVersion() {
        return actor.tokenVersion();
    }

    public CurrentActor actor() {
        return actor;
    }

    public RealtimeAccessContext realtimeContext() {
        return realtimeContext;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaskPilotPrincipal that)) {
            return false;
        }
        return Objects.equals(actor, that.actor) && Objects.equals(realtimeContext, that.realtimeContext);
    }

    @Override
    public int hashCode() {
        return Objects.hash(actor, realtimeContext);
    }

    @Override
    public String toString() {
        return "TaskPilotPrincipal[userId=" + actor.userId() + ", role=" + actor.role() + "]";
    }
}

-- P8: Adaptive snapshot provenance, decision linkage/idempotency,
-- and reviewed-outcome-v2 / invalidation projections.

ALTER TABLE public.recommendation_snapshots
    ADD COLUMN IF NOT EXISTS task_version BIGINT NULL,
    ADD COLUMN IF NOT EXISTS assignment_version BIGINT NULL,
    ADD COLUMN IF NOT EXISTS weight_version VARCHAR(64) NOT NULL DEFAULT 'baseline-v1',
    ADD COLUMN IF NOT EXISTS roster_revision VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS synthetic BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_recommendation_snapshots_task_id
    ON public.recommendation_snapshots (task_id)
    WHERE task_id IS NOT NULL;

ALTER TABLE public.recommendation_snapshot_candidates
    ADD COLUMN IF NOT EXISTS fit_source VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS workload_revision VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS performance_version VARCHAR(32) NULL,
    ADD COLUMN IF NOT EXISTS performance_rubric_version VARCHAR(32) NULL,
    ADD COLUMN IF NOT EXISTS performance_sample_count INTEGER NULL,
    ADD COLUMN IF NOT EXISTS performance_window_limit INTEGER NULL,
    ADD COLUMN IF NOT EXISTS observed_quality_mean DOUBLE PRECISION NULL,
    ADD COLUMN IF NOT EXISTS evidence_weight DOUBLE PRECISION NULL,
    ADD COLUMN IF NOT EXISTS performance_evidence_revision VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS performance_measured_at TIMESTAMPTZ NULL;

ALTER TABLE public.recommendation_decision_events
    ADD COLUMN IF NOT EXISTS task_version BIGINT NULL,
    ADD COLUMN IF NOT EXISTS assignment_version BIGINT NULL,
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128) NULL,
    ADD COLUMN IF NOT EXISTS roster_revision VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS synthetic BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX IF NOT EXISTS ux_rec_decision_actor_idempotency
    ON public.recommendation_decision_events (decided_by_user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_rec_decision_task_id
    ON public.recommendation_decision_events (task_id)
    WHERE task_id IS NOT NULL;

CREATE TABLE public.adaptive_reviewed_outcomes (
    outcome_id                    VARCHAR(64) PRIMARY KEY,
    source_event_id               UUID        NOT NULL,
    project_id                    BIGINT      NOT NULL,
    task_id                       BIGINT      NOT NULL,
    submission_id                 BIGINT      NOT NULL,
    evaluation_id                 BIGINT      NOT NULL,
    evaluation_version            INTEGER     NOT NULL,
    assignment_version            BIGINT      NOT NULL,
    assignee_id                   BIGINT      NOT NULL,
    reviewer_id                   BIGINT      NOT NULL,
    decision_id                   VARCHAR(64) NULL REFERENCES public.recommendation_decision_events(decision_id),
    snapshot_id                   VARCHAR(64) NULL REFERENCES public.recommendation_snapshots(snapshot_id),
    evidence_status               VARCHAR(32) NOT NULL,
    quality_rating                INTEGER     NULL,
    rubric_version                VARCHAR(32) NOT NULL,
    accepted_submitted_at         TIMESTAMPTZ NOT NULL,
    reviewed_at                   TIMESTAMPTZ NOT NULL,
    commitment_due_at             TIMESTAMPTZ NULL,
    on_time                       BOOLEAN     NULL,
    timeliness_status             VARCHAR(32) NOT NULL,
    evidence_revision             BIGINT      NOT NULL,
    invalidated                   BOOLEAN     NOT NULL DEFAULT FALSE,
    invalidation_reason           VARCHAR(64) NULL,
    invalidated_at                TIMESTAMPTZ NULL,
    invalidation_event_id         UUID        NULL,
    invalidated_evidence_revision BIGINT      NULL,
    synthetic                     BOOLEAN     NOT NULL DEFAULT FALSE,
    outcome_version               VARCHAR(32) NOT NULL DEFAULT 'reviewed-outcome-v2',
    projected_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_adaptive_reviewed_outcomes_event UNIQUE (source_event_id),
    CONSTRAINT ux_adaptive_reviewed_outcomes_eval UNIQUE (evaluation_id, evaluation_version),
    CONSTRAINT chk_adaptive_rev_outcomes_status CHECK (
        evidence_status IN ('INDEPENDENT_ACCEPTED', 'SELF_ATTESTED')
    ),
    CONSTRAINT chk_adaptive_rev_outcomes_timeliness CHECK (
        timeliness_status IN ('ON_TIME', 'LATE', 'NO_DUE_DATE')
    ),
    CONSTRAINT chk_adaptive_rev_outcomes_rating CHECK (
        (evidence_status = 'INDEPENDENT_ACCEPTED' AND quality_rating BETWEEN 1 AND 5)
            OR (evidence_status = 'SELF_ATTESTED' AND quality_rating IS NULL)
    )
);

CREATE INDEX idx_adaptive_reviewed_outcomes_scope
    ON public.adaptive_reviewed_outcomes (project_id, invalidated, projected_at DESC, task_id ASC);

CREATE INDEX idx_adaptive_reviewed_outcomes_task
    ON public.adaptive_reviewed_outcomes (task_id, evaluation_version DESC);

CREATE INDEX idx_adaptive_reviewed_outcomes_decision
    ON public.adaptive_reviewed_outcomes (decision_id)
    WHERE decision_id IS NOT NULL;

REVOKE ALL ON TABLE public.adaptive_reviewed_outcomes FROM PUBLIC;
ALTER TABLE public.adaptive_reviewed_outcomes ENABLE ROW LEVEL SECURITY;
CREATE POLICY deny_all_adaptive_reviewed_outcomes
    ON public.adaptive_reviewed_outcomes
    FOR ALL
    TO PUBLIC
    USING (false)
    WITH CHECK (false);

CREATE TABLE public.adaptive_outcome_invalidations (
    event_id           UUID        PRIMARY KEY,
    project_id         BIGINT      NOT NULL,
    task_id            BIGINT      NOT NULL,
    evaluation_id      BIGINT      NOT NULL,
    evaluation_version INTEGER     NOT NULL,
    reason             VARCHAR(64) NOT NULL,
    occurred_at        TIMESTAMPTZ NOT NULL,
    evidence_revision  BIGINT      NOT NULL,
    CONSTRAINT ux_adaptive_outcome_inv_eval UNIQUE (evaluation_id, evaluation_version)
);

CREATE INDEX idx_adaptive_outcome_inv_project_task
    ON public.adaptive_outcome_invalidations (project_id, task_id);

REVOKE ALL ON TABLE public.adaptive_outcome_invalidations FROM PUBLIC;
ALTER TABLE public.adaptive_outcome_invalidations ENABLE ROW LEVEL SECURITY;
CREATE POLICY deny_all_adaptive_outcome_invalidations
    ON public.adaptive_outcome_invalidations
    FOR ALL
    TO PUBLIC
    USING (false)
    WITH CHECK (false);

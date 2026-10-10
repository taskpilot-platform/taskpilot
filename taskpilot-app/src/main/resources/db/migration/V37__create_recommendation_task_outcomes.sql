-- V37__create_recommendation_task_outcomes.sql
-- Phase 2D: Task completion timestamp and minimal task outcome foundation

ALTER TABLE "tasks"
    ADD COLUMN IF NOT EXISTS "completed_at" TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS "idx_tasks_completed_at"
    ON "tasks" ("completed_at");

CREATE TABLE IF NOT EXISTS "recommendation_task_outcomes" (
    "outcome_id"                VARCHAR(64) PRIMARY KEY,
    "snapshot_id"               VARCHAR(64) NOT NULL,
    "decision_id"               VARCHAR(64) NOT NULL,
    "project_id"                BIGINT NOT NULL,
    "task_id"                   BIGINT NOT NULL,
    "recommended_candidate_id"  BIGINT,
    "selected_candidate_id"    BIGINT,
    "decision_type"             VARCHAR(32) NOT NULL,
    "decision_source"           VARCHAR(16) NOT NULL,
    "observed_assignee_id"      BIGINT,
    "task_status"               VARCHAR(32) NOT NULL,
    "due_at"                    TIMESTAMP WITH TIME ZONE,
    "completed_at"              TIMESTAMP WITH TIME ZONE,
    "outcome_type"              VARCHAR(32) NOT NULL,
    "exclusion_reason"          VARCHAR(64),
    "observed_at"               TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "outcome_version"           VARCHAR(32) NOT NULL DEFAULT 'outcome-v1',
    CONSTRAINT "fk_task_outcomes_snapshot" FOREIGN KEY ("snapshot_id")
        REFERENCES "recommendation_snapshots" ("snapshot_id"),
    CONSTRAINT "fk_task_outcomes_decision" FOREIGN KEY ("decision_id")
        REFERENCES "recommendation_decision_events" ("decision_id"),
    CONSTRAINT "fk_task_outcomes_task" FOREIGN KEY ("task_id")
        REFERENCES "tasks" ("id"),
    CONSTRAINT "uk_task_outcomes_decision_task" UNIQUE ("decision_id", "task_id")
);

CREATE INDEX IF NOT EXISTS "idx_task_outcomes_task_id"
    ON "recommendation_task_outcomes" ("task_id");

CREATE INDEX IF NOT EXISTS "idx_task_outcomes_decision_id"
    ON "recommendation_task_outcomes" ("decision_id");

CREATE INDEX IF NOT EXISTS "idx_task_outcomes_project_observed"
    ON "recommendation_task_outcomes" ("project_id", "observed_at");

-- V35__create_recommendation_decision_events_table.sql
-- Append-only recommendation decision events for Phase 2B

CREATE TABLE IF NOT EXISTS "recommendation_decision_events" (
    "decision_id"              VARCHAR(64) PRIMARY KEY,
    "snapshot_id"             VARCHAR(64) NOT NULL UNIQUE,
    "project_id"              BIGINT NOT NULL,
    "task_id"                 BIGINT,
    "decided_by_user_id"      BIGINT,
    "decision_source"         VARCHAR(16) NOT NULL,
    "decision_type"           VARCHAR(32) NOT NULL,
    "recommended_candidate_id" BIGINT,
    "selected_candidate_id"   BIGINT,
    "reason_code"             VARCHAR(64),
    "note"                    VARCHAR(1000),
    "created_at"              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "fk_decision_events_snapshot" FOREIGN KEY ("snapshot_id")
        REFERENCES "recommendation_snapshots" ("snapshot_id")
);

CREATE INDEX IF NOT EXISTS "idx_recommendation_decision_events_project_created"
    ON "recommendation_decision_events" ("project_id", "created_at");

CREATE INDEX IF NOT EXISTS "idx_recommendation_decision_events_decided_by"
    ON "recommendation_decision_events" ("decided_by_user_id");

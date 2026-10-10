-- V36__add_phase_2c_measured_workload_to_snapshots.sql
-- Phase 2C: Add measured active task count workload columns to recommendation snapshot candidates

ALTER TABLE "recommendation_snapshot_candidates"
    ADD COLUMN IF NOT EXISTS "workload_unit" VARCHAR(32),
    ADD COLUMN IF NOT EXISTS "workload_scope" VARCHAR(32),
    ADD COLUMN IF NOT EXISTS "workload_measured_at" TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS "idx_tasks_project_assignee_status"
    ON "tasks" ("project_id", "assignee_id", "status");

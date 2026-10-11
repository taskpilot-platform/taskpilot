-- V40__scope_adaptive_proposals_to_heuristic_mode.sql
-- Phase 2F: Scope Adaptive Weight Proposals to heuristic mode (nullable for legacy compatibility)

ALTER TABLE "adaptive_weight_proposals"
    ADD COLUMN IF NOT EXISTS "target_heuristic_mode" VARCHAR(32) NULL;

ALTER TABLE "adaptive_weight_proposals"
    ADD CONSTRAINT "chk_adaptive_proposals_target_mode"
    CHECK ("target_heuristic_mode" IS NULL OR "target_heuristic_mode" = 'BALANCED');

CREATE INDEX IF NOT EXISTS "idx_adaptive_proposals_target_mode"
    ON "adaptive_weight_proposals" ("target_heuristic_mode");

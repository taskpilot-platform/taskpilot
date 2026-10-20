-- V39__create_adaptive_weight_proposals.sql
-- Phase 2F: Adaptive Weight Proposals and Audit Trail

CREATE TABLE IF NOT EXISTS "adaptive_weight_proposals" (
    "proposal_id"                   VARCHAR(64) PRIMARY KEY,
    "status"                        VARCHAR(32) NOT NULL,
    "dataset_hash"                  VARCHAR(64) NOT NULL,
    "dataset_sample_count"          INTEGER NOT NULL,
    "current_fit_weight"            DOUBLE PRECISION NOT NULL,
    "current_load_weight"           DOUBLE PRECISION NOT NULL,
    "current_performance_weight"    DOUBLE PRECISION NOT NULL,
    "proposed_fit_weight"           DOUBLE PRECISION NOT NULL,
    "proposed_load_weight"          DOUBLE PRECISION NOT NULL,
    "proposed_performance_weight"   DOUBLE PRECISION NOT NULL,
    "grid_step"                     DOUBLE PRECISION NOT NULL,
    "metric_historical_agreement"   DOUBLE PRECISION NOT NULL,
    "metric_on_time_association"    DOUBLE PRECISION NOT NULL,
    "created_at"                    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "created_by_user_id"            BIGINT NOT NULL,
    "reviewed_at"                   TIMESTAMP WITH TIME ZONE,
    "reviewed_by_user_id"           BIGINT,
    "review_notes"                  VARCHAR(1000),
    "rejection_reason"              VARCHAR(1000),
    "proposal_version"              VARCHAR(32) NOT NULL DEFAULT 'proposal-v1'
);

CREATE INDEX IF NOT EXISTS "idx_adaptive_proposals_status"
    ON "adaptive_weight_proposals" ("status");

CREATE INDEX IF NOT EXISTS "idx_adaptive_proposals_created_at"
    ON "adaptive_weight_proposals" ("created_at");

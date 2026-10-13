-- V39__correct_adaptive_proposal_safety.sql
-- Phase 2F: Correct Adaptive proposal safety, nullability for insufficient evidence, and baseline normalization

-- 1. Relax NOT NULL constraints on proposed weights, grid step, and metrics for INSUFFICIENT_EVIDENCE proposals
ALTER TABLE "adaptive_weight_proposals"
    ALTER COLUMN "proposed_fit_weight" DROP NOT NULL,
    ALTER COLUMN "proposed_load_weight" DROP NOT NULL,
    ALTER COLUMN "proposed_performance_weight" DROP NOT NULL,
    ALTER COLUMN "grid_step" DROP NOT NULL,
    ALTER COLUMN "metric_historical_agreement" DROP NOT NULL,
    ALTER COLUMN "metric_on_time_association" DROP NOT NULL;

-- 2. Add metadata columns for dataset version, algorithm version, normalized baseline, baseline metrics, and evaluation window
ALTER TABLE "adaptive_weight_proposals"
    ADD COLUMN IF NOT EXISTS "dataset_version" VARCHAR(32) NOT NULL DEFAULT 'dataset-v1',
    ADD COLUMN IF NOT EXISTS "algorithm_version" VARCHAR(32) NOT NULL DEFAULT 'grid-search-v1',
    ADD COLUMN IF NOT EXISTS "current_adaptive_fit_weight" DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS "current_adaptive_load_weight" DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS "current_metric_historical_agreement" DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS "current_metric_on_time_association" DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS "evaluation_window_start" TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS "evaluation_window_end" TIMESTAMP WITH TIME ZONE;

-- 3. Backfill normalized baseline for historical rows where current fit + load > 0
UPDATE "adaptive_weight_proposals"
SET "current_adaptive_fit_weight" = "current_fit_weight" / ("current_fit_weight" + "current_load_weight"),
    "current_adaptive_load_weight" = "current_load_weight" / ("current_fit_weight" + "current_load_weight")
WHERE ("current_fit_weight" + "current_load_weight") > 0
  AND "current_adaptive_fit_weight" IS NULL;

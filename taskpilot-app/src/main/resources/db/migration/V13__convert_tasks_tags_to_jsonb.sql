-- Convert tasks.tags from TEXT[] (native PostgreSQL array) to JSONB
-- Reason: Hibernate 6 SqlTypes.ARRAY causes schema validation failure on production
-- with ddl-auto: validate. JSONB is consistent with how required_skills is stored.

ALTER TABLE "tasks"
    ALTER COLUMN "tags" TYPE JSONB
        USING to_jsonb("tags");

COMMENT ON COLUMN "tasks"."tags" IS 'JSONB Array: vd ["Bug", "Urgent"]';

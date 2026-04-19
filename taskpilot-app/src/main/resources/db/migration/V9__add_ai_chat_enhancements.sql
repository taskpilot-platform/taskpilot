-- Add model tracking columns to ai_logs
ALTER TABLE "ai_logs"
    ADD COLUMN IF NOT EXISTS "model_used"   VARCHAR(50),
    ADD COLUMN IF NOT EXISTS "tokens_used"  INT,
    ADD COLUMN IF NOT EXISTS "duration_ms"  INT,
    ADD COLUMN IF NOT EXISTS "session_id"   BIGINT REFERENCES "chat_sessions" ("id") ON DELETE SET NULL;

-- Add updated_at to chat_sessions for better UX (sort by recent activity)
ALTER TABLE "chat_sessions"
    ADD COLUMN IF NOT EXISTS "updated_at" TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;

-- Performance indexes for AI Chat queries
CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_id
    ON "chat_sessions" ("user_id", "updated_at" DESC);

CREATE INDEX IF NOT EXISTS idx_chat_messages_session_created
    ON "chat_messages" ("session_id", "created_at" ASC);

CREATE INDEX IF NOT EXISTS idx_ai_logs_user_created
    ON "ai_logs" ("user_id", "created_at" DESC);

CREATE INDEX IF NOT EXISTS idx_ai_logs_session_id
    ON "ai_logs" ("session_id");

COMMENT ON COLUMN "ai_logs"."model_used" IS 'LangChain4j model bean used: gemini-2.5-flash, gpt-4o, DeepSeek-R1';
COMMENT ON COLUMN "ai_logs"."tokens_used" IS 'Estimated tokens consumed by this AI interaction';
COMMENT ON COLUMN "ai_logs"."duration_ms" IS 'Wall-clock time in milliseconds from request to first/final token';
COMMENT ON COLUMN "ai_logs"."session_id" IS 'FK to chat_sessions - links log to a specific chat session';
COMMENT ON COLUMN "chat_sessions"."updated_at" IS 'Last activity time in this session (updated on each new message)';

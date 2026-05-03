CREATE TABLE IF NOT EXISTS "ai_chat_memories" (
    "session_id" BIGINT PRIMARY KEY,
    "messages_json" TEXT NOT NULL,
    "created_at" TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);


DO $$ BEGIN
    IF NOT EXISTS (
        SELECT
            1
        FROM
            pg_constraint
        WHERE
            conname = 'fk_ai_chat_memories_session'
    ) THEN
    ALTER TABLE
        "ai_chat_memories"
    ADD
        CONSTRAINT fk_ai_chat_memories_session FOREIGN KEY ("session_id") REFERENCES "chat_sessions" ("id") ON
    DELETE
        CASCADE;


END IF;


END $$;


CREATE INDEX IF NOT EXISTS idx_ai_chat_memories_updated ON "ai_chat_memories" ("updated_at" DESC);


COMMENT ON TABLE "ai_chat_memories" IS 'Persistent LangChain4j chat memory snapshots by session';


COMMENT ON COLUMN "ai_chat_memories"."messages_json" IS 'Serialized List<ChatMessage> in JSON format';
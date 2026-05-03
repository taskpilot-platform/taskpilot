ALTER TABLE
    "chat_messages"
ADD
    COLUMN IF NOT EXISTS "client_message_id" VARCHAR(128);


CREATE INDEX IF NOT EXISTS idx_chat_messages_session_sender_client_id ON "chat_messages" ("session_id", "sender", "client_message_id");


CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_messages_user_client_id ON "chat_messages" ("session_id", "client_message_id")
WHERE
    "sender" = 'USER'
    AND "client_message_id" IS NOT NULL;


COMMENT ON COLUMN "chat_messages"."client_message_id" IS 'Client-side idempotency key to deduplicate retried SSE chat requests';
ALTER TABLE conversations
    ADD COLUMN IF NOT EXISTS archived_context TEXT,
    ADD COLUMN IF NOT EXISTS archived_message_count INT NOT NULL DEFAULT 0;

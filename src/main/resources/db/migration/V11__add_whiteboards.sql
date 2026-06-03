CREATE TABLE IF NOT EXISTS whiteboards (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    document_id     BIGINT NULL,
    exercise_label  TEXT NULL,
    title           TEXT NOT NULL,
    data            TEXT NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_whiteboards_conversation
    ON whiteboards(conversation_id);

CREATE INDEX IF NOT EXISTS idx_whiteboards_exercise
    ON whiteboards(conversation_id, exercise_label);

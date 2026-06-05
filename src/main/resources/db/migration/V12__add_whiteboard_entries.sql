ALTER TABLE whiteboards ADD COLUMN IF NOT EXISTS mode   TEXT NOT NULL DEFAULT 'default';
ALTER TABLE whiteboards ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'ACTIVE';

CREATE TABLE IF NOT EXISTS whiteboard_entries (
    id              BIGSERIAL PRIMARY KEY,
    whiteboard_id   BIGINT NOT NULL REFERENCES whiteboards(id) ON DELETE CASCADE,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    type            TEXT NOT NULL DEFAULT 'TEXT',
    content         TEXT NOT NULL,
    order_index     INT  NOT NULL DEFAULT 0,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_wb_entries_whiteboard    ON whiteboard_entries(whiteboard_id);
CREATE INDEX IF NOT EXISTS idx_wb_entries_conversation  ON whiteboard_entries(conversation_id);
CREATE INDEX IF NOT EXISTS idx_wb_entries_order         ON whiteboard_entries(whiteboard_id, order_index);

CREATE TABLE IF NOT EXISTS reasoning_nodes (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    whiteboard_id   BIGINT NULL    REFERENCES whiteboards(id)    ON DELETE SET NULL,
    parent_node_id  BIGINT NULL    REFERENCES reasoning_nodes(id) ON DELETE SET NULL,
    node_type       TEXT NOT NULL,
    title           TEXT NOT NULL,
    description     TEXT NULL,
    status          TEXT NOT NULL DEFAULT 'PENDING',
    level           INT  NOT NULL DEFAULT 0,
    order_index     INT  NOT NULL DEFAULT 0,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_reasoning_conversation ON reasoning_nodes(conversation_id);
CREATE INDEX IF NOT EXISTS idx_reasoning_whiteboard   ON reasoning_nodes(whiteboard_id);
CREATE INDEX IF NOT EXISTS idx_reasoning_parent       ON reasoning_nodes(parent_node_id);
CREATE INDEX IF NOT EXISTS idx_reasoning_order        ON reasoning_nodes(conversation_id, level, order_index);

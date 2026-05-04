-- =========================
-- EXTENSION
-- =========================
CREATE EXTENSION IF NOT EXISTS vector;

-- =========================
-- CONVERSATIONS
-- =========================
CREATE TABLE conversations (
                               id BIGSERIAL PRIMARY KEY,
                               title TEXT,
                               created_at TIMESTAMP DEFAULT NOW()
);

-- =========================
-- MESSAGES (chat core)
-- =========================
CREATE TABLE messages (
                          id BIGSERIAL PRIMARY KEY,
                          conversation_id BIGINT REFERENCES conversations(id) ON DELETE CASCADE,
                          role TEXT CHECK (role IN ('user', 'assistant')),
                          content TEXT NOT NULL,
                          created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_messages_conversation_id
    ON messages(conversation_id);

-- =========================
-- MESSAGE EMBEDDINGS
-- =========================
CREATE TABLE message_embeddings (
                                    id BIGSERIAL PRIMARY KEY,
                                    message_id BIGINT REFERENCES messages(id) ON DELETE CASCADE,
                                    embedding VECTOR(768),
                                    created_at TIMESTAMP DEFAULT NOW()
);

-- índice vectorial (cosine)
CREATE INDEX idx_message_embeddings_vector
    ON message_embeddings
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

-- =========================
-- DOCUMENTS (para RAG externo)
-- =========================
CREATE TABLE documents (
                           id BIGSERIAL PRIMARY KEY,
                           source TEXT,         -- ej: "pdf", "web", "nota"
                           content TEXT NOT NULL,
                           created_at TIMESTAMP DEFAULT NOW()
);

-- =========================
-- DOCUMENT EMBEDDINGS
-- =========================
CREATE TABLE document_embeddings (
                                     id BIGSERIAL PRIMARY KEY,
                                     document_id BIGINT REFERENCES documents(id) ON DELETE CASCADE,
                                     embedding VECTOR(768),
                                     created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_document_embeddings_vector
    ON document_embeddings
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
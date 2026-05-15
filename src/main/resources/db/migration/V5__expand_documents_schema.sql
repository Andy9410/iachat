-- Expand documents table with full metadata
ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS user_email   VARCHAR(150),
    ADD COLUMN IF NOT EXISTS filename     TEXT,
    ADD COLUMN IF NOT EXISTS file_type    VARCHAR(20),
    ADD COLUMN IF NOT EXISTS upload_date  TIMESTAMP DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS page_count   INT,
    ADD COLUMN IF NOT EXISTS status       VARCHAR(20) NOT NULL DEFAULT 'ready';

-- Expand document_embeddings with chunk metadata
ALTER TABLE document_embeddings
    ADD COLUMN IF NOT EXISTS chunk_text  TEXT,
    ADD COLUMN IF NOT EXISTS chunk_index INT,
    ADD COLUMN IF NOT EXISTS metadata    JSONB,
    ADD COLUMN IF NOT EXISTS page_number INT;

-- Indexes for user-scoped queries
CREATE INDEX IF NOT EXISTS idx_documents_user_email
    ON documents(user_email);

CREATE INDEX IF NOT EXISTS idx_documents_content_hash
    ON documents(content_hash);

CREATE INDEX IF NOT EXISTS idx_document_embeddings_document_id
    ON document_embeddings(document_id);

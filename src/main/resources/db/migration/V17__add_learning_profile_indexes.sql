CREATE INDEX IF NOT EXISTS idx_documents_user_status
    ON documents(user_email, status);

CREATE INDEX IF NOT EXISTS idx_messages_conversation_role_created_at
    ON messages(conversation_id, role, created_at);

CREATE INDEX IF NOT EXISTS idx_document_embeddings_exercise_ref
    ON document_embeddings ((LOWER(BTRIM(metadata->>'exercise_ref'))))
    WHERE metadata->>'exercise_ref' IS NOT NULL;

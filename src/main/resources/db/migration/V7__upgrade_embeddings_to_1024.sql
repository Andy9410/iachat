-- V7__upgrade_embeddings_to_1024.sql

DO $$
BEGIN
    -- Limpiar embeddings incompatibles
TRUNCATE TABLE document_embeddings;

-- Solo alterar si todavía es vector(768)
IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'document_embeddings'
          AND column_name = 'embedding'
    ) THEN

ALTER TABLE document_embeddings
ALTER COLUMN embedding TYPE vector(1024);

END IF;
END $$;
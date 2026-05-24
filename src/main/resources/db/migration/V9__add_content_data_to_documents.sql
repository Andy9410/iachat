-- V9__add_content_data_to_documents.sql
--
-- Agrega columna content_data (BYTEA) a la tabla documents para almacenar
-- el binario original del archivo (PDF/imagen).
--
-- El document-service (Python) necesita esta columna para:
--   - Guardar el binario durante la ingesta (insert_document)
--   - Servir la descarga del archivo (GET /{doc_id}/download)
--
-- Sin esta columna, el endpoint de descarga devuelve 404 y el frontend
-- muestra "No se pudo cargar el documento".

ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS content_data BYTEA;

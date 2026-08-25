-- Index creation belongs to versioned migrations, not application startup hooks.
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'conversations'
                     AND index_name = 'idx_conversations_user_id');
SET @idx_sql = IF(@idx_exists = 0,
                  'CREATE INDEX idx_conversations_user_id ON conversations(user_id)', 'SELECT 1');
PREPARE idx_statement FROM @idx_sql; EXECUTE idx_statement; DEALLOCATE PREPARE idx_statement;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'conversations'
                     AND index_name = 'idx_conversations_updated_at');
SET @idx_sql = IF(@idx_exists = 0,
                  'CREATE INDEX idx_conversations_updated_at ON conversations(updated_at)', 'SELECT 1');
PREPARE idx_statement FROM @idx_sql; EXECUTE idx_statement; DEALLOCATE PREPARE idx_statement;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'documents'
                     AND index_name = 'idx_documents_uploaded_by');
SET @idx_sql = IF(@idx_exists = 0,
                  'CREATE INDEX idx_documents_uploaded_by ON documents(uploaded_by)', 'SELECT 1');
PREPARE idx_statement FROM @idx_sql; EXECUTE idx_statement; DEALLOCATE PREPARE idx_statement;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'documents'
                     AND index_name = 'idx_documents_file_type');
SET @idx_sql = IF(@idx_exists = 0,
                  'CREATE INDEX idx_documents_file_type ON documents(file_type)', 'SELECT 1');
PREPARE idx_statement FROM @idx_sql; EXECUTE idx_statement; DEALLOCATE PREPARE idx_statement;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'documents'
                     AND index_name = 'idx_documents_processing_status');
SET @idx_sql = IF(@idx_exists = 0,
                  'CREATE INDEX idx_documents_processing_status ON documents(processing_status)', 'SELECT 1');
PREPARE idx_statement FROM @idx_sql; EXECUTE idx_statement; DEALLOCATE PREPARE idx_statement;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'documents'
                     AND index_name = 'idx_documents_created_at');
SET @idx_sql = IF(@idx_exists = 0,
                  'CREATE INDEX idx_documents_created_at ON documents(created_at)', 'SELECT 1');
PREPARE idx_statement FROM @idx_sql; EXECUTE idx_statement; DEALLOCATE PREPARE idx_statement;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'document_chunks'
                     AND index_name = 'idx_document_chunks_document_chunk');
SET @idx_sql = IF(@idx_exists = 0,
                  'CREATE INDEX idx_document_chunks_document_chunk ON document_chunks(document_id, chunk_index)',
                  'SELECT 1');
PREPARE idx_statement FROM @idx_sql; EXECUTE idx_statement; DEALLOCATE PREPARE idx_statement;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'ecommerce_qa_pairs'
                     AND index_name = 'idx_ecommerce_qa_created_at');
SET @idx_sql = IF(@idx_exists = 0,
                  'CREATE INDEX idx_ecommerce_qa_created_at ON ecommerce_qa_pairs(created_at)', 'SELECT 1');
PREPARE idx_statement FROM @idx_sql; EXECUTE idx_statement; DEALLOCATE PREPARE idx_statement;

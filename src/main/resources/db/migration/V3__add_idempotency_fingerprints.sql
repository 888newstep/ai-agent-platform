-- Stable content fingerprints make replayed uploads/import batches safe.
SET @document_content_hash_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'documents'
      AND column_name = 'content_hash'
);
SET @document_content_hash_sql = IF(
    @document_content_hash_exists = 0,
    'ALTER TABLE documents ADD COLUMN content_hash VARCHAR(64) NULL AFTER file_size',
    'SELECT 1'
);
PREPARE document_content_hash_statement FROM @document_content_hash_sql;
EXECUTE document_content_hash_statement;
DEALLOCATE PREPARE document_content_hash_statement;

SET @document_content_hash_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'documents'
      AND index_name = 'uk_documents_content_hash'
);
SET @document_content_hash_index_sql = IF(
    @document_content_hash_index_exists = 0,
    'ALTER TABLE documents ADD UNIQUE KEY uk_documents_content_hash (content_hash)',
    'SELECT 1'
);
PREPARE document_content_hash_index_statement FROM @document_content_hash_index_sql;
EXECUTE document_content_hash_index_statement;
DEALLOCATE PREPARE document_content_hash_index_statement;

SET @qa_record_hash_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'ecommerce_qa_pairs'
      AND column_name = 'record_hash'
);
SET @qa_record_hash_sql = IF(
    @qa_record_hash_exists = 0,
    'ALTER TABLE ecommerce_qa_pairs ADD COLUMN record_hash VARCHAR(64) NULL AFTER source_file',
    'SELECT 1'
);
PREPARE qa_record_hash_statement FROM @qa_record_hash_sql;
EXECUTE qa_record_hash_statement;
DEALLOCATE PREPARE qa_record_hash_statement;

SET @qa_record_hash_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'ecommerce_qa_pairs'
      AND index_name = 'uk_ecommerce_qa_record_hash'
);
SET @qa_record_hash_index_sql = IF(
    @qa_record_hash_index_exists = 0,
    'ALTER TABLE ecommerce_qa_pairs ADD UNIQUE KEY uk_ecommerce_qa_record_hash (record_hash)',
    'SELECT 1'
);
PREPARE qa_record_hash_index_statement FROM @qa_record_hash_index_sql;
EXECUTE qa_record_hash_index_statement;
DEALLOCATE PREPARE qa_record_hash_index_statement;

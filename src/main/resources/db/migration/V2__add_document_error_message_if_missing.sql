-- Keep existing installations compatible with the current Document entity.
SET @document_error_message_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'documents'
      AND column_name = 'error_message'
);
SET @document_error_message_sql = IF(
    @document_error_message_exists = 0,
    'ALTER TABLE documents ADD COLUMN error_message VARCHAR(1000) NULL AFTER processing_status',
    'SELECT 1'
);
PREPARE document_error_message_statement FROM @document_error_message_sql;
EXECUTE document_error_message_statement;
DEALLOCATE PREPARE document_error_message_statement;
-- Staging metadata lets document ingestion survive process restarts without storing file blobs in MySQL.
SET @document_content_type_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'documents' AND column_name = 'content_type'
);
SET @document_content_type_sql = IF(
    @document_content_type_exists = 0,
    'ALTER TABLE documents ADD COLUMN content_type VARCHAR(255) NULL AFTER content_hash',
    'SELECT 1'
);
PREPARE document_content_type_statement FROM @document_content_type_sql;
EXECUTE document_content_type_statement;
DEALLOCATE PREPARE document_content_type_statement;

SET @document_staging_path_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'documents' AND column_name = 'staging_path'
);
SET @document_staging_path_sql = IF(
    @document_staging_path_exists = 0,
    'ALTER TABLE documents ADD COLUMN staging_path VARCHAR(500) NULL AFTER content_type',
    'SELECT 1'
);
PREPARE document_staging_path_statement FROM @document_staging_path_sql;
EXECUTE document_staging_path_statement;
DEALLOCATE PREPARE document_staging_path_statement;

SET @document_parser_version_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'documents' AND column_name = 'parser_version'
);
SET @document_parser_version_sql = IF(
    @document_parser_version_exists = 0,
    'ALTER TABLE documents ADD COLUMN parser_version VARCHAR(50) NULL AFTER staging_path',
    'SELECT 1'
);
PREPARE document_parser_version_statement FROM @document_parser_version_sql;
EXECUTE document_parser_version_statement;
DEALLOCATE PREPARE document_parser_version_statement;

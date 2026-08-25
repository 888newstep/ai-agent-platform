ALTER TABLE messages MODIFY COLUMN content MEDIUMTEXT NOT NULL;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'messages'
                     AND index_name = 'idx_messages_session_id_id');
SET @idx_sql = IF(@idx_exists = 0,
                  'CREATE INDEX idx_messages_session_id_id ON messages(session_id, id)', 'SELECT 1');
PREPARE idx_statement FROM @idx_sql; EXECUTE idx_statement; DEALLOCATE PREPARE idx_statement;

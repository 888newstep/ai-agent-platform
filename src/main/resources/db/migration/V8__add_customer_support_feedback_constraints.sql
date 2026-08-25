SET @duplicate_feedback_exists = (
    SELECT COUNT(*)
    FROM (
        SELECT session_id, message_id
        FROM ecommerce_feedback
        WHERE message_id IS NOT NULL
        GROUP BY session_id, message_id
        HAVING COUNT(*) > 1
    ) duplicates
);

SET @deduplicate_feedback_sql = IF(
    @duplicate_feedback_exists > 0,
    'DELETE older FROM ecommerce_feedback older JOIN ecommerce_feedback newer ON older.session_id = newer.session_id AND older.message_id = newer.message_id AND older.id < newer.id WHERE older.message_id IS NOT NULL',
    'SELECT 1'
);
PREPARE feedback_statement FROM @deduplicate_feedback_sql;
EXECUTE feedback_statement;
DEALLOCATE PREPARE feedback_statement;

DELETE feedback
FROM ecommerce_feedback feedback
LEFT JOIN messages message ON message.id = feedback.message_id
WHERE feedback.message_id IS NOT NULL
  AND message.id IS NULL;

SET @feedback_fk_exists = (
    SELECT COUNT(*)
    FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE()
      AND table_name = 'ecommerce_feedback'
      AND constraint_name = 'fk_ecommerce_feedback_message'
      AND constraint_type = 'FOREIGN KEY'
);
SET @feedback_fk_sql = IF(
    @feedback_fk_exists = 0,
    'ALTER TABLE ecommerce_feedback ADD CONSTRAINT fk_ecommerce_feedback_message FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE',
    'SELECT 1'
);
PREPARE feedback_statement FROM @feedback_fk_sql;
EXECUTE feedback_statement;
DEALLOCATE PREPARE feedback_statement;

SET @feedback_unique_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'ecommerce_feedback'
      AND index_name = 'uk_ecommerce_feedback_session_message'
);
SET @feedback_unique_sql = IF(
    @feedback_unique_exists = 0,
    'CREATE UNIQUE INDEX uk_ecommerce_feedback_session_message ON ecommerce_feedback(session_id, message_id)',
    'SELECT 1'
);
PREPARE feedback_statement FROM @feedback_unique_sql;
EXECUTE feedback_statement;
DEALLOCATE PREPARE feedback_statement;

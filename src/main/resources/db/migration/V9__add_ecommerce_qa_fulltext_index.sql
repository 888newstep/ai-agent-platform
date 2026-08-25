SET @qa_fulltext_index_exists = (
    SELECT COUNT(*)
      FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'ecommerce_qa_pairs'
       AND index_name = 'idx_ecommerce_qa_question_ft'
);

SET @qa_fulltext_index_sql = IF(
    @qa_fulltext_index_exists = 0,
    'ALTER TABLE ecommerce_qa_pairs ADD FULLTEXT INDEX idx_ecommerce_qa_question_ft (question) WITH PARSER ngram',
    'SELECT 1'
);

PREPARE qa_fulltext_index_statement FROM @qa_fulltext_index_sql;
EXECUTE qa_fulltext_index_statement;
DEALLOCATE PREPARE qa_fulltext_index_statement;

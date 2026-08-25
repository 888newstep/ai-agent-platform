package com.aiagent.ecommerce.infrastructure.repository;

import com.aiagent.ecommerce.domain.EcommerceQaPair;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EcommerceQaPairRepository extends JpaRepository<EcommerceQaPair, Long> {

    Optional<EcommerceQaPair> findByRecordHash(String recordHash);

    List<EcommerceQaPair> findAllByRecordHashIn(Collection<String> recordHashes);

    long countByIdInAndStatus(Collection<Long> ids, Integer status);

    @Query(value = """
            SELECT qa.id AS id,
                   qa.question AS question,
                   qa.answer AS answer,
                   qa.qa_text AS qaText,
                   qa.category AS category,
                   MATCH(qa.question) AGAINST (:query IN NATURAL LANGUAGE MODE) AS lexicalScore
              FROM ecommerce_qa_pairs qa
             WHERE qa.status = 1
               AND MATCH(qa.question) AGAINST (:query IN NATURAL LANGUAGE MODE) > 0
             ORDER BY lexicalScore DESC, qa.id ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<LexicalCandidate> searchQuestionFullText(@Param("query") String query,
                                                  @Param("limit") int limit);

    @Modifying
    @Query("""
            update EcommerceQaPair qa
               set qa.hitCount = coalesce(qa.hitCount, 0) + 1,
                   qa.lastHitAt = :hitAt
             where qa.id in :ids
               and qa.status = 1
            """)
    int incrementHitCount(@Param("ids") Collection<Long> ids, @Param("hitAt") LocalDateTime hitAt);

    interface LexicalCandidate {
        Long getId();

        String getQuestion();

        String getAnswer();

        String getQaText();

        String getCategory();

        Double getLexicalScore();
    }
}

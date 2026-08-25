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

    @Modifying
    @Query("""
            update EcommerceQaPair qa
               set qa.hitCount = coalesce(qa.hitCount, 0) + 1,
                   qa.lastHitAt = :hitAt
             where qa.id in :ids
               and qa.status = 1
            """)
    int incrementHitCount(@Param("ids") Collection<Long> ids, @Param("hitAt") LocalDateTime hitAt);
}

package com.aiagent.chat.infrastructure.repository;

import com.aiagent.chat.domain.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<Message> findBySessionIdOrderByIdDesc(String sessionId, Pageable pageable);

    Optional<Message> findByIdAndSessionId(Long id, String sessionId);

    Optional<Message> findFirstBySessionIdAndRoleAndIdLessThanOrderByIdDesc(
            String sessionId, String role, Long id);

    @Modifying
    void deleteBySessionId(@Param("sessionId") String sessionId);
}

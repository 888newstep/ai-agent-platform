package com.aiagent.repository;

import com.aiagent.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findBySessionIdOrderByCreatedAtAsc(String sessionId);
    List<Message> findBySessionIdAndCreatedAtAfterOrderByCreatedAtAsc(String sessionId, LocalDateTime after);
    void deleteBySessionId(String sessionId);
    long countBySessionId(String sessionId);
}
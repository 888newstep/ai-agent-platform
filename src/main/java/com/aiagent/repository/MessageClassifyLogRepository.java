package com.aiagent.repository;

import com.aiagent.entity.MessageClassifyLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageClassifyLogRepository extends JpaRepository<MessageClassifyLog, Long> {
    List<MessageClassifyLog> findByClassifiedType(String classifiedType);
    List<MessageClassifyLog> findBySessionIdOrderByCreatedAtDesc(String sessionId);
    long countByClassifiedType(String classifiedType);
}
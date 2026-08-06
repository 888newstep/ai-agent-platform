package com.aiagent.chat.infrastructure.repository;

import com.aiagent.chat.domain.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    @Modifying
    void deleteBySessionId(@Param("sessionId") String sessionId);
}

package com.aiagent.repository;

import com.aiagent.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    Optional<Conversation> findBySessionId(String sessionId);
    List<Conversation> findByUserIdOrderByUpdatedAtDesc(Long userId);
}

package com.aiagent.chat.infrastructure.repository;

import com.aiagent.chat.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    Optional<Conversation> findBySessionId(String sessionId);
}

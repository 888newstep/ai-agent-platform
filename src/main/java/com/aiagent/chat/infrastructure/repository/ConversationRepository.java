package com.aiagent.chat.infrastructure.repository;

import com.aiagent.chat.domain.Conversation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    Optional<Conversation> findBySessionId(String sessionId);

    Optional<Conversation> findBySessionIdAndUserId(String sessionId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select conversation from Conversation conversation "
            + "where conversation.sessionId = :sessionId and conversation.userId = :userId")
    Optional<Conversation> findOwnedForUpdate(@Param("sessionId") String sessionId,
                                               @Param("userId") Long userId);
}

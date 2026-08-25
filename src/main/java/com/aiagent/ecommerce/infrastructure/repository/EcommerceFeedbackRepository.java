package com.aiagent.ecommerce.infrastructure.repository;

import com.aiagent.ecommerce.domain.EcommerceFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EcommerceFeedbackRepository extends JpaRepository<EcommerceFeedback, Long> {
    Optional<EcommerceFeedback> findBySessionIdAndMessageId(String sessionId, Long messageId);
}

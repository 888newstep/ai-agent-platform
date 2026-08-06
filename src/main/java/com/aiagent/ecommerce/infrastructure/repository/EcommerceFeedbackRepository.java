package com.aiagent.ecommerce.infrastructure.repository;

import com.aiagent.ecommerce.domain.EcommerceFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EcommerceFeedbackRepository extends JpaRepository<EcommerceFeedback, Long> {
}

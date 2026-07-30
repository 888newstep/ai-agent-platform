package com.aiagent.repository;

import com.aiagent.entity.EcommerceFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EcommerceFeedbackRepository extends JpaRepository<EcommerceFeedback, Long> {
    List<EcommerceFeedback> findBySessionIdOrderByCreatedAtDesc(String sessionId);
    List<EcommerceFeedback> findByRating(Integer rating);
    long countByRating(Integer rating);
}
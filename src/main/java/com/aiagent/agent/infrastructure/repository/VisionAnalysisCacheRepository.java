package com.aiagent.agent.infrastructure.repository;

import com.aiagent.agent.domain.VisionAnalysisCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VisionAnalysisCacheRepository extends JpaRepository<VisionAnalysisCache, Long> {
    Optional<VisionAnalysisCache> findByImageHashAndModelName(String imageHash, String modelName);
}

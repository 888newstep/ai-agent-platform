package com.aiagent.repository;

import com.aiagent.entity.VisionAnalysisCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VisionAnalysisCacheRepository extends JpaRepository<VisionAnalysisCache, Long> {
    Optional<VisionAnalysisCache> findByImageHashAndModelName(String imageHash, String modelName);
    void deleteByImageHash(String imageHash);
}
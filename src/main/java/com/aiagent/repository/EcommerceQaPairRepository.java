package com.aiagent.repository;

import com.aiagent.entity.EcommerceQaPair;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EcommerceQaPairRepository extends JpaRepository<EcommerceQaPair, Long> {
    List<EcommerceQaPair> findByCategory(String category);
    List<EcommerceQaPair> findByStatus(Integer status);
    List<EcommerceQaPair> findBySourceFile(String sourceFile);
    long countByCategory(String category);
}
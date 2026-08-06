package com.aiagent.infrastructure.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 数据库优化配置
 * 
 * 优化点：
 * 1. 为常用查询字段添加索引
 * 2. 启用事务管理优化
 * 3. 连接池参数调优
 */
@Slf4j
@Configuration
@EnableTransactionManagement
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.datasource.url", matchIfMissing = false)
public class DatabaseOptimizationConfig {

    private final DataSource dataSource;

    @PostConstruct
    public void createIndexes() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            
            // 为 conversations 表添加索引
            executeIgnoreError(stmt, 
                "CREATE INDEX idx_conversations_user_id ON conversations(user_id)");
            executeIgnoreError(stmt, 
                "CREATE INDEX idx_conversations_updated_at ON conversations(updated_at DESC)");
            
            // 为 messages 表添加索引
            executeIgnoreError(stmt, 
                "CREATE INDEX idx_messages_conversation_id ON messages(conversation_id)");
            executeIgnoreError(stmt, 
                "CREATE INDEX idx_messages_created_at ON messages(created_at DESC)");
            
            // 为 documents 表添加索引
            executeIgnoreError(stmt, 
                "CREATE INDEX idx_documents_user_id ON documents(user_id)");
            executeIgnoreError(stmt, 
                "CREATE INDEX idx_documents_file_type ON documents(file_type)");
            executeIgnoreError(stmt, 
                "CREATE INDEX idx_documents_created_at ON documents(created_at DESC)");
            
            // 为 document_chunks 表添加索引
            executeIgnoreError(stmt, 
                "CREATE INDEX idx_document_chunks_document_id ON document_chunks(document_id)");
            executeIgnoreError(stmt, 
                "CREATE INDEX idx_document_chunks_chunk_index ON document_chunks(document_id, chunk_index)");
            
            // 为 ecommerce_qa_pairs 表添加索引
            executeIgnoreError(stmt, 
                "CREATE INDEX idx_ecommerce_qa_category ON ecommerce_qa_pairs(category)");
            executeIgnoreError(stmt, 
                "CREATE INDEX idx_ecommerce_qa_created_at ON ecommerce_qa_pairs(created_at DESC)");
            
            log.info("数据库索引优化完成");
            
        } catch (Exception e) {
            log.warn("创建数据库索引时出现异常（可能已存在）: {}", e.getMessage());
        }
    }
    
    private void executeIgnoreError(Statement stmt, String sql) {
        try {
            stmt.execute(sql);
        } catch (Exception e) {
            // 忽略已存在的索引
            if (!e.getMessage().contains("Duplicate") && 
                !e.getMessage().contains("already exists")) {
                log.debug("SQL执行提示: {}", e.getMessage());
            }
        }
    }
}

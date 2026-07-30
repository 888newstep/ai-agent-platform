-- ============================================================
-- AI Agent Platform 数据库初始化脚本
-- 环境: MySQL 8.x, 字符集 utf8mb4
-- 设计原则: MySQL 是"源"，Milvus 是"索引"，Redis 是"缓存"
-- ============================================================

CREATE DATABASE IF NOT EXISTS ai_agent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ai_agent;

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `vision_analysis_cache`;
DROP TABLE IF EXISTS `message_classify_log`;
DROP TABLE IF EXISTS `ecommerce_feedback`;
DROP TABLE IF EXISTS `ecommerce_qa_pairs`;
DROP TABLE IF EXISTS `document_chunks`;
DROP TABLE IF EXISTS `messages`;
DROP TABLE IF EXISTS `documents`;
DROP TABLE IF EXISTS `conversations`;
DROP TABLE IF EXISTS `users`;

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 1. 用户表 (对应 User.java, @Table(name = "users"))
-- ============================================================
CREATE TABLE `users` (
    `id`          BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`    VARCHAR(50)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(255) NOT NULL COMMENT '密码(BCrypt加密)',
    `email`       VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `phone`       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `enabled`     BIT(1) NOT NULL DEFAULT b'1' COMMENT '是否启用: 1启用 0禁用',
    `created_at`  DATETIME(6)  NOT NULL COMMENT '创建时间',
    `updated_at`  DATETIME(6)  DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_users_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ============================================================
-- 2. 会话表 (对应 Conversation.java, @Table(name = "conversations"))
--    只存会话元数据，消息内容存 messages 表
-- ============================================================
CREATE TABLE `conversations` (
    `id`            BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`    VARCHAR(100) NOT NULL COMMENT '会话ID(UUID)',
    `user_id`       BIGINT       DEFAULT NULL COMMENT '所属用户ID',
    `title`         VARCHAR(255) DEFAULT NULL COMMENT '会话标题(自动生成)',
    `message_count` INT DEFAULT(0) COMMENT '消息数量',
    `model_chain`   JSON         DEFAULT NULL COMMENT '该会话默认模型调用链',
    `created_at`    DATETIME(6)  NOT NULL COMMENT '创建时间',
    `updated_at`    DATETIME(6)  DEFAULT NULL COMMENT '最后活跃时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversations_session_id` (`session_id`),
    KEY `idx_conversations_user_id` (`user_id`),
    KEY `idx_conversations_updated` (`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话表';

-- ============================================================
-- 3. 消息明细表 (持久化所有对话，Redis 做热缓存)
--    支持多模型调用链记录
-- ============================================================
CREATE TABLE `messages` (
    `id`            BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`    VARCHAR(100) NOT NULL COMMENT '会话ID',
    `role`          VARCHAR(20)  NOT NULL COMMENT '角色: user/assistant/system',
    `content`       TEXT         NOT NULL COMMENT '消息内容',
    `msg_type`      VARCHAR(30)  DEFAULT 'text' COMMENT '消息类型: text/rag/multi_model',
    `model_chain`   JSON         DEFAULT NULL COMMENT '模型调用链(JSON)',
    `rag_chunks`    JSON         DEFAULT NULL COMMENT '检索到的RAG chunk信息(JSON)',
    `tokens_used`   INT          DEFAULT NULL COMMENT '消耗Token数',
    `model_name`    VARCHAR(100) DEFAULT NULL COMMENT '最终回复使用的模型',
    `latency_ms`    INT          DEFAULT NULL COMMENT '总响应耗时(毫秒)',
    `created_at`    DATETIME(6)  NOT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_messages_session_id` (`session_id`),
    KEY `idx_messages_created_at` (`created_at`),
    KEY `idx_messages_msg_type` (`msg_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息明细表';

-- ============================================================
-- 4. 文档表 (对应 Document.java, @Table(name = "documents"))
--    记录上传文档的元信息
-- ============================================================
CREATE TABLE `documents` (
    `id`              BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `file_name`       VARCHAR(255) NOT NULL COMMENT '文件名',
    `file_type`       VARCHAR(50)  DEFAULT NULL COMMENT '文件类型(pdf/docx/md/txt)',
    `file_size`       BIGINT       DEFAULT NULL COMMENT '文件大小(字节)',
    `vector_store_id` VARCHAR(100) DEFAULT NULL COMMENT 'Milvus中的集合标识',
    `chunk_count`     INT          DEFAULT NULL COMMENT '切分块数量',
    `uploaded_by`     BIGINT       DEFAULT NULL COMMENT '上传人用户ID',
    `created_at`      DATETIME(6)  NOT NULL COMMENT '创建时间',
    `updated_at`      DATETIME(6)  DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_documents_uploaded_by` (`uploaded_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档表';

-- ============================================================
-- 5. 文档 Chunk 明细表 (追踪每个 chunk 原文和向量ID)
--    支持从 MySQL 重建 Milvus 向量
-- ============================================================
CREATE TABLE `document_chunks` (
    `id`              BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `document_id`     BIGINT NOT NULL COMMENT '所属文档ID',
    `chunk_index`     INT NOT NULL COMMENT '块序号(从0开始)',
    `content`         TEXT    NOT NULL COMMENT '块原文内容',
    `char_count`      INT DEFAULT 0 COMMENT '字符数',
    `vector_id`       VARCHAR(100) DEFAULT NULL COMMENT 'Milvus中的向量ID',
    `created_at`      DATETIME(6) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_chunks_document_id` (`document_id`),
    KEY `idx_chunks_vector_id` (`vector_id`),
    CONSTRAINT `fk_chunks_document` FOREIGN KEY (`document_id`) REFERENCES `documents`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档Chunk明细表';

-- ============================================================
-- 6. 电商客服 QA 知识库源数据表
--    MySQL 存完整记录，Milvus 存向量用于检索
-- ============================================================
CREATE TABLE `ecommerce_qa_pairs` (
    `id`              BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `question`        VARCHAR(1024) NOT NULL COMMENT '用户问题(原始)',
    `answer`          TEXT          NOT NULL COMMENT '客服回答(原始)',
    `qa_text`         TEXT          DEFAULT NULL COMMENT '拼接后的embedding文本',
    `category`        VARCHAR(100)  DEFAULT NULL COMMENT '问题分类(售后/物流/退换货/支付等)',
    `source_file`     VARCHAR(255)  DEFAULT NULL COMMENT '来源文件名',
    `vector_id`       VARCHAR(100)  DEFAULT NULL COMMENT 'Milvus中的向量ID',
    `status`          TINYINT       DEFAULT 1 COMMENT '状态: 1启用 0禁用(软删除)',
    `hit_count`       INT           DEFAULT 0 COMMENT '命中次数',
    `last_hit_at`     DATETIME(6)   DEFAULT NULL COMMENT '最后命中时间',
    `created_at`      DATETIME(6)   NOT NULL COMMENT '创建时间',
    `updated_at`      DATETIME(6)   DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_qa_category` (`category`),
    KEY `idx_qa_vector_id` (`vector_id`),
    KEY `idx_qa_status` (`status`),
    KEY `idx_qa_hit_count` (`hit_count`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='电商客服QA知识库源数据表';

-- ============================================================
-- 7. 用户反馈表 (评估 RAG 效果，持续优化知识库)
-- ============================================================
CREATE TABLE `ecommerce_feedback` (
    `id`              BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`      VARCHAR(100) NOT NULL COMMENT '会话ID',
    `message_id`      BIGINT       DEFAULT NULL COMMENT '关联消息ID',
    `question`        TEXT NOT NULL COMMENT '用户问题',
    `answer`          TEXT NOT NULL COMMENT 'AI回答',
    `retrieved_qa`    JSON         DEFAULT NULL COMMENT '检索到的QA对(JSON)',
    `model_chain`     JSON         DEFAULT NULL COMMENT '模型调用链',
    `rating`          TINYINT      DEFAULT NULL COMMENT '用户评分: 1-5',
    `feedback_text`   VARCHAR(500) DEFAULT NULL COMMENT '用户反馈文本',
    `created_at`      DATETIME(6)  NOT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_feedback_session` (`session_id`),
    KEY `idx_feedback_rating` (`rating`),
    KEY `idx_feedback_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户反馈表';

-- ============================================================
-- 8. 消息分类日志表 (多模型路由的决策记录)
--    用于分析分类准确率、优化路由规则
-- ============================================================
CREATE TABLE `message_classify_log` (
    `id`                BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`        VARCHAR(100) NOT NULL COMMENT '会话ID',
    `question_hash`     VARCHAR(64)  NOT NULL COMMENT '问题hash(用于去重分析)',
    `question`          TEXT         NOT NULL COMMENT '原始问题',
    `has_image`         BIT(1)       DEFAULT b'0' COMMENT '是否含图片',
    `classified_type`   VARCHAR(50)  NOT NULL COMMENT '分类结果: simple_vision/deep_vision/text_only',
    `confidence`        DECIMAL(5,4) DEFAULT NULL COMMENT '分类置信度',
    `classifier_model`  VARCHAR(50)  DEFAULT 'doubao-mini' COMMENT '分类器使用的模型',
    `routed_models`     JSON         DEFAULT NULL COMMENT '路由到的模型列表',
    `latency_ms`        INT          DEFAULT NULL COMMENT '分类耗时(毫秒)',
    `created_at`        DATETIME(6)  NOT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_classify_type` (`classified_type`),
    KEY `idx_classify_created` (`created_at`),
    KEY `idx_classify_hash` (`question_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息分类日志表';

-- ============================================================
-- 9. 视觉分析结果缓存表 (避免重复调用多模态模型)
-- ============================================================
CREATE TABLE `vision_analysis_cache` (
    `id`              BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `image_hash`      VARCHAR(64) NOT NULL COMMENT '图片内容hash',
    `model_name`      VARCHAR(50) NOT NULL COMMENT '生成结果的模型: doubao-mini/qwen3.7-flash',
    `result_json`     JSON        NOT NULL COMMENT '结构化分析结果(JSON)',
    `token_used`      INT         DEFAULT NULL COMMENT '消耗Token数',
    `latency_ms`      INT         DEFAULT NULL COMMENT '生成耗时(毫秒)',
    `created_at`      DATETIME(6) NOT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_vision_image_model` (`image_hash`, `model_name`),
    KEY `idx_vision_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='视觉分析结果缓存表';

-- ============================================================
-- 示例数据
-- 密码为 BCrypt("password") 哈希值
-- ============================================================
INSERT INTO `users` (`username`, `password`, `email`, `phone`, `enabled`, `created_at`, `updated_at`) VALUES
('admin', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', 'admin@example.com', '13800000000', b'1', NOW(6), NOW(6)),
('test',  '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', 'test@example.com',  '13900000000', b'1', NOW(6), NOW(6));

INSERT INTO `conversations` (`session_id`, `user_id`, `title`, `message_count`, `created_at`, `updated_at`) VALUES
(UUID(), 1, '电商客服咨询', 4, NOW(6), NOW(6)),
(UUID(), 1, 'RAG文档问答测试', 2, NOW(6), NOW(6));

INSERT INTO `documents` (`file_name`, `file_type`, `file_size`, `vector_store_id`, `chunk_count`, `uploaded_by`, `created_at`, `updated_at`) VALUES
('退货政策说明.pdf', 'pdf', 1048576, UUID(), 42, 1, NOW(6), NOW(6)),
('常见问题解答.md', 'md', 20480, UUID(), 8, 1, NOW(6), NOW(6));

-- ============================================================
-- 验证
-- ============================================================
SELECT '=== 数据库初始化完成 ===' AS status;
SHOW TABLES;
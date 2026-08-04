-- V1__initial_schema.sql
-- Initial database schema for AI Agent Platform

-- Users table
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(100),
    phone VARCHAR(20),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL,
    updated_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Conversations table
CREATE TABLE conversations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL UNIQUE,
    user_id BIGINT,
    title VARCHAR(255),
    message_count INT DEFAULT 0,
    model_chain JSON,
    created_at DATETIME NOT NULL,
    updated_at DATETIME,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Messages table
CREATE TABLE messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    msg_type VARCHAR(30) DEFAULT 'text',
    model_chain JSON,
    rag_chunks JSON,
    tokens_used INT,
    model_name VARCHAR(100),
    latency_ms INT,
    created_at DATETIME NOT NULL,
    INDEX idx_session_id (session_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Documents table
CREATE TABLE documents (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    file_type VARCHAR(50),
    file_size BIGINT,
    vector_store_id VARCHAR(100),
    chunk_count INT,
    processing_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    error_message VARCHAR(1000),
    processing_started_at DATETIME,
    processing_completed_at DATETIME,
    uploaded_by BIGINT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME,
    FOREIGN KEY (uploaded_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Document chunks table
CREATE TABLE document_chunks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    char_count INT DEFAULT 0,
    vector_id VARCHAR(100),
    created_at DATETIME NOT NULL,
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    INDEX idx_document_id (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- E-commerce QA pairs table
CREATE TABLE ecommerce_qa_pairs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    question VARCHAR(1024) NOT NULL,
    answer TEXT NOT NULL,
    qa_text TEXT,
    category VARCHAR(100),
    source_file VARCHAR(255),
    vector_id VARCHAR(100),
    status INT DEFAULT 1,
    hit_count INT DEFAULT 0,
    last_hit_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME,
    INDEX idx_category (category),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- E-commerce feedback table
CREATE TABLE ecommerce_feedback (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL,
    message_id BIGINT,
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    retrieved_qa JSON,
    model_chain JSON,
    rating INT,
    feedback_text VARCHAR(500),
    created_at DATETIME NOT NULL,
    INDEX idx_session_id (session_id),
    INDEX idx_rating (rating)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Message classify log table
CREATE TABLE message_classify_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(100) NOT NULL,
    question_hash VARCHAR(64) NOT NULL,
    question TEXT NOT NULL,
    has_image BOOLEAN DEFAULT FALSE,
    classified_type VARCHAR(50) NOT NULL,
    confidence DECIMAL(5,4),
    classifier_model VARCHAR(50) DEFAULT 'doubao-mini',
    routed_models JSON,
    latency_ms INT,
    created_at DATETIME NOT NULL,
    INDEX idx_session_id (session_id),
    INDEX idx_classified_type (classified_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Vision analysis cache table
CREATE TABLE vision_analysis_cache (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    image_hash VARCHAR(64) NOT NULL,
    model_name VARCHAR(50) NOT NULL,
    result_json JSON NOT NULL,
    token_used INT,
    latency_ms INT,
    created_at DATETIME NOT NULL,
    INDEX idx_image_hash (image_hash),
    INDEX idx_model_name (model_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

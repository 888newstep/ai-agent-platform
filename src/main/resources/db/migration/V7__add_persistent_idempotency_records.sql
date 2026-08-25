CREATE TABLE persistent_idempotency_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    operation_name VARCHAR(64) NOT NULL,
    key_hash VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    session_id VARCHAR(100),
    response_payload MEDIUMTEXT NOT NULL,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT uk_persistent_idempotency_user_operation_key
        UNIQUE (user_id, operation_name, key_hash),
    INDEX idx_persistent_idempotency_expires_at (expires_at),
    CONSTRAINT fk_persistent_idempotency_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

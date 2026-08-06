package com.aiagent.agent.infrastructure.repository;

import com.aiagent.agent.domain.MessageClassifyLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageClassifyLogRepository extends JpaRepository<MessageClassifyLog, Long> {
}

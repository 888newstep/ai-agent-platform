package com.aiagent.chat.application;

import com.aiagent.auth.domain.User;
import com.aiagent.auth.infrastructure.repository.UserRepository;
import com.aiagent.chat.domain.Conversation;
import com.aiagent.chat.domain.Message;
import com.aiagent.chat.infrastructure.repository.ConversationRepository;
import com.aiagent.chat.infrastructure.repository.MessageRepository;
import com.aiagent.infrastructure.idempotency.PersistentIdempotencyContext;
import com.aiagent.infrastructure.idempotency.PersistentIdempotencyService;
import com.aiagent.infrastructure.memory.LongContextManager;
import com.aiagent.shared.exception.AuthenticationRequiredException;
import com.aiagent.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private static final int MAX_HISTORY_LIMIT = 200;
    private static final int MAX_TITLE_LENGTH = 255;

    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final LongContextManager longContextManager;
    private final PersistentIdempotencyService persistentIdempotencyService;

    @Transactional
    public String createSession(String username) {
        return createSession(username, PersistentIdempotencyContext.disabled());
    }

    @Transactional
    public String createSession(String username, PersistentIdempotencyContext idempotencyContext) {
        User user = requireEnabledUser(username);
        Optional<String> completed = persistentIdempotencyService.findCompleted(
                user.getId(), null, idempotencyContext, String.class);
        if (completed.isPresent()) {
            return completed.get();
        }

        String sessionId = UUID.randomUUID().toString();
        conversationRepository.save(Conversation.builder()
                .sessionId(sessionId)
                .userId(user.getId())
                .messageCount(0)
                .build());
        persistentIdempotencyService.saveCompleted(
                user.getId(), sessionId, idempotencyContext, sessionId);
        log.debug("Created persistent chat session: sessionId={}, userId={}", sessionId, user.getId());
        return sessionId;
    }

    @Transactional(readOnly = true)
    public void requireOwnedSession(String username, String sessionId) {
        Long userId = requireEnabledUser(username).getId();
        validateSessionId(sessionId);
        conversationRepository.findBySessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> sessionNotFound(sessionId));
    }

    @Transactional
    public void recordSuccessfulExchange(String username,
                                         String sessionId,
                                         String question,
                                         String answer) {
        recordSuccessfulExchange(
                username,
                sessionId,
                question,
                answer,
                PersistentIdempotencyContext.disabled(),
                answer,
                String.class);
    }

    @Transactional
    public <T> T recordSuccessfulExchange(String username,
                                          String sessionId,
                                          String question,
                                          String answer,
                                          PersistentIdempotencyContext idempotencyContext,
                                          T response,
                                          Class<T> responseType) {
        return recordSuccessfulExchange(
                username,
                sessionId,
                question,
                answer,
                idempotencyContext,
                ChatExchangeMetadata.empty(),
                ignored -> response,
                responseType);
    }

    @Transactional
    public <T> T recordSuccessfulExchange(String username,
                                          String sessionId,
                                          String question,
                                          String answer,
                                          PersistentIdempotencyContext idempotencyContext,
                                          ChatExchangeMetadata metadata,
                                          Function<Long, T> responseFactory,
                                          Class<T> responseType) {
        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("question must not be blank");
        }
        if (!StringUtils.hasText(answer)) {
            throw new IllegalStateException("Model returned an empty answer");
        }
        Long userId = requireEnabledUser(username).getId();
        validateSessionId(sessionId);
        Optional<T> completed = persistentIdempotencyService.findCompleted(
                userId, sessionId, idempotencyContext, responseType);
        if (completed.isPresent()) {
            return completed.get();
        }
        Conversation conversation = conversationRepository.findOwnedForUpdate(sessionId, userId)
                .orElseThrow(() -> sessionNotFound(sessionId));

        Message userMessage = Message.builder()
                .sessionId(sessionId)
                .role("user")
                .content(question)
                .build();
        Message assistantMessage = Message.builder()
                .sessionId(sessionId)
                .role("assistant")
                .content(answer)
                .msgType(resolveMessageType(metadata))
                .modelChain(metadata == null ? null : metadata.modelChain())
                .ragChunks(metadata == null ? null : metadata.ragChunks())
                .build();
        messageRepository.saveAll(List.of(userMessage, assistantMessage));
        messageRepository.flush();
        if (assistantMessage.getId() == null) {
            throw new IllegalStateException("Assistant message id was not generated");
        }

        int currentCount = conversation.getMessageCount() == null ? 0 : conversation.getMessageCount();
        conversation.setMessageCount(Math.addExact(currentCount, 2));
        if (!StringUtils.hasText(conversation.getTitle())) {
            conversation.setTitle(abbreviate(question, MAX_TITLE_LENGTH));
        }
        conversationRepository.save(conversation);
        T response = responseFactory.apply(assistantMessage.getId());
        if (response == null) {
            throw new IllegalStateException("Chat response must not be null");
        }
        persistentIdempotencyService.saveCompleted(userId, sessionId, idempotencyContext, response);

        afterCommit(() -> cacheExchange(sessionId, question, answer));
        return response;
    }

    @Transactional
    public <T> Optional<T> findCompletedResponse(String username,
                                                 String sessionId,
                                                 PersistentIdempotencyContext idempotencyContext,
                                                 Class<T> responseType) {
        Long userId = requireEnabledUser(username).getId();
        validateSessionId(sessionId);
        conversationRepository.findBySessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> sessionNotFound(sessionId));
        return persistentIdempotencyService.findCompleted(
                userId, sessionId, idempotencyContext, responseType);
    }

    @Transactional
    public void deleteSession(String username, String sessionId) {
        Long userId = requireEnabledUser(username).getId();
        validateSessionId(sessionId);
        Conversation conversation = conversationRepository.findOwnedForUpdate(sessionId, userId)
                .orElseThrow(() -> sessionNotFound(sessionId));
        messageRepository.deleteBySessionId(sessionId);
        conversationRepository.delete(conversation);
        afterCommit(() -> clearCachedSession(sessionId));
    }

    @Transactional(readOnly = true)
    public List<ChatMessageView> getRecentMessages(String username, String sessionId, int limit) {
        Long userId = requireEnabledUser(username).getId();
        validateSessionId(sessionId);
        if (limit < 1 || limit > MAX_HISTORY_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_HISTORY_LIMIT);
        }
        conversationRepository.findBySessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> sessionNotFound(sessionId));

        List<Message> descending = messageRepository.findBySessionIdOrderByIdDesc(
                sessionId, PageRequest.of(0, limit));
        List<Message> chronological = new ArrayList<>(descending);
        Collections.reverse(chronological);
        return chronological.stream()
                .map(message -> new ChatMessageView(
                        message.getId(),
                        message.getRole(),
                        message.getContent(),
                        message.getMsgType(),
                        message.getCreatedAt()))
                .toList();
    }

    private User requireEnabledUser(String username) {
        if (!StringUtils.hasText(username)) {
            throw new AuthenticationRequiredException("Authentication required");
        }
        return userRepository.findByUsername(username)
                .filter(user -> Boolean.TRUE.equals(user.getEnabled()))
                .orElseThrow(() -> new AuthenticationRequiredException(
                        "Authenticated user does not exist or is disabled"));
    }

    private void validateSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId) || sessionId.length() > 100) {
            throw new IllegalArgumentException("Invalid sessionId");
        }
        try {
            UUID.fromString(sessionId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid sessionId", exception);
        }
    }

    private ResourceNotFoundException sessionNotFound(String sessionId) {
        return new ResourceNotFoundException("Chat session not found: " + sessionId);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.codePointCount(0, value.length()) <= maxLength) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxLength));
    }

    private String resolveMessageType(ChatExchangeMetadata metadata) {
        if (metadata == null || !StringUtils.hasText(metadata.messageType())) {
            return "text";
        }
        if (metadata.messageType().length() > 30) {
            throw new IllegalArgumentException("messageType must not exceed 30 characters");
        }
        return metadata.messageType();
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void cacheExchange(String sessionId, String question, String answer) {
        try {
            longContextManager.saveMessageAndMaybeSummarize(sessionId, "user", question);
            longContextManager.saveMessageAndMaybeSummarize(sessionId, "assistant", answer);
        } catch (RuntimeException exception) {
            log.warn("Failed to update Redis chat context after MySQL commit: sessionId={}, error={}",
                    sessionId, exception.getMessage());
        }
    }

    private void clearCachedSession(String sessionId) {
        try {
            longContextManager.clearSession(sessionId);
        } catch (RuntimeException exception) {
            log.warn("Failed to clear Redis chat context after MySQL commit: sessionId={}, error={}",
                    sessionId, exception.getMessage());
        }
    }
}

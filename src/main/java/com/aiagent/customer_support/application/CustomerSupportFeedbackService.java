package com.aiagent.customer_support.application;

import com.aiagent.chat.application.ChatSessionService;
import com.aiagent.chat.domain.Message;
import com.aiagent.chat.infrastructure.repository.MessageRepository;
import com.aiagent.customer_support.api.CustomerSupportFeedbackRequest;
import com.aiagent.ecommerce.domain.EcommerceFeedback;
import com.aiagent.ecommerce.infrastructure.repository.EcommerceFeedbackRepository;
import com.aiagent.shared.exception.ResourceNotFoundException;
import com.aiagent.shared.exception.ResourceStateConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class CustomerSupportFeedbackService {

    private final ChatSessionService chatSessionService;
    private final MessageRepository messageRepository;
    private final EcommerceFeedbackRepository feedbackRepository;

    @Transactional
    public CustomerSupportFeedbackResponse submit(String username,
                                                  String sessionId,
                                                  Long messageId,
                                                  CustomerSupportFeedbackRequest request) {
        validateRequest(sessionId, messageId, request);
        chatSessionService.requireOwnedSession(username, sessionId);

        Message assistantMessage = messageRepository.findByIdAndSessionId(messageId, sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Assistant message not found: " + messageId));
        if (!"assistant".equals(assistantMessage.getRole())) {
            throw new IllegalArgumentException("Feedback can only be submitted for assistant messages");
        }
        if (!"customer_support".equals(assistantMessage.getMsgType())) {
            throw new IllegalArgumentException("Feedback can only be submitted for customer support answers");
        }

        Message questionMessage = messageRepository
                .findFirstBySessionIdAndRoleAndIdLessThanOrderByIdDesc(sessionId, "user", messageId)
                .orElseThrow(() -> new ResourceStateConflictException(
                        "The assistant message has no corresponding user question"));

        var existing = feedbackRepository.findBySessionIdAndMessageId(sessionId, messageId);
        if (existing.isPresent()) {
            EcommerceFeedback feedback = existing.get();
            if (Objects.equals(feedback.getRating(), request.rating())
                    && Objects.equals(normalize(feedback.getFeedbackText()), normalize(request.feedbackText()))) {
                return toResponse(feedback);
            }
            throw new ResourceStateConflictException("Feedback has already been submitted for this message");
        }

        try {
            EcommerceFeedback saved = feedbackRepository.saveAndFlush(EcommerceFeedback.builder()
                    .sessionId(sessionId)
                    .messageId(messageId)
                    .question(questionMessage.getContent())
                    .answer(assistantMessage.getContent())
                    .retrievedQa(assistantMessage.getRagChunks())
                    .modelChain(assistantMessage.getModelChain())
                    .rating(request.rating())
                    .feedbackText(normalize(request.feedbackText()))
                    .build());
            return toResponse(saved);
        } catch (DataIntegrityViolationException exception) {
            throw new ResourceStateConflictException("Feedback has already been submitted for this message");
        }
    }

    private CustomerSupportFeedbackResponse toResponse(EcommerceFeedback feedback) {
        return CustomerSupportFeedbackResponse.builder()
                .feedbackId(feedback.getId())
                .sessionId(feedback.getSessionId())
                .messageId(feedback.getMessageId())
                .rating(feedback.getRating())
                .build();
    }

    private void validateRequest(String sessionId, Long messageId, CustomerSupportFeedbackRequest request) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (messageId == null || messageId <= 0) {
            throw new IllegalArgumentException("messageId must be greater than zero");
        }
        if (request == null || request.rating() == null || request.rating() < 1 || request.rating() > 5) {
            throw new IllegalArgumentException("rating must be between 1 and 5");
        }
        if (request.feedbackText() != null && request.feedbackText().length() > 500) {
            throw new IllegalArgumentException("feedbackText must not exceed 500 characters");
        }
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}

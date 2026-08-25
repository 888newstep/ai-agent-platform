package com.aiagent.customer_support.application;

import com.aiagent.chat.application.ChatSessionService;
import com.aiagent.chat.domain.Message;
import com.aiagent.chat.infrastructure.repository.MessageRepository;
import com.aiagent.customer_support.api.CustomerSupportFeedbackRequest;
import com.aiagent.ecommerce.domain.EcommerceFeedback;
import com.aiagent.ecommerce.infrastructure.repository.EcommerceFeedbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerSupportFeedbackServiceTest {

    private static final String SESSION_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Mock private ChatSessionService chatSessionService;
    @Mock private MessageRepository messageRepository;
    @Mock private EcommerceFeedbackRepository feedbackRepository;

    private CustomerSupportFeedbackService service;

    @BeforeEach
    void setUp() {
        service = new CustomerSupportFeedbackService(
                chatSessionService, messageRepository, feedbackRepository);
    }

    @Test
    void shouldPersistFeedbackFromTrustedMessageData() {
        Message assistant = Message.builder()
                .id(12L)
                .sessionId(SESSION_ID)
                .role("assistant")
                .content("回答")
                .msgType("customer_support")
                .ragChunks("[{\"qaPairId\":17}]")
                .modelChain("{\"mode\":\"customer_support\"}")
                .build();
        Message user = Message.builder()
                .id(11L)
                .sessionId(SESSION_ID)
                .role("user")
                .content("问题")
                .build();
        when(messageRepository.findByIdAndSessionId(12L, SESSION_ID)).thenReturn(Optional.of(assistant));
        when(messageRepository.findFirstBySessionIdAndRoleAndIdLessThanOrderByIdDesc(
                SESSION_ID, "user", 12L)).thenReturn(Optional.of(user));
        when(feedbackRepository.findBySessionIdAndMessageId(SESSION_ID, 12L)).thenReturn(Optional.empty());
        when(feedbackRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(EcommerceFeedback.class)))
                .thenAnswer(invocation -> {
                    EcommerceFeedback feedback = invocation.getArgument(0);
                    feedback.setId(31L);
                    return feedback;
                });

        CustomerSupportFeedbackResponse response = service.submit(
                "user", SESSION_ID, 12L, new CustomerSupportFeedbackRequest(5, " 有帮助 "));

        assertThat(response.getFeedbackId()).isEqualTo(31L);
        assertThat(response.getRating()).isEqualTo(5);
        verify(chatSessionService).requireOwnedSession("user", SESSION_ID);
        verify(feedbackRepository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(feedback ->
                "问题".equals(feedback.getQuestion())
                        && "回答".equals(feedback.getAnswer())
                        && "有帮助".equals(feedback.getFeedbackText())
                        && "[{\"qaPairId\":17}]".equals(feedback.getRetrievedQa())));
    }

    @Test
    void shouldRejectFeedbackForUserMessage() {
        Message user = Message.builder()
                .id(11L)
                .sessionId(SESSION_ID)
                .role("user")
                .content("问题")
                .build();
        when(messageRepository.findByIdAndSessionId(11L, SESSION_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.submit(
                "user", SESSION_ID, 11L, new CustomerSupportFeedbackRequest(3, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Feedback can only be submitted for assistant messages");
        verify(feedbackRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldReturnExistingFeedbackForExactRetry() {
        Message assistant = Message.builder()
                .id(12L)
                .sessionId(SESSION_ID)
                .role("assistant")
                .content("回答")
                .msgType("customer_support")
                .build();
        Message user = Message.builder()
                .id(11L)
                .sessionId(SESSION_ID)
                .role("user")
                .content("问题")
                .build();
        EcommerceFeedback existing = EcommerceFeedback.builder()
                .id(31L)
                .sessionId(SESSION_ID)
                .messageId(12L)
                .rating(4)
                .feedbackText("ok")
                .build();
        when(messageRepository.findByIdAndSessionId(12L, SESSION_ID)).thenReturn(Optional.of(assistant));
        when(messageRepository.findFirstBySessionIdAndRoleAndIdLessThanOrderByIdDesc(
                SESSION_ID, "user", 12L)).thenReturn(Optional.of(user));
        when(feedbackRepository.findBySessionIdAndMessageId(SESSION_ID, 12L))
                .thenReturn(Optional.of(existing));

        CustomerSupportFeedbackResponse response = service.submit(
                "user", SESSION_ID, 12L, new CustomerSupportFeedbackRequest(4, "ok"));

        assertThat(response.getFeedbackId()).isEqualTo(31L);
        verify(feedbackRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }
}

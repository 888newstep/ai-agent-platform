package com.aiagent.customer_support.api;

import com.aiagent.customer_support.application.CustomerSupportChatResponse;
import com.aiagent.customer_support.application.CustomerSupportFeedbackResponse;
import com.aiagent.customer_support.application.CustomerSupportFeedbackService;
import com.aiagent.customer_support.application.CustomerSupportService;
import com.aiagent.infrastructure.idempotency.IdempotencyService;
import com.aiagent.infrastructure.idempotency.PersistentIdempotencyContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerSupportControllerTest {

    private static final String SESSION_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Mock private CustomerSupportService customerSupportService;
    @Mock private CustomerSupportFeedbackService feedbackService;
    @Mock private IdempotencyService idempotencyService;

    private CustomerSupportController controller;
    private UsernamePasswordAuthenticationToken authentication;

    @BeforeEach
    void setUp() {
        controller = new CustomerSupportController(
                customerSupportService, feedbackService, idempotencyService);
        authentication = UsernamePasswordAuthenticationToken.authenticated("user", "token", java.util.List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldUseUserScopedIdempotencyForChat() {
        CustomerSupportChatResponse expected = CustomerSupportChatResponse.builder()
                .sessionId(SESSION_ID)
                .messageId(12L)
                .answer("回答")
                .build();
        when(idempotencyService.fingerprint("user", SESSION_ID, "问题")).thenReturn("request-hash");
        when(idempotencyService.fingerprint("key-1")).thenReturn("key-hash");
        when(customerSupportService.answer(
                eq("user"), eq(SESSION_ID), eq("问题"), any(PersistentIdempotencyContext.class)))
                .thenReturn(expected);
        when(idempotencyService.execute(
                eq("customer-support-chat:user"), eq("key-1"), eq("request-hash"),
                eq(CustomerSupportChatResponse.class), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<CustomerSupportChatResponse>) invocation.getArgument(4)).get());

        var response = controller.chat(
                new CustomerSupportChatRequest(SESSION_ID, "问题"), "key-1", authentication);

        assertThat(response.getBody()).isSameAs(expected);
        verify(customerSupportService).answer(
                "user", SESSION_ID, "问题",
                new PersistentIdempotencyContext("customer-support-chat", "key-hash", "request-hash"));
    }

    @Test
    void shouldSubmitFeedbackAsAuthenticatedUser() {
        CustomerSupportFeedbackRequest request = new CustomerSupportFeedbackRequest(5, "helpful");
        CustomerSupportFeedbackResponse expected = CustomerSupportFeedbackResponse.builder()
                .feedbackId(9L)
                .sessionId(SESSION_ID)
                .messageId(12L)
                .rating(5)
                .build();
        when(feedbackService.submit("user", SESSION_ID, 12L, request)).thenReturn(expected);

        var response = controller.submitFeedback(SESSION_ID, 12L, request, authentication);

        assertThat(response.getBody()).isSameAs(expected);
        verify(feedbackService).submit("user", SESSION_ID, 12L, request);
    }
}

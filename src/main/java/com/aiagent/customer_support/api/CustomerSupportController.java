package com.aiagent.customer_support.api;

import com.aiagent.customer_support.application.CustomerSupportChatResponse;
import com.aiagent.customer_support.application.CustomerSupportFeedbackResponse;
import com.aiagent.customer_support.application.CustomerSupportFeedbackService;
import com.aiagent.customer_support.application.CustomerSupportService;
import com.aiagent.infrastructure.idempotency.IdempotencyService;
import com.aiagent.infrastructure.idempotency.PersistentIdempotencyContext;
import com.aiagent.shared.exception.AuthenticationRequiredException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer-support")
@RequiredArgsConstructor
public class CustomerSupportController {

    private static final String CHAT_OPERATION = "customer-support-chat";

    private final CustomerSupportService customerSupportService;
    private final CustomerSupportFeedbackService feedbackService;
    private final IdempotencyService idempotencyService;

    @PostMapping("/chat")
    public ResponseEntity<CustomerSupportChatResponse> chat(
            @Valid @RequestBody CustomerSupportChatRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        String username = requireUsername(authentication);
        String requestHash = idempotencyService.fingerprint(
                username, request.sessionId(), request.question());
        PersistentIdempotencyContext persistentContext = StringUtils.hasText(idempotencyKey)
                ? new PersistentIdempotencyContext(
                        CHAT_OPERATION,
                        idempotencyService.fingerprint(idempotencyKey),
                        requestHash)
                : PersistentIdempotencyContext.disabled();

        CustomerSupportChatResponse response = idempotencyService.execute(
                CHAT_OPERATION + ":" + username,
                idempotencyKey,
                requestHash,
                CustomerSupportChatResponse.class,
                () -> customerSupportService.answer(
                        username, request.sessionId(), request.question(), persistentContext));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/sessions/{sessionId}/messages/{messageId}/feedback")
    public ResponseEntity<CustomerSupportFeedbackResponse> submitFeedback(
            @PathVariable String sessionId,
            @PathVariable Long messageId,
            @Valid @RequestBody CustomerSupportFeedbackRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(feedbackService.submit(
                requireUsername(authentication), sessionId, messageId, request));
    }

    private String requireUsername(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !StringUtils.hasText(authentication.getName())) {
            throw new AuthenticationRequiredException("Authentication required");
        }
        return authentication.getName();
    }
}

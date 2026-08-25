package com.aiagent.chat.application;

import com.aiagent.auth.domain.User;
import com.aiagent.auth.infrastructure.repository.UserRepository;
import com.aiagent.chat.domain.Conversation;
import com.aiagent.chat.domain.Message;
import com.aiagent.chat.infrastructure.repository.ConversationRepository;
import com.aiagent.chat.infrastructure.repository.MessageRepository;
import com.aiagent.infrastructure.memory.LongContextManager;
import com.aiagent.infrastructure.idempotency.PersistentIdempotencyContext;
import com.aiagent.infrastructure.idempotency.PersistentIdempotencyService;
import com.aiagent.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatSessionServiceTest {

    private static final String SESSION_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Mock
    private UserRepository userRepository;

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private LongContextManager longContextManager;

    @Mock
    private PersistentIdempotencyService persistentIdempotencyService;

    private ChatSessionService service;

    @BeforeEach
    void setUp() {
        service = new ChatSessionService(
                userRepository, conversationRepository, messageRepository, longContextManager,
                persistentIdempotencyService);
    }

    @Test
    void shouldCreatePersistentSessionForActiveUser() {
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(activeUser(7L)));

        String sessionId = service.createSession("user");

        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).save(captor.capture());
        assertThat(sessionId).isNotBlank();
        assertThat(captor.getValue().getSessionId()).isEqualTo(sessionId);
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getMessageCount()).isZero();
    }

    @Test
    void shouldHideSessionsOwnedByAnotherUser() {
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(activeUser(7L)));
        when(conversationRepository.findBySessionIdAndUserId(SESSION_ID, 7L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireOwnedSession("user", SESSION_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void shouldPersistExchangeAndUpdateRedisAfterDatabaseWork() {
        Conversation conversation = Conversation.builder()
                .sessionId(SESSION_ID)
                .userId(7L)
                .messageCount(0)
                .build();
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(activeUser(7L)));
        when(conversationRepository.findOwnedForUpdate(SESSION_ID, 7L))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.saveAll(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<Message> messages = invocation.getArgument(0);
            messages.get(0).setId(1L);
            messages.get(1).setId(2L);
            return messages;
        });

        service.recordSuccessfulExchange("user", SESSION_ID, "如何退款？", "请提交退款申请。 ");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).saveAll(messagesCaptor.capture());
        assertThat(messagesCaptor.getValue())
                .extracting(Message::getRole)
                .containsExactly("user", "assistant");
        assertThat(conversation.getMessageCount()).isEqualTo(2);
        assertThat(conversation.getTitle()).isEqualTo("如何退款？");
        verify(longContextManager).saveMessageAndMaybeSummarize(SESSION_ID, "user", "如何退款？");
        verify(longContextManager).saveMessageAndMaybeSummarize(
                SESSION_ID, "assistant", "请提交退款申请。 ");
    }

    @Test
    void shouldDeleteOwnedSessionAndCachedContext() {
        Conversation conversation = Conversation.builder()
                .sessionId(SESSION_ID)
                .userId(7L)
                .build();
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(activeUser(7L)));
        when(conversationRepository.findOwnedForUpdate(SESSION_ID, 7L))
                .thenReturn(Optional.of(conversation));

        service.deleteSession("user", SESSION_ID);

        verify(messageRepository).deleteBySessionId(SESSION_ID);
        verify(conversationRepository).delete(conversation);
        verify(longContextManager).clearSession(SESSION_ID);
    }

    @Test
    void shouldReturnRecentMessagesInChronologicalOrder() {
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(activeUser(7L)));
        when(conversationRepository.findBySessionIdAndUserId(SESSION_ID, 7L))
                .thenReturn(Optional.of(Conversation.builder().sessionId(SESSION_ID).userId(7L).build()));
        Message assistant = Message.builder()
                .id(2L)
                .sessionId(SESSION_ID)
                .role("assistant")
                .content("answer")
                .createdAt(LocalDateTime.now())
                .build();
        Message user = Message.builder()
                .id(1L)
                .sessionId(SESSION_ID)
                .role("user")
                .content("question")
                .createdAt(LocalDateTime.now().minusSeconds(1))
                .build();
        when(messageRepository.findBySessionIdOrderByIdDesc(
                eq(SESSION_ID), any(Pageable.class))).thenReturn(List.of(assistant, user));

        List<ChatMessageView> messages = service.getRecentMessages("user", SESSION_ID, 100);

        assertThat(messages).extracting(ChatMessageView::role)
                .containsExactly("user", "assistant");
    }

    @Test
    void shouldReturnPersistedSessionWhenRedisRecordWasLost() {
        PersistentIdempotencyContext context = new PersistentIdempotencyContext(
                "agent-session-create", "key-hash", "request-hash");
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(activeUser(7L)));
        when(persistentIdempotencyService.findCompleted(7L, null, context, String.class))
                .thenReturn(Optional.of(SESSION_ID));

        String sessionId = service.createSession("user", context);

        assertThat(sessionId).isEqualTo(SESSION_ID);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void shouldNotDuplicateMessagesWhenPersistentCompletionExists() {
        PersistentIdempotencyContext context = new PersistentIdempotencyContext(
                "agent-chat", "key-hash", "request-hash");
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(activeUser(7L)));
        when(persistentIdempotencyService.findCompleted(
                7L, SESSION_ID, context, String.class)).thenReturn(Optional.of("stored answer"));

        String answer = service.recordSuccessfulExchange(
                "user", SESSION_ID, "question", "new answer",
                context, "new answer", String.class);

        assertThat(answer).isEqualTo("stored answer");
        verify(messageRepository, never()).saveAll(any());
        verify(conversationRepository, never()).findOwnedForUpdate(any(), any());
    }

    @Test
    void shouldPersistAssistantTraceAndBuildResponseWithMessageId() {
        Conversation conversation = Conversation.builder()
                .sessionId(SESSION_ID)
                .userId(7L)
                .messageCount(0)
                .build();
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(activeUser(7L)));
        when(conversationRepository.findOwnedForUpdate(SESSION_ID, 7L))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.saveAll(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<Message> messages = invocation.getArgument(0);
            messages.get(0).setId(20L);
            messages.get(1).setId(21L);
            return messages;
        });

        Long messageId = service.recordSuccessfulExchange(
                "user",
                SESSION_ID,
                "question",
                "answer",
                PersistentIdempotencyContext.disabled(),
                new ChatExchangeMetadata("customer_support", "{\"mode\":\"cs\"}", "[{\"id\":1}]"),
                id -> id,
                Long.class);

        assertThat(messageId).isEqualTo(21L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).saveAll(messagesCaptor.capture());
        Message assistant = messagesCaptor.getValue().get(1);
        assertThat(assistant.getMsgType()).isEqualTo("customer_support");
        assertThat(assistant.getModelChain()).isEqualTo("{\"mode\":\"cs\"}");
        assertThat(assistant.getRagChunks()).isEqualTo("[{\"id\":1}]");
    }

    private User activeUser(Long id) {
        return User.builder()
                .id(id)
                .username("user")
                .enabled(true)
                .build();
    }
}

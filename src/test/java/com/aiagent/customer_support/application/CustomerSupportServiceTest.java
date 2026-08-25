package com.aiagent.customer_support.application;

import com.aiagent.chat.application.ChatExchangeMetadata;
import com.aiagent.chat.application.ChatSessionService;
import com.aiagent.customer_support.config.CustomerSupportAnswerProperties;
import com.aiagent.ecommerce.infrastructure.repository.EcommerceQaPairRepository;
import com.aiagent.infrastructure.idempotency.PersistentIdempotencyContext;
import com.aiagent.infrastructure.memory.LongContextManager;
import com.aiagent.knowledge.domain.DocumentProcessingStatus;
import com.aiagent.knowledge.infrastructure.repository.DocumentRepository;
import com.aiagent.rag.application.AdaptiveRagContext;
import com.aiagent.rag.application.AdaptiveRagRoundTrace;
import com.aiagent.rag.application.AdaptiveRagService;
import com.aiagent.rag.application.AnswerSupportResult;
import com.aiagent.rag.application.AnswerSupportVerifier;
import com.aiagent.rag.application.RagVerificationLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerSupportServiceTest {

    private static final String SESSION_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Mock private ChatLanguageModel chatLanguageModel;
    @Mock private AdaptiveRagService adaptiveRagService;
    @Mock private AnswerSupportVerifier answerSupportVerifier;
    @Mock private LongContextManager longContextManager;
    @Mock private ChatSessionService chatSessionService;
    @Mock private EcommerceQaPairRepository qaPairRepository;
    @Mock private DocumentRepository documentRepository;

    private CustomerSupportAnswerProperties properties;
    private CustomerSupportService service;

    @BeforeEach
    void setUp() {
        properties = new CustomerSupportAnswerProperties();
        service = new CustomerSupportService(
                chatLanguageModel,
                adaptiveRagService,
                answerSupportVerifier,
                longContextManager,
                chatSessionService,
                qaPairRepository,
                documentRepository,
                properties,
                new ObjectMapper());
        org.mockito.Mockito.lenient().when(chatSessionService.findCompletedResponse(
                anyString(), anyString(), any(), eq(CustomerSupportChatResponse.class)))
                .thenReturn(Optional.empty());
    }

    @Test
    void shouldGenerateAnswerAndIncrementHitsForReliableEvidence() {
        AdaptiveRagContext context = context(RagVerificationLevel.HIGH, 1, List.of(chunk("17")));
        when(adaptiveRagService.resolve("如何退款")).thenReturn(context);
        when(longContextManager.getOptimizedContext(SESSION_ID, "如何退款")).thenReturn("");
        when(chatLanguageModel.generate(anyString())).thenReturn("可以在订单页申请退款。 ");
        when(answerSupportVerifier.verify(anyString(), anyString(), anyString()))
                .thenReturn(supportedAnswer());
        when(qaPairRepository.countByIdInAndStatus(List.of(17L), 1)).thenReturn(1L);
        when(qaPairRepository.incrementHitCount(anyList(), any(LocalDateTime.class))).thenReturn(1);
        mockPersistence(42L);

        CustomerSupportChatResponse response = service.answer(
                "user", SESSION_ID, "如何退款", PersistentIdempotencyContext.disabled());

        assertThat(response.getMessageId()).isEqualTo(42L);
        assertThat(response.getResolution()).isEqualTo("ANSWERED");
        assertThat(response.isHandoffSuggested()).isFalse();
        assertThat(response.getEvidence()).extracting(CustomerSupportEvidence::getQaPairId)
                .containsExactly(17L);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatLanguageModel).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("[TRUSTED_SECURITY_POLICY]")
                .contains("<<<BEGIN_UNTRUSTED_DATA:KNOWLEDGE_CONTEXT>>>")
                .contains("<<<BEGIN_UNTRUSTED_DATA:USER_REQUEST>>>");
        verify(qaPairRepository).incrementHitCount(eq(List.of(17L)), any(LocalDateTime.class));
    }

    @Test
    void shouldHandoffWithoutCallingModelWhenEvidenceIsInsufficient() {
        AdaptiveRagContext context = context(RagVerificationLevel.LOW, 1, List.of(chunk("17")));
        when(adaptiveRagService.resolve("特殊赔付政策")).thenReturn(context);
        mockPersistence(43L);

        CustomerSupportChatResponse response = service.answer(
                "user", SESSION_ID, "特殊赔付政策", PersistentIdempotencyContext.disabled());

        assertThat(response.getResolution()).isEqualTo("HANDOFF_REQUIRED");
        assertThat(response.isHandoffSuggested()).isTrue();
        assertThat(response.getAnswer()).isEqualTo(properties.getHandoffMessage());
        verify(chatLanguageModel, never()).generate(anyString());
        verify(qaPairRepository, never()).incrementHitCount(anyList(), any(LocalDateTime.class));
    }

    @Test
    void shouldHandoffWhenMilvusEvidenceIsStaleInMysql() {
        AdaptiveRagContext context = context(RagVerificationLevel.HIGH, 1, List.of(chunk("17")));
        when(adaptiveRagService.resolve("已下架政策")).thenReturn(context);
        when(qaPairRepository.countByIdInAndStatus(List.of(17L), 1)).thenReturn(0L);
        mockPersistence(44L);

        CustomerSupportChatResponse response = service.answer(
                "user", SESSION_ID, "已下架政策", PersistentIdempotencyContext.disabled());

        assertThat(response.getResolution()).isEqualTo("HANDOFF_REQUIRED");
        verify(chatLanguageModel, never()).generate(anyString());
        verify(qaPairRepository, never()).incrementHitCount(anyList(), any(LocalDateTime.class));
    }

    @Test
    void shouldAnswerFromCompletedDocumentEvidence() {
        AdaptiveRagRoundTrace.ChunkTrace documentChunk = AdaptiveRagRoundTrace.ChunkTrace.builder()
                .chunkId("chunk-9")
                .documentId("8")
                .score(0.91)
                .retrievalSource("both")
                .build();
        AdaptiveRagContext context = context(
                RagVerificationLevel.HIGH, 1, List.of(documentChunk));
        when(adaptiveRagService.resolve("产品说明")).thenReturn(context);
        when(documentRepository.countByIdInAndProcessingStatus(
                List.of(8L), DocumentProcessingStatus.COMPLETED)).thenReturn(1L);
        when(longContextManager.getOptimizedContext(SESSION_ID, "产品说明")).thenReturn("");
        when(chatLanguageModel.generate(anyString())).thenReturn("文档中的产品说明");
        when(answerSupportVerifier.verify(anyString(), anyString(), anyString()))
                .thenReturn(supportedAnswer());
        mockPersistence(45L);

        CustomerSupportChatResponse response = service.answer(
                "user", SESSION_ID, "产品说明", PersistentIdempotencyContext.disabled());

        assertThat(response.getResolution()).isEqualTo("ANSWERED");
        assertThat(response.getEvidence()).extracting(CustomerSupportEvidence::getDocumentId)
                .containsExactly(8L);
        verify(qaPairRepository, never()).incrementHitCount(anyList(), any(LocalDateTime.class));
    }

    @Test
    void shouldHandoffWhenGeneratedAnswerIsNotSupportedByEvidence() {
        AdaptiveRagContext context = context(RagVerificationLevel.HIGH, 1, List.of(chunk("17")));
        when(adaptiveRagService.resolve("退款多久到账")).thenReturn(context);
        when(qaPairRepository.countByIdInAndStatus(List.of(17L), 1)).thenReturn(1L);
        when(longContextManager.getOptimizedContext(SESSION_ID, "退款多久到账")).thenReturn("");
        when(chatLanguageModel.generate(anyString())).thenReturn("退款会在24小时内到账。");
        when(answerSupportVerifier.verify(anyString(), anyString(), anyString()))
                .thenReturn(AnswerSupportResult.builder()
                        .supported(false)
                        .score(0.84)
                        .supportedClaimRatio(1.0)
                        .numbersSupported(false)
                        .reason("answer number 24 is absent from evidence")
                        .build());
        mockPersistence(46L);

        CustomerSupportChatResponse response = service.answer(
                "user", SESSION_ID, "退款多久到账", PersistentIdempotencyContext.disabled());

        assertThat(response.getResolution()).isEqualTo("HANDOFF_REQUIRED");
        assertThat(response.getResponseSource()).isEqualTo("answer_unsupported");
        assertThat(response.isAnswerSupported()).isFalse();
        assertThat(response.getAnswer()).isEqualTo(properties.getHandoffMessage());
        verify(qaPairRepository, never()).incrementHitCount(anyList(), any(LocalDateTime.class));
    }

    @Test
    void shouldReplayPersistentCompletionBeforeRetrieval() {
        PersistentIdempotencyContext idempotencyContext = new PersistentIdempotencyContext(
                "customer-support-chat", "key-hash", "request-hash");
        CustomerSupportChatResponse stored = CustomerSupportChatResponse.builder()
                .sessionId(SESSION_ID)
                .messageId(9L)
                .answer("stored")
                .build();
        when(chatSessionService.findCompletedResponse(
                "user", SESSION_ID, idempotencyContext, CustomerSupportChatResponse.class))
                .thenReturn(Optional.of(stored));

        CustomerSupportChatResponse response = service.answer(
                "user", SESSION_ID, "问题", idempotencyContext);

        assertThat(response).isSameAs(stored);
        verify(adaptiveRagService, never()).resolve(anyString());
    }

    @SuppressWarnings("unchecked")
    private void mockPersistence(Long messageId) {
        when(chatSessionService.recordSuccessfulExchange(
                anyString(), anyString(), anyString(), anyString(), any(),
                any(ChatExchangeMetadata.class), any(Function.class), eq(CustomerSupportChatResponse.class)))
                .thenAnswer(invocation -> {
                    Function<Long, CustomerSupportChatResponse> responseFactory = invocation.getArgument(6);
                    return responseFactory.apply(messageId);
                });
    }

    private AdaptiveRagContext context(RagVerificationLevel level,
                                       int chunkCount,
                                       List<AdaptiveRagRoundTrace.ChunkTrace> chunks) {
        return AdaptiveRagContext.builder()
                .context("退款规则证据")
                .verificationLevel(level)
                .chunkCount(chunkCount)
                .retrievalRounds(1)
                .roundTraces(List.of(AdaptiveRagRoundTrace.builder()
                        .round(1)
                        .retrievedChunks(chunks)
                        .build()))
                .build();
    }

    private AdaptiveRagRoundTrace.ChunkTrace chunk(String id) {
        return AdaptiveRagRoundTrace.ChunkTrace.builder()
                .chunkId(id)
                .qaPairId(id)
                .score(0.92)
                .category("refund")
                .question("如何退款")
                .retrievalSource("both")
                .build();
    }

    private AnswerSupportResult supportedAnswer() {
        return AnswerSupportResult.builder()
                .supported(true)
                .score(0.91)
                .supportedClaimRatio(1.0)
                .claimCount(1)
                .supportedClaimCount(1)
                .numbersSupported(true)
                .reason("supported")
                .build();
    }
}

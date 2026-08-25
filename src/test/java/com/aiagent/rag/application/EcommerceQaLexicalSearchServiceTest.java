package com.aiagent.rag.application;

import com.aiagent.ecommerce.infrastructure.repository.EcommerceQaPairRepository;
import com.aiagent.infrastructure.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EcommerceQaLexicalSearchServiceTest {

    @Mock
    private EcommerceQaPairRepository repository;

    private AiProperties aiProperties;
    private EcommerceQaLexicalSearchService service;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.getVectorStore().setMode("qa");
        aiProperties.getRag().setHybridMysqlFulltextEnabled(true);
        service = new EcommerceQaLexicalSearchService(repository, aiProperties);
    }

    @Test
    void shouldMapFullTextProjectionToRetrievalChunk() {
        EcommerceQaPairRepository.LexicalCandidate candidate = mock(EcommerceQaPairRepository.LexicalCandidate.class);
        when(candidate.getId()).thenReturn(42L);
        when(candidate.getQuestion()).thenReturn("怎么退款");
        when(candidate.getAnswer()).thenReturn("先申请售后");
        when(candidate.getQaText()).thenReturn("用户问题：怎么退款 客服回答：先申请售后");
        when(candidate.getCategory()).thenReturn("refund");
        when(candidate.getLexicalScore()).thenReturn(3.5);
        when(repository.searchQuestionFullText("怎么退款", 5)).thenReturn(List.of(candidate));

        var results = service.search("怎么退款", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo("42");
        assertThat(results.get(0).getScore()).isEqualTo(3.5);
        assertThat(results.get(0).getMetadata())
                .containsEntry("lexicalProvider", "mysql_ngram_fulltext")
                .containsEntry("category", "refund");
    }

    @Test
    void shouldDisableMysqlSearchOutsideQaMode() {
        aiProperties.getVectorStore().setMode("langchain");

        assertThat(service.search("refund", 5)).isEmpty();
        verify(repository, never()).searchQuestionFullText("refund", 5);
    }

    @Test
    void shouldDegradeWhenFullTextQueryFails() {
        when(repository.searchQuestionFullText("refund", 5)).thenThrow(new IllegalStateException("index missing"));

        assertThat(service.search("refund", 5)).isEmpty();
    }
}

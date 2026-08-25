package com.aiagent.customer_support.application;

import com.aiagent.ecommerce.domain.EcommerceQaPair;
import com.aiagent.ecommerce.infrastructure.repository.EcommerceQaPairRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CsImportBatchPersistenceServiceTest {

    @Mock
    private EcommerceQaPairRepository repository;

    private CsImportBatchPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new CsImportBatchPersistenceService(repository);
    }

    @Test
    void shouldSkipIndexedRecordsAndDeduplicatePendingHashes() {
        var indexedRecord = record("indexed", "q1");
        var pendingRecord = record("pending", "q2");
        EcommerceQaPair indexedPair = EcommerceQaPair.builder()
                .id(1L)
                .recordHash("indexed")
                .vectorId("vector-1")
                .build();
        when(repository.findAllByRecordHashIn(any())).thenReturn(List.of(indexedPair));
        when(repository.save(any(EcommerceQaPair.class))).thenAnswer(invocation -> {
            EcommerceQaPair pair = invocation.getArgument(0);
            pair.setId(2L);
            return pair;
        });

        var prepared = service.prepareBatch(List.of(indexedRecord, pendingRecord, pendingRecord));

        assertThat(prepared.pendingPairs()).hasSize(1);
        assertThat(prepared.pendingPairs().get(0).recordIndex()).isEqualTo(1);
        assertThat(prepared.pendingPairs().get(0).pair().getId()).isEqualTo(2L);
    }

    @Test
    void shouldUpdateAllVectorIdsInOneTransactionBoundary() {
        EcommerceQaPair first = EcommerceQaPair.builder().id(1L).build();
        EcommerceQaPair second = EcommerceQaPair.builder().id(2L).build();
        when(repository.findAllById(any())).thenReturn(List.of(first, second));

        service.updateVectorIds(List.of(
                new CsImportBatchPersistenceService.VectorAssignment(1L, "v1"),
                new CsImportBatchPersistenceService.VectorAssignment(2L, "v2")));

        assertThat(first.getVectorId()).isEqualTo("v1");
        assertThat(second.getVectorId()).isEqualTo("v2");
        ArgumentCaptor<List<EcommerceQaPair>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).containsExactly(first, second);
        verify(repository, never()).save(any(EcommerceQaPair.class));
    }

    @Test
    void shouldReturnEmptyBatchWithoutRepositoryCall() {
        assertThat(service.prepareBatch(null).pendingPairs()).isEmpty();
        assertThat(service.prepareBatch(List.of()).pendingPairs()).isEmpty();

        verifyNoInteractions(repository);
    }

    @Test
    void shouldRejectBlankRecordHash() {
        assertThatThrownBy(() -> service.prepareBatch(List.of(record(" ", "question"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hash");

        verifyNoInteractions(repository);
    }

    @Test
    void shouldIgnoreEmptyVectorAssignments() {
        service.updateVectorIds(null);
        service.updateVectorIds(List.of());

        verifyNoInteractions(repository);
    }

    @Test
    void shouldRejectConflictingVectorAssignments() {
        assertThatThrownBy(() -> service.updateVectorIds(List.of(
                new CsImportBatchPersistenceService.VectorAssignment(1L, "v1"),
                new CsImportBatchPersistenceService.VectorAssignment(1L, "v2"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conflicting");

        verifyNoInteractions(repository);
    }

    @Test
    void shouldRejectUnexpectedRepositoryRecord() {
        when(repository.findAllById(any())).thenReturn(List.of(EcommerceQaPair.builder().id(2L).build()));

        assertThatThrownBy(() -> service.updateVectorIds(List.of(
                new CsImportBatchPersistenceService.VectorAssignment(1L, "v1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected");

        verify(repository, never()).saveAll(any());
    }

    private CsDataImportService.QaRecord record(String hash, String question) {
        return CsDataImportService.QaRecord.builder()
                .question(question)
                .answer("answer")
                .qaText(question + " answer")
                .category("test")
                .sourceFile("test.jsonl")
                .recordHash(hash)
                .build();
    }
}

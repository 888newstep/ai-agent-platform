package com.aiagent.customer_support.application;

import com.aiagent.ecommerce.domain.EcommerceQaPair;
import com.aiagent.ecommerce.infrastructure.repository.EcommerceQaPairRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CsImportBatchPersistenceService {

    private final EcommerceQaPairRepository qaPairRepository;

    @Transactional
    public PreparedBatch prepareBatch(List<CsDataImportService.QaRecord> records) {
        if (records == null || records.isEmpty()) {
            return new PreparedBatch(List.of());
        }
        for (CsDataImportService.QaRecord record : records) {
            if (record == null || !StringUtils.hasText(record.getRecordHash())) {
                throw new IllegalArgumentException("Imported QA record hash must not be blank");
            }
        }
        Map<String, EcommerceQaPair> existingPairs = new HashMap<>();
        qaPairRepository.findAllByRecordHashIn(records.stream()
                        .map(CsDataImportService.QaRecord::getRecordHash)
                        .toList())
                .forEach(pair -> existingPairs.putIfAbsent(pair.getRecordHash(), pair));

        Set<String> scheduledHashes = new HashSet<>();
        List<PendingPair> pendingPairs = new ArrayList<>();
        for (int index = 0; index < records.size(); index++) {
            CsDataImportService.QaRecord record = records.get(index);
            EcommerceQaPair pair = existingPairs.get(record.getRecordHash());
            if (pair != null && StringUtils.hasText(pair.getVectorId())) {
                continue;
            }
            if (!scheduledHashes.add(record.getRecordHash())) {
                continue;
            }
            if (pair == null) {
                pair = qaPairRepository.save(EcommerceQaPair.builder()
                        .question(record.getQuestion())
                        .answer(record.getAnswer())
                        .qaText(record.getQaText())
                        .category(record.getCategory())
                        .sourceFile(record.getSourceFile())
                        .recordHash(record.getRecordHash())
                        .status(1)
                        .build());
                existingPairs.put(record.getRecordHash(), pair);
            }
            pendingPairs.add(new PendingPair(index, pair));
        }
        return new PreparedBatch(pendingPairs);
    }

    @Transactional
    public void updateVectorIds(List<VectorAssignment> assignments) {
        if (assignments == null || assignments.isEmpty()) {
            return;
        }
        Map<Long, String> vectorIdsByPairId = new HashMap<>();
        for (VectorAssignment assignment : assignments) {
            if (assignment == null || assignment.qaPairId() == null || !StringUtils.hasText(assignment.vectorId())) {
                throw new IllegalArgumentException("Vector assignment must contain QA id and vector id");
            }
            String previous = vectorIdsByPairId.putIfAbsent(assignment.qaPairId(), assignment.vectorId());
            if (previous != null && !previous.equals(assignment.vectorId())) {
                throw new IllegalArgumentException("Conflicting vector ids for QA record " + assignment.qaPairId());
            }
        }

        List<EcommerceQaPair> pairs = qaPairRepository.findAllById(vectorIdsByPairId.keySet());
        if (pairs.size() != vectorIdsByPairId.size()) {
            throw new IllegalStateException("Some imported QA records disappeared before vector ids were saved");
        }
        for (EcommerceQaPair pair : pairs) {
            String vectorId = vectorIdsByPairId.get(pair.getId());
            if (vectorId == null) {
                throw new IllegalStateException("Repository returned an unexpected QA record " + pair.getId());
            }
            pair.setVectorId(vectorId);
        }
        qaPairRepository.saveAll(pairs);
    }

    public record PreparedBatch(List<PendingPair> pendingPairs) {
    }

    public record PendingPair(int recordIndex, EcommerceQaPair pair) {
    }

    public record VectorAssignment(Long qaPairId, String vectorId) {
    }
}

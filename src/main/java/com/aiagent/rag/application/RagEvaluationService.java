package com.aiagent.rag.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.knowledge.domain.RetrievalChunk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * RAG 检索评测服务。
 *
 * <p>支持内置 smoke 数据集、外部 JSON/CSV 数据集、多个 Top-K 和多个检索配置对比，
 * 输出整体及 category 分组的召回、精确率、F1 和延迟指标。
 *
 * <p>`chunkSize` 与 `chunkOverlap` 仅作为配置快照记录；切片参数对比需要重新导入文档。
 */
@Slf4j

@Service
@RequiredArgsConstructor
public class RagEvaluationService {

    private static final String DEFAULT_CATEGORY = "uncategorized";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final MultiRecallService multiRecallService;
    private final AiProperties aiProperties;

    @Value("${ai.evaluation.dataset-directory:}")
    private String datasetDirectory;

    public EvaluationReport evaluate(Map<String, List<String>> testDataset, List<Integer> topKs) {
        return evaluate(toDataset(testDataset), sanitizeTopKs(topKs), defaultProfile());
    }

    public EvaluationReport quickEvaluate(List<Integer> topKs) {
        return evaluate(buildSampleDataset(), sanitizeTopKs(topKs), defaultProfile());
    }

    public EvaluationReport quickEvaluate(List<Integer> topKs,
                                          Double similarityThreshold,
                                          Boolean hybridSearch) {
        if (similarityThreshold == null && hybridSearch == null) {
            return quickEvaluate(topKs);
        }
        return evaluate(buildSampleDataset(), sanitizeTopKs(topKs), runtimeProfile("runtime", similarityThreshold, hybridSearch));
    }

    public EvaluationReport evaluateFromFile(String datasetPath,
                                             List<Integer> topKs,
                                             Double similarityThreshold,
                                             Boolean hybridSearch) {
        EvaluationDataset dataset = loadDataset(datasetPath);
        return evaluate(dataset, sanitizeTopKs(topKs), runtimeProfile("runtime", similarityThreshold, hybridSearch));
    }

    public ComparisonReport compareFromFile(String datasetPath,
                                            List<Integer> topKs,
                                            List<EvaluationProfile> requestedProfiles) {
        EvaluationDataset dataset = loadDataset(datasetPath);
        List<Integer> sanitizedTopKs = sanitizeTopKs(topKs);
        List<EvaluationProfile> profiles = requestedProfiles == null || requestedProfiles.isEmpty()
                ? List.of(defaultProfile())
                : requestedProfiles.stream().map(this::resolveProfile).toList();

        ComparisonReport report = new ComparisonReport();
        report.setDatasetSource(dataset.getSource());
        report.setDatasetSize(dataset.getCases().size());
        report.setTopKs(sanitizedTopKs);

        Map<String, EvaluationReport> reports = new LinkedHashMap<>();
        for (EvaluationProfile profile : profiles) {
            reports.put(profile.getName(), evaluate(dataset, sanitizedTopKs, profile));
        }
        report.setReports(reports);

        log.debug("RAG 对比评测完成: dataset={}, profiles={}", dataset.getSource(), reports.keySet());
        return report;
    }

    private EvaluationReport evaluate(EvaluationDataset dataset,
                                      List<Integer> topKs,
                                      EvaluationProfile profile) {
        log.debug("开始 RAG 评估: dataset={}, size={}, topKs={}, profile={}",
                dataset.getSource(), dataset.getCases().size(), topKs, profile.getName());

        EvaluationReport report = new EvaluationReport();
        report.setDatasetSource(dataset.getSource());
        report.setDatasetSize(dataset.getCases().size());
        report.setTopKs(topKs);
        report.setConfigSnapshot(getConfigSnapshot(profile));

        int maxTopK = topKs.stream().mapToInt(Integer::intValue).max().orElse(aiProperties.getRag().getTopK());
        Map<Integer, MetricAccumulator> overallByTopK = new LinkedHashMap<>();
        Map<Integer, Map<String, MetricAccumulator>> categoryByTopK = new LinkedHashMap<>();
        topKs.forEach(k -> {
            overallByTopK.put(k, new MetricAccumulator());
            categoryByTopK.put(k, new LinkedHashMap<>());
        });

        for (EvaluationCase evaluationCase : dataset.getCases()) {
            long startTime = System.currentTimeMillis();
            List<RetrievalChunk> searchResults = multiRecallService.search(
                    evaluationCase.getQuestion(),
                    MultiRecallService.SearchOptions.builder()
                            .topK(maxTopK)
                            .similarityThreshold(profile.getSimilarityThreshold())
                            .hybridSearch(Boolean.TRUE.equals(profile.getHybridSearch()))
                            .cacheEnabled(false)
                            .build());
            long latency = System.currentTimeMillis() - startTime;
            List<RetrievalChunk> results = searchResults == null ? List.of() : searchResults;
            report.getDiagnostics().add(buildDiagnostic(evaluationCase, results, maxTopK, latency));

            for (int k : topKs) {
                List<RetrievalChunk> topResults = results.size() > k ? results.subList(0, k) : results;
                Set<String> resultIds = topResults.stream()
                        .map(RetrievalChunk::getId)
                        .collect(Collectors.toSet());
                long relevantInResults = resultIds.stream()
                        .filter(evaluationCase.getRelevantDocIds()::contains)
                        .count();
                double recall = (double) relevantInResults / evaluationCase.getRelevantDocIds().size();
                double precision = (double) relevantInResults / Math.max(k, 1);

                overallByTopK.get(k).add(recall, precision, latency, topResults.size());
                categoryByTopK.get(k)
                        .computeIfAbsent(evaluationCase.getCategory(), ignored -> new MetricAccumulator())
                        .add(recall, precision, latency, topResults.size());
            }
        }

        for (int k : topKs) {
            String topK = String.valueOf(k);
            writeMetrics(overallByTopK.get(k), (name, value) -> report.addMetric(topK, name, value));
            for (Map.Entry<String, MetricAccumulator> entry : categoryByTopK.get(k).entrySet()) {
                writeMetrics(entry.getValue(), (name, value) ->
                        report.addCategoryMetric(entry.getKey(), topK, name, value));
            }
        }

        log.debug("RAG 评估完成: dataset={}, profile={}, size={}",
                dataset.getSource(), profile.getName(), dataset.getCases().size());
        return report;
    }

    private QueryDiagnostic buildDiagnostic(EvaluationCase evaluationCase,
                                            List<RetrievalChunk> results,
                                            int maxTopK,
                                            long latency) {
        List<String> actualDocIds = results.stream().map(RetrievalChunk::getId).toList();
        Integer firstRelevantRank = null;
        for (int index = 0; index < actualDocIds.size(); index++) {
            if (evaluationCase.getRelevantDocIds().contains(actualDocIds.get(index))) {
                firstRelevantRank = index + 1;
                break;
            }
        }
        List<String> retrievalSources = results.stream()
                .map(RetrievalChunk::getMetadata)
                .map(metadata -> metadata == null ? "unknown" : String.valueOf(metadata.getOrDefault("retrievalSource", "unknown")))
                .toList();
        return QueryDiagnostic.builder()
                .caseHash(fingerprint(evaluationCase.getQuestion()))
                .category(evaluationCase.getCategory())
                .relevantDocIds(evaluationCase.getRelevantDocIds())
                .actualDocIds(actualDocIds)
                .retrievalSources(retrievalSources)
                .firstRelevantRank(firstRelevantRank)
                .maxTopK(maxTopK)
                .latencyMs(latency)
                .build();
    }

    private void writeMetrics(MetricAccumulator accumulator, BiConsumer<String, Object> writer) {
        int sampleCount = accumulator.latencies.size();
        double recall = average(accumulator.recalls);
        double precision = average(accumulator.precisions);
        writer.accept("sampleCount", sampleCount);
        writer.accept("recall", recall);
        writer.accept("precision", precision);
        writer.accept("f1", calculateF1(recall, precision));
        writer.accept("avgLatency", averageLong(accumulator.latencies));
        writer.accept("p95Latency", percentileLong(accumulator.latencies, 95));
        writer.accept("p99Latency", percentileLong(accumulator.latencies, 99));
        writer.accept("p50Latency", percentileLong(accumulator.latencies, 50));
        writer.accept("emptyResultCount", accumulator.emptyResultCount);
        writer.accept("emptyResultRate", ratio(accumulator.emptyResultCount, sampleCount));
        writer.accept("retrievalHitRate", ratio(sampleCount - accumulator.emptyResultCount, sampleCount));
    }

    private EvaluationDataset loadDataset(String datasetPath) {
        if (!StringUtils.hasText(datasetPath)) {
            throw new IllegalArgumentException("datasetPath must not be blank");
        }

        Path path = Path.of(datasetPath.trim()).toAbsolutePath().normalize();
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Dataset file not found: " + datasetPath);
        }

        try {
            Path realPath = path.toRealPath();
            if (StringUtils.hasText(datasetDirectory)) {
                Path allowedDirectory = Path.of(datasetDirectory).toAbsolutePath().normalize().toRealPath();
                if (!realPath.startsWith(allowedDirectory)) {
                    throw new IllegalArgumentException("Dataset path is outside the configured directory");
                }
            }
            path = realPath;
            String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (fileName.endsWith(".json")) {
                return loadJsonDataset(path);
            }
            if (fileName.endsWith(".csv")) {
                return loadCsvDataset(path);
            }
            throw new IllegalArgumentException("Unsupported dataset format: " + fileName + " (supported: .json, .csv)");
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read dataset: " + e.getMessage(), e);
        }
    }

    private EvaluationDataset loadJsonDataset(Path path) throws IOException {
        List<EvaluationCase> cases = OBJECT_MAPPER.readValue(path.toFile(), new TypeReference<>() {});
        return EvaluationDataset.builder()
                .source(buildDatasetSource(path))
                .cases(normalizeCases(cases))
                .build();
    }

    private EvaluationDataset loadCsvDataset(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Dataset CSV is empty: " + path);
        }

        List<String> headers = splitCsvLine(lines.get(0)).stream()
                .map(s -> s.trim())
                .map(header -> header.toLowerCase(Locale.ROOT))
                .toList();
        int questionIndex = headerIndexOrMinusOne(headers, "question");
        int relevantIndex = Math.max(headerIndexOrMinusOne(headers, "relevantdocids"), headerIndexOrMinusOne(headers, "relevant_doc_ids"));
        int categoryIndex = Math.max(headerIndexOrMinusOne(headers, "category"), headerIndexOrMinusOne(headers, "type"));

        if (questionIndex < 0 || relevantIndex < 0) {
            throw new IllegalArgumentException("CSV header must contain question and relevantDocIds/relevant_doc_ids");
        }

        List<EvaluationCase> cases = new ArrayList<>();
        for (int lineIndex = 1; lineIndex < lines.size(); lineIndex++) {
            String line = lines.get(lineIndex);
            if (!StringUtils.hasText(line)) {
                continue;
            }
            List<String> values = splitCsvLine(line);
            if (questionIndex >= values.size() || relevantIndex >= values.size()) {
                continue;
            }
            String question = values.get(questionIndex).trim();
            if (!StringUtils.hasText(question)) {
                continue;
            }
            String relevantIdsRaw = values.get(relevantIndex).trim();
            String category = categoryIndex >= 0 && categoryIndex < values.size() && StringUtils.hasText(values.get(categoryIndex))
                    ? values.get(categoryIndex).trim()
                    : DEFAULT_CATEGORY;
            cases.add(EvaluationCase.builder()
                    .question(question)
                    .relevantDocIds(parseRelevantDocIds(relevantIdsRaw))
                    .category(category)
                    .build());
        }

        return EvaluationDataset.builder()
                .source(buildDatasetSource(path))
                .cases(normalizeCases(cases))
                .build();
    }

    private String buildDatasetSource(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }

        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }

        String fingerprint = HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        return path.getFileName() + "#" + fingerprint;
    }

    private String fingerprint(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private int headerIndexOrMinusOne(List<String> headers, String key) {
        return headers.indexOf(key.toLowerCase(Locale.ROOT));
    }

    private List<String> splitCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (ch == '"') {
                if (inQuotes && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }

    private List<String> parseRelevantDocIds(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            try {
                List<String> ids = OBJECT_MAPPER.readValue(trimmed, new TypeReference<>() {});
                return ids.stream().filter(StringUtils::hasText).map(s -> s.trim()).distinct().toList();
            } catch (Exception ignored) {
                // Fall through to simple delimiter parsing.
            }
        }
        return Arrays.stream(trimmed.split("[|;]"))
                .map(s -> s.trim())
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private EvaluationDataset toDataset(Map<String, List<String>> testDataset) {
        List<EvaluationCase> cases = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : testDataset.entrySet()) {
            cases.add(EvaluationCase.builder()
                    .question(entry.getKey())
                    .relevantDocIds(entry.getValue() == null ? List.of() : entry.getValue())
                    .category(DEFAULT_CATEGORY)
                    .build());
        }
        return EvaluationDataset.builder()
                .source("inline")
                .cases(normalizeCases(cases))
                .build();
    }


    private EvaluationDataset buildSampleDataset() {
        return EvaluationDataset.builder()
                .source("built-in-sample")
                .cases(List.of(
                        EvaluationCase.builder().question("退款流程是什么").relevantDocIds(List.of("doc_refund_01", "doc_refund_02")).category("process").build(),
                        EvaluationCase.builder().question("如何修改收货地址").relevantDocIds(List.of("doc_address_01", "doc_address_02")).category("process").build(),
                        EvaluationCase.builder().question("商品质量问题怎么处理").relevantDocIds(List.of("doc_quality_01", "doc_quality_02")).category("after_sale").build(),
                        EvaluationCase.builder().question("订单发货时间").relevantDocIds(List.of("doc_shipping_01", "doc_shipping_02")).category("fact").build(),
                        EvaluationCase.builder().question("会员权益有哪些").relevantDocIds(List.of("doc_vip_01", "doc_vip_02")).category("fact").build()
                ))
                .build();
    }

    private List<EvaluationCase> normalizeCases(List<EvaluationCase> cases) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("Evaluation dataset must contain at least one case");
        }
        List<EvaluationCase> normalized = new ArrayList<>();
        for (EvaluationCase evaluationCase : cases) {
            if (evaluationCase == null || !StringUtils.hasText(evaluationCase.getQuestion())) {
                continue;
            }
            List<String> relevantDocIds = evaluationCase.getRelevantDocIds() == null ? List.of() : evaluationCase.getRelevantDocIds().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .toList();
            if (relevantDocIds.isEmpty()) {
                throw new IllegalArgumentException("Every evaluation case must contain at least one relevantDocId");
            }
            normalized.add(EvaluationCase.builder()
                    .question(evaluationCase.getQuestion().trim())
                    .relevantDocIds(relevantDocIds)
                    .category(StringUtils.hasText(evaluationCase.getCategory()) ? evaluationCase.getCategory().trim() : DEFAULT_CATEGORY)
                    .build());
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Evaluation dataset must contain at least one valid case");
        }
        return normalized;
    }

    private EvaluationProfile defaultProfile() {
        return EvaluationProfile.builder()
                .name("default")
                .similarityThreshold(aiProperties.getRag().getSimilarityThreshold())
                .hybridSearch(aiProperties.getRag().isEnableHybridSearch())
                .build();
    }

    private EvaluationProfile runtimeProfile(String name,
                                             Double similarityThreshold,
                                             Boolean hybridSearch) {
        return resolveProfile(EvaluationProfile.builder()
                .name(name)
                .similarityThreshold(similarityThreshold)
                .hybridSearch(hybridSearch)
                .build());
    }

    private EvaluationProfile resolveProfile(EvaluationProfile requested) {
        return EvaluationProfile.builder()
                .name(StringUtils.hasText(requested.getName()) ? requested.getName() : buildProfileName(requested))
                .similarityThreshold(requested.getSimilarityThreshold() != null && requested.getSimilarityThreshold() > 0
                        ? requested.getSimilarityThreshold()
                        : aiProperties.getRag().getSimilarityThreshold())
                .hybridSearch(requested.getHybridSearch() != null ? requested.getHybridSearch() : aiProperties.getRag().isEnableHybridSearch())
                .build();
    }

    private String buildProfileName(EvaluationProfile profile) {
        double threshold = profile.getSimilarityThreshold() != null && profile.getSimilarityThreshold() > 0
                ? profile.getSimilarityThreshold()
                : aiProperties.getRag().getSimilarityThreshold();
        boolean hybridSearch = profile.getHybridSearch() != null ? profile.getHybridSearch() : aiProperties.getRag().isEnableHybridSearch();
        return String.format(Locale.ROOT, "threshold=%.2f,hybrid=%s", threshold, hybridSearch);
    }

    private List<Integer> sanitizeTopKs(List<Integer> topKs) {
        List<Integer> source = topKs == null || topKs.isEmpty() ? List.of(aiProperties.getRag().getTopK()) : topKs;
        List<Integer> sanitized = source.stream()
                .filter(value -> value != null && value > 0)
                .distinct()
                .toList();
        return sanitized.isEmpty() ? List.of(aiProperties.getRag().getTopK()) : sanitized;
    }

    private double average(List<Double> values) {
        return values.stream().mapToDouble(d -> d.doubleValue()).average().orElse(0);
    }

    private double ratio(int numerator, int denominator) {
        return denominator <= 0 ? 0 : (double) numerator / denominator;
    }

    private double averageLong(List<Long> values) {
        return values.stream().mapToLong(l -> l.longValue()).average().orElse(0);
    }

    private long percentileLong(List<Long> values, int percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private double calculateF1(double recall, double precision) {
        if (recall + precision == 0) {
            return 0;
        }
        return 2 * (recall * precision) / (recall + precision);
    }

    private Map<String, Object> getConfigSnapshot(EvaluationProfile profile) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("profile", profile.getName());
        config.put("topK", aiProperties.getRag().getTopK());
        config.put("similarityThreshold", profile.getSimilarityThreshold());
        config.put("hybridSearch", Boolean.TRUE.equals(profile.getHybridSearch()));
        config.put("hybridVectorWeight", aiProperties.getRag().getHybridVectorWeight());
        config.put("hybridBm25Weight", aiProperties.getRag().getHybridBm25Weight());
        config.put("hybridRrfK", aiProperties.getRag().getHybridRrfK());
        config.put("hybridVectorCandidateTopK", aiProperties.getRag().getHybridVectorCandidateTopK());
        config.put("hybridBm25CandidateTopK", aiProperties.getRag().getHybridBm25CandidateTopK());
        config.put("hybridBm25CorpusMaxDocs", aiProperties.getRag().getHybridBm25CorpusMaxDocs());
        config.put("hybridCorpusBm25Enabled", aiProperties.getRag().isHybridCorpusBm25Enabled());
        config.put("hybridMysqlFulltextEnabled", aiProperties.getRag().isHybridMysqlFulltextEnabled());
        config.put("hybridMysqlCandidateTopK", aiProperties.getRag().getHybridMysqlCandidateTopK());
        config.put("hybridPreserveVectorTopK", aiProperties.getRag().getHybridPreserveVectorTopK());
        config.put("hybridCrossEncoderEnabled", aiProperties.getRag().isHybridCrossEncoderEnabled());
        config.put("hybridRerankCandidateTopK", aiProperties.getRag().getHybridRerankCandidateTopK());
        config.put("hybridRerankFailOpen", aiProperties.getRag().isHybridRerankFailOpen());
        config.put("bm25StopwordEnabled", aiProperties.getRag().isBm25StopwordEnabled());
        config.put("evaluationRetrievalStrategy", "single-max-top-k");
        config.put("chunkSize", aiProperties.getDocument().getChunkSize());
        config.put("chunkOverlap", aiProperties.getDocument().getChunkOverlap());
        config.put("chunkingComparable", false);
        config.put("chunkingNote", "chunkSize/chunkOverlap require re-ingestion and are snapshot-only in this report");
        return config;
    }

    private static class MetricAccumulator {
        private final List<Double> recalls = new ArrayList<>();
        private final List<Double> precisions = new ArrayList<>();
        private final List<Long> latencies = new ArrayList<>();
        private int emptyResultCount;

        private void add(double recall, double precision, long latency, int resultCount) {
            recalls.add(recall);
            precisions.add(precision);
            latencies.add(latency);
            if (resultCount == 0) {
                emptyResultCount++;
            }
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvaluationCase {
        private String question;
        private List<String> relevantDocIds;
        private String category;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvaluationDataset {
        private String source;
        private List<EvaluationCase> cases;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvaluationProfile {
        private String name;
        private Double similarityThreshold;
        private Boolean hybridSearch;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueryDiagnostic {
        private String caseHash;
        private String category;
        private List<String> relevantDocIds;
        private List<String> actualDocIds;
        private List<String> retrievalSources;
        private Integer firstRelevantRank;
        private int maxTopK;
        private long latencyMs;
    }

    @Data
    public static class EvaluationReport {
        private String datasetSource;
        private int datasetSize;
        private List<Integer> topKs;
        private Map<String, Object> configSnapshot;
        private Map<String, Map<String, Object>> metrics = new LinkedHashMap<>();
        private Map<String, Map<String, Map<String, Object>>> categoryMetrics = new LinkedHashMap<>();
        private List<QueryDiagnostic> diagnostics = new ArrayList<>();

        public void addMetric(String k, String name, Object value) {
            metrics.computeIfAbsent(k, ignored -> new LinkedHashMap<>());
            metrics.get(k).put(name, value);
        }

        public void addMetric(int k, String name, Object value) {
            addMetric(String.valueOf(k), name, value);
        }

        public void addCategoryMetric(String category, String k, String name, Object value) {
            categoryMetrics.computeIfAbsent(category, ignored -> new LinkedHashMap<>());
            categoryMetrics.get(category).computeIfAbsent(k, ignored -> new LinkedHashMap<>());
            categoryMetrics.get(category).get(k).put(name, value);
        }

        public String toFormattedSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("RAG Evaluation Summary\n");
            sb.append("dataset=").append(datasetSource).append(", size=").append(datasetSize).append('\n');
            for (String k : metrics.keySet()) {
                Map<String, Object> metric = metrics.get(k);
                sb.append(String.format(Locale.ROOT,
                        "k=%s samples=%d recall=%.2f%% precision=%.2f%% f1=%.2f%% hit=%.2f%% empty=%.2f%% avg=%.0fms p50=%dms p95=%dms p99=%dms\n",
                        k,
                        (long) getDouble(metric, "sampleCount"),
                        getDouble(metric, "recall") * 100,
                        getDouble(metric, "precision") * 100,
                        getDouble(metric, "f1") * 100,
                        getDouble(metric, "retrievalHitRate") * 100,
                        getDouble(metric, "emptyResultRate") * 100,
                        getDouble(metric, "avgLatency"),
                        (long) getDouble(metric, "p50Latency"),
                        (long) getDouble(metric, "p95Latency"),
                        (long) getDouble(metric, "p99Latency")));
            }
            sb.append("note=chunkSize/chunkOverlap are snapshot-only and require re-ingestion for fair comparison");
            return sb.toString();
        }

        private double getDouble(Map<String, Object> map, String key) {
            Object value = map.get(key);
            return value instanceof Number number ? number.doubleValue() : 0;
        }
    }

    @Data
    public static class ComparisonReport {
        private String datasetSource;
        private int datasetSize;
        private List<Integer> topKs;
        private Map<String, EvaluationReport> reports = new LinkedHashMap<>();

        public String toFormattedSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("RAG Comparison Summary\n");
            sb.append("dataset=").append(datasetSource).append(", size=").append(datasetSize).append('\n');
            for (Map.Entry<String, EvaluationReport> entry : reports.entrySet()) {
                sb.append("profile=").append(entry.getKey()).append('\n');
                sb.append(entry.getValue().toFormattedSummary()).append('\n');
            }
            return sb.toString().trim();
        }
    }
}

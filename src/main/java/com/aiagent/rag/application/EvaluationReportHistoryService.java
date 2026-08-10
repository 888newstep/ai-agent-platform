package com.aiagent.rag.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 评测报告的轻量级文件存储，负责历史查询和两份报告的指标差值计算。
 *
 * <p>报告文件只允许使用应用生成的文件名，避免通过历史接口读取任意本地文件。</p>
 */
@Slf4j
@Service
public class EvaluationReportHistoryService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> REPORT_TYPE = new TypeReference<>() {
    };
    private static final Pattern REPORT_FILE_PATTERN = Pattern.compile(
            "^rag-evaluation-\\d{13}(?:-[0-9a-fA-F-]{36})?\\.json$");
    private static final int MAX_HISTORY_LIMIT = 100;

    private final Path reportDirectory;

    public EvaluationReportHistoryService(
            @Value("${ai.evaluation.report-directory:./evaluation-reports}") String configuredDirectory) {
        if (!StringUtils.hasText(configuredDirectory)) {
            throw new IllegalArgumentException("Evaluation report directory must not be blank");
        }
        this.reportDirectory = Path.of(configuredDirectory).toAbsolutePath().normalize();
    }

    public SavedReport save(Map<String, Object> reportPayload) throws IOException {
        Objects.requireNonNull(reportPayload, "reportPayload");
        Files.createDirectories(reportDirectory);

        String fileName = "rag-evaluation-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID() + ".json";
        Path reportPath = resolveReportPath(fileName);
        Files.writeString(
                reportPath,
                OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(reportPayload),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
        return new SavedReport(fileName, reportPath.toString());
    }

    public List<Map<String, Object>> list(int requestedLimit) throws IOException {
        int limit = Math.min(Math.max(requestedLimit, 1), MAX_HISTORY_LIMIT);
        if (!Files.isDirectory(reportDirectory)) {
            return List.of();
        }

        List<Path> reportPaths;
        try (Stream<Path> paths = Files.list(reportDirectory)) {
            reportPaths = paths
                    .filter(this::isReportFile)
                    .sorted(Comparator.comparingLong(this::lastModified).reversed())
                    .limit(limit)
                    .toList();
        }

        List<Map<String, Object>> reports = new ArrayList<>();
        for (Path reportPath : reportPaths) {
            Map<String, Object> summary = readSummary(reportPath);
            if (summary != null) {
                reports.add(summary);
            }
        }
        return reports;
    }

    public Map<String, Object> compare(String baselineFileName, String candidateFileName) throws IOException {
        Map<String, Object> baseline = readReport(resolveReportPath(baselineFileName));
        Map<String, Object> candidate = readReport(resolveReportPath(candidateFileName));

        List<String> warnings = new ArrayList<>();
        if (!Objects.equals(baseline.get("datasetSource"), candidate.get("datasetSource"))) {
            warnings.add("datasetSource differs");
        }
        if (!Objects.equals(baseline.get("datasetSize"), candidate.get("datasetSize"))) {
            warnings.add("datasetSize differs");
        }
        if (!Objects.equals(baseline.get("topKs"), candidate.get("topKs"))) {
            warnings.add("topKs differs");
        }

        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("comparable", warnings.isEmpty());
        comparison.put("warnings", warnings);
        comparison.put("baseline", snapshot(baselineFileName, baseline));
        comparison.put("candidate", snapshot(candidateFileName, candidate));
        comparison.put("metricDeltas", calculateMetricDeltas(
                asMap(baseline.get("metrics")),
                asMap(candidate.get("metrics"))));
        return comparison;
    }

    private Map<String, Object> readSummary(Path reportPath) {
        try {
            Map<String, Object> report = readReport(reportPath);
            Map<String, Object> summary = snapshot(reportPath.getFileName().toString(), report);
            summary.put("lastModifiedAt", Instant.ofEpochMilli(lastModified(reportPath)).toString());
            return summary;
        } catch (IOException ex) {
            log.warn("跳过无法读取的评测报告: {}", reportPath, ex);
            return null;
        }
    }

    private Map<String, Object> readReport(Path reportPath) throws IOException {
        if (!Files.isRegularFile(reportPath)) {
            throw new IllegalArgumentException("Evaluation report not found: " + reportPath.getFileName());
        }
        return OBJECT_MAPPER.readValue(Files.readString(reportPath, StandardCharsets.UTF_8), REPORT_TYPE);
    }

    private Map<String, Object> snapshot(String fileName, Map<String, Object> report) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("fileName", fileName);
        copyIfPresent(snapshot, report, "generatedAt");
        copyIfPresent(snapshot, report, "datasetSource");
        copyIfPresent(snapshot, report, "datasetSize");
        copyIfPresent(snapshot, report, "topKs");
        snapshot.put("metrics", report.getOrDefault("metrics", Map.of()));
        snapshot.put("config", report.getOrDefault("config", Map.of()));
        return snapshot;
    }

    private Map<String, Object> calculateMetricDeltas(Map<?, ?> baselineMetrics,
                                                      Map<?, ?> candidateMetrics) {
        Set<String> topKs = new LinkedHashSet<>();
        baselineMetrics.keySet().forEach(key -> topKs.add(String.valueOf(key)));
        candidateMetrics.keySet().forEach(key -> topKs.add(String.valueOf(key)));

        Map<String, Object> deltas = new LinkedHashMap<>();
        for (String topK : topKs) {
            Map<?, ?> baseline = asMap(findByStringKey(baselineMetrics, topK));
            Map<?, ?> candidate = asMap(findByStringKey(candidateMetrics, topK));
            Set<String> metricNames = new LinkedHashSet<>();
            baseline.keySet().forEach(key -> metricNames.add(String.valueOf(key)));
            candidate.keySet().forEach(key -> metricNames.add(String.valueOf(key)));

            Map<String, Object> metricDeltas = new LinkedHashMap<>();
            for (String metricName : metricNames) {
                Number baselineValue = asNumber(findByStringKey(baseline, metricName));
                Number candidateValue = asNumber(findByStringKey(candidate, metricName));
                if (baselineValue != null && candidateValue != null) {
                    metricDeltas.put(metricName, candidateValue.doubleValue() - baselineValue.doubleValue());
                }
            }
            if (!metricDeltas.isEmpty()) {
                deltas.put(topK, metricDeltas);
            }
        }
        return deltas;
    }

    private Path resolveReportPath(String fileName) {
        if (!StringUtils.hasText(fileName) || !REPORT_FILE_PATTERN.matcher(fileName).matches()) {
            throw new IllegalArgumentException("Invalid evaluation report file name");
        }
        Path reportPath = reportDirectory.resolve(fileName).normalize();
        if (!reportDirectory.equals(reportPath.getParent())) {
            throw new IllegalArgumentException("Evaluation report must be inside the report directory");
        }
        return reportPath;
    }

    private boolean isReportFile(Path path) {
        return Files.isRegularFile(path)
                && REPORT_FILE_PATTERN.matcher(path.getFileName().toString()).matches();
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return Long.MIN_VALUE;
        }
    }

    private void copyIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private Object findByStringKey(Map<?, ?> map, String key) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (key.equals(String.valueOf(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Number asNumber(Object value) {
        return value instanceof Number number ? number : null;
    }

    public record SavedReport(String fileName, String absolutePath) {
    }
}
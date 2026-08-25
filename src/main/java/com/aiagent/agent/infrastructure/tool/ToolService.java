package com.aiagent.agent.infrastructure.tool;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import dev.langchain4j.agent.tool.Tool;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ToolService {

    private static final Pattern TABLE_PATTERN = Pattern.compile("\\b(?:from|join)\\s+([a-zA-Z0-9_.$]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORBIDDEN_SQL_PATTERN = Pattern.compile(
            "\\b(?:insert|update|delete|drop|alter|truncate|create|grant|revoke|merge|call|execute)\\b|\\bfor\\s+update\\b",
            Pattern.CASE_INSENSITIVE
    );

    private final DataSource dataSource;
    private final AiProperties aiProperties;
    private final PlatformMetricsService metricsService;
    private final HttpClient httpClient;

    public ToolService(DataSource dataSource,
                       AiProperties aiProperties,
                       PlatformMetricsService metricsService) {
        this.dataSource = dataSource;
        this.aiProperties = aiProperties;
        this.metricsService = metricsService;
        int configuredTimeout = aiProperties.getTool().getApiCall().getTimeout();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, configuredTimeout)))
                .build();
    }

    @Tool("query_database")
    public String queryDatabase(String sql) {
        Timer.Sample sample = startToolSample();
        if (!aiProperties.getTool().getDatabaseQuery().isEnabled()) {
            recordToolExecution("query_database", "disabled", false, sample);
            return "Error: database query tool is disabled.";
        }

        String executableSql;
        try {
            executableSql = buildExecutableSelect(sql);
        } catch (IllegalArgumentException e) {
            log.warn("Rejected SQL query: {}", e.getMessage());
            recordToolExecution("query_database", "invalid_input", false, sample);
            return "Error: " + e.getMessage();
        }

        log.info("Executing guarded SQL query: {}", executableSql);

        List<Map<String, Object>> results = new ArrayList<>();
        int maxRows = aiProperties.getTool().getDatabaseQuery().getMaxRows();
        int queryTimeoutSeconds = aiProperties.getTool().getDatabaseQuery().getQueryTimeoutSeconds();
        if (maxRows <= 0 || queryTimeoutSeconds <= 0) {
            recordToolExecution("query_database", "invalid_config", false, sample);
            return "Error: database query tool configuration is invalid.";
        }
        String status = "error";
        boolean success = false;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(executableSql)) {

            conn.setReadOnly(true);
            stmt.setMaxRows(maxRows);
            stmt.setQueryTimeout(queryTimeoutSeconds);

            try (ResultSet rs = stmt.executeQuery()) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(metaData.getColumnName(i), rs.getObject(i));
                    }
                    results.add(row);
                }
            }

            status = "success";
            success = true;
            return formatResults(results);
        } catch (SQLException e) {
            log.error("Database query failed: sqlState={}, errorCode={}", e.getSQLState(), e.getErrorCode());
            return "Error: database query failed.";
        } finally {
            recordToolExecution("query_database", status, success, sample);
        }
    }

    @Tool("call_external_api")
    public String callExternalApi(String url, String method, String body) {
        Timer.Sample sample = startToolSample();
        if (!aiProperties.getTool().getApiCall().isEnabled()) {
            recordToolExecution("call_external_api", "disabled", false, sample);
            return "Error: external API tool is disabled.";
        }

        String normalizedMethod;
        URI uri;
        try {
            normalizedMethod = normalizeMethod(method);
            validateApiCallConfiguration(normalizedMethod, body);
            uri = validateUri(url, normalizedMethod);
        } catch (IllegalArgumentException e) {
            log.warn("Rejected external API call: {}", e.getMessage());
            recordToolExecution("call_external_api", "invalid_input", false, sample);
            return "Error: " + e.getMessage();
        }

        log.info("Calling guarded API: {} {}", normalizedMethod, safeTarget(uri));

        int timeoutMs = aiProperties.getTool().getApiCall().getTimeout();
        String status = "error";
        boolean success = false;

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofMillis(timeoutMs));

            if ("POST".equals(normalizedMethod)) {
                requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body != null ? body : ""))
                        .header("Content-Type", "application/json");
            } else {
                requestBuilder.GET();
            }

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            success = response.statusCode() >= 200 && response.statusCode() < 300;
            status = success ? "success" : "http_error";
            String responseBody = truncate(response.body(), aiProperties.getTool().getApiCall().getMaxResponseChars());
            return "Status: " + response.statusCode() + "\nResponse: " + responseBody;
        } catch (java.net.http.HttpTimeoutException e) {
            status = "timeout";
            log.warn("External API call timed out: {} {}", normalizedMethod, safeTarget(uri));
            return "Error: request timed out.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("External API call interrupted: {} {}", normalizedMethod, safeTarget(uri));
            return "Error: request interrupted.";
        } catch (Exception e) {
            log.error("API call failed: method={}, target={}, type={}",
                    normalizedMethod, safeTarget(uri), e.getClass().getSimpleName());
            return "Error: external API call failed.";
        } finally {
            recordToolExecution("call_external_api", status, success, sample);
        }
    }

    private Timer.Sample startToolSample() {
        return metricsService.startSample();
    }

    private void recordToolExecution(String toolName,
                                     String status,
                                     boolean success,
                                     Timer.Sample sample) {
        metricsService.recordToolExecution(toolName, status, success, sample);
    }

    private String buildExecutableSelect(String sql) {
        if (!StringUtils.hasText(sql)) {
            throw new IllegalArgumentException("SQL must not be blank.");
        }

        String normalized = sql.trim().replaceAll(";+\\s*$", "");
        String lowerCase = normalized.toLowerCase(Locale.ROOT);

        if (!lowerCase.startsWith("select ")) {
            throw new IllegalArgumentException("Only SELECT queries are allowed.");
        }
        if (lowerCase.contains("--") || lowerCase.contains("/*") || lowerCase.contains("*/") || lowerCase.contains(";")) {
            throw new IllegalArgumentException("Comments and multi-statement SQL are not allowed.");
        }
        Matcher forbiddenMatcher = FORBIDDEN_SQL_PATTERN.matcher(lowerCase);
        if (forbiddenMatcher.find()) {
            throw new IllegalArgumentException("Forbidden SQL keyword detected: " + forbiddenMatcher.group());
        }

        Set<String> referencedTables = extractReferencedTables(normalized);
        if (referencedTables.isEmpty()) {
            throw new IllegalArgumentException("Query must reference a whitelisted table.");
        }

        Set<String> allowedTables = aiProperties.getTool().getDatabaseQuery().getAllowedTables().stream()
                .map(table -> table.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (allowedTables.isEmpty()) {
            throw new IllegalArgumentException("No allowed database tables are configured.");
        }

        for (String table : referencedTables) {
            if (!allowedTables.contains(table)) {
                throw new IllegalArgumentException("Access to table is not allowed: " + table);
            }
        }

        int maxRows = aiProperties.getTool().getDatabaseQuery().getMaxRows();
        return "SELECT * FROM (" + normalized + ") tool_query LIMIT " + maxRows;
    }

    private Set<String> extractReferencedTables(String sql) {
        Matcher matcher = TABLE_PATTERN.matcher(sql);
        Set<String> tables = new HashSet<>();
        while (matcher.find()) {
            String table = matcher.group(1).trim().toLowerCase(Locale.ROOT);
            if (table.contains(".")) {
                table = table.substring(table.lastIndexOf('.') + 1);
            }
            tables.add(table);
        }
        return tables;
    }

    private String normalizeMethod(String method) {
        String normalizedMethod = StringUtils.hasText(method) ? method.trim().toUpperCase(Locale.ROOT) : "GET";
        List<String> allowedMethods = aiProperties.getTool().getApiCall().getAllowedMethods();
        if (allowedMethods == null || allowedMethods.isEmpty()) {
            throw new IllegalArgumentException("No allowed HTTP methods are configured.");
        }

        boolean allowed = allowedMethods.stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(normalizedMethod::equals);
        if (!allowed) {
            throw new IllegalArgumentException("HTTP method is not allowed: " + normalizedMethod);
        }
        return normalizedMethod;
    }

    private void validateApiCallConfiguration(String method, String body) {
        AiProperties.ApiCall config = aiProperties.getTool().getApiCall();
        if (config.getTimeout() <= 0 || config.getMaxResponseChars() <= 0 || config.getMaxRequestChars() <= 0) {
            throw new IllegalArgumentException("External API tool configuration is invalid.");
        }
        if ("POST".equals(method)) {
            String requestBody = body == null ? "" : body;
            int requestCharacters = requestBody.codePointCount(0, requestBody.length());
            if (requestCharacters > config.getMaxRequestChars()) {
                throw new IllegalArgumentException("Request body exceeds the configured character limit.");
            }
        }
    }

    private URI validateUri(String url, String method) {
        if (!StringUtils.hasText(url)) {
            throw new IllegalArgumentException("URL must not be blank.");
        }

        URI uri = URI.create(url.trim());
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!StringUtils.hasText(scheme) || !StringUtils.hasText(host)) {
            throw new IllegalArgumentException("URL must be absolute and include a host.");
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Only HTTP and HTTPS URLs are allowed.");
        }

        List<String> allowedHosts = aiProperties.getTool().getApiCall().getAllowedHosts();
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            throw new IllegalArgumentException("No allowed API hosts are configured.");
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT);
        boolean hostAllowed = allowedHosts.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(allowed -> matchesHost(normalizedHost, allowed));
        if (!hostAllowed) {
            throw new IllegalArgumentException("Host is not in the allowlist: " + normalizedHost);
        }
        if (isPrivateAddress(normalizedHost)) {
            throw new IllegalArgumentException("Private or loopback hosts are not allowed.");
        }
        if ("POST".equals(method) && uri.getQuery() != null && uri.getQuery().length() > 1024) {
            throw new IllegalArgumentException("Query string is too long for POST requests.");
        }

        return uri;
    }

    private boolean matchesHost(String normalizedHost, String allowedHost) {
        if (allowedHost.startsWith("*.")) {
            return normalizedHost.endsWith(allowedHost.substring(1));
        }
        return normalizedHost.equals(allowedHost);
    }

    private boolean isPrivateAddress(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress();
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to resolve host: " + host);
        }
    }

    private String safeTarget(URI uri) {
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        String path = StringUtils.hasText(uri.getRawPath()) ? uri.getRawPath() : "/";
        return uri.getScheme() + "://" + uri.getHost() + port + path;
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...";
    }

    private String formatResults(List<Map<String, Object>> results) {
        if (results.isEmpty()) {
            return "No results found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" results:\n\n");

        for (Map<String, Object> row : results) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}

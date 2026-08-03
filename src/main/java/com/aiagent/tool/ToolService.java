package com.aiagent.tool;

import com.aiagent.config.AiProperties;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class ToolService {

    private static final Pattern TABLE_PATTERN = Pattern.compile("\\b(?:from|join)\\s+([a-zA-Z0-9_.$]+)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> FORBIDDEN_SQL_KEYWORDS = Set.of(
            "insert", "update", "delete", "drop", "alter", "truncate",
            "create", "grant", "revoke", "merge", "call", "execute", "for update"
    );

    private final DataSource dataSource;
    private final AiProperties aiProperties;

    @Tool("query_database")
    public String queryDatabase(String sql) {
        if (!aiProperties.getTool().getDatabaseQuery().isEnabled()) {
            return "Error: database query tool is disabled.";
        }

        String executableSql;
        try {
            executableSql = buildExecutableSelect(sql);
        } catch (IllegalArgumentException e) {
            log.warn("Rejected SQL query: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }

        log.info("Executing guarded SQL query: {}", executableSql);

        List<Map<String, Object>> results = new ArrayList<>();
        int maxRows = aiProperties.getTool().getDatabaseQuery().getMaxRows();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(executableSql)) {

            conn.setReadOnly(true);
            stmt.setMaxRows(maxRows);

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

            return formatResults(results);
        } catch (SQLException e) {
            log.error("Database query failed", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("call_external_api")
    public String callExternalApi(String url, String method, String body) {
        if (!aiProperties.getTool().getApiCall().isEnabled()) {
            return "Error: external API tool is disabled.";
        }

        String normalizedMethod;
        URI uri;
        try {
            normalizedMethod = normalizeMethod(method);
            uri = validateUri(url, normalizedMethod);
        } catch (IllegalArgumentException e) {
            log.warn("Rejected external API call: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }

        log.info("Calling guarded API: {} {}", normalizedMethod, uri);

        int timeoutMs = aiProperties.getTool().getApiCall().getTimeout();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();

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

            HttpResponse<String> response = client.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            String responseBody = truncate(response.body(), aiProperties.getTool().getApiCall().getMaxResponseChars());
            return "Status: " + response.statusCode() + "\nResponse: " + responseBody;
        } catch (Exception e) {
            log.error("API call failed", e);
            return "Error: " + e.getMessage();
        }
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
        for (String forbiddenKeyword : FORBIDDEN_SQL_KEYWORDS) {
            if (lowerCase.contains(forbiddenKeyword)) {
                throw new IllegalArgumentException("Forbidden SQL keyword detected: " + forbiddenKeyword);
            }
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

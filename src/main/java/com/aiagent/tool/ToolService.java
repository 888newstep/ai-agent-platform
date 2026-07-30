package com.aiagent.tool;

import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolService {

    private final DataSource dataSource;

    @Tool("query_database")
    public String queryDatabase(String sql) {
        log.info("Executing SQL query: {}", sql);
        List<Map<String, Object>> results = new ArrayList<>();
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(metaData.getColumnName(i), rs.getObject(i));
                }
                results.add(row);
            }
            
            return formatResults(results);
        } catch (SQLException e) {
            log.error("Database query failed", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("call_external_api")
    public String callExternalApi(String url, String method, String body) {
        log.info("Calling API: {} {}", method, url);
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest.Builder requestBuilder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url));
            
            if ("POST".equalsIgnoreCase(method)) {
                requestBuilder.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body != null ? body : ""))
                        .header("Content-Type", "application/json");
            } else {
                requestBuilder.GET();
            }
            
            java.net.http.HttpResponse<String> response = client.send(
                    requestBuilder.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()
            );
            
            return "Status: " + response.statusCode() + "\nResponse: " + response.body();
        } catch (Exception e) {
            log.error("API call failed", e);
            return "Error: " + e.getMessage();
        }
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

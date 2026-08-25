package com.aiagent.agent.infrastructure.tool;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolServiceTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    @Mock
    private ResultSetMetaData resultSetMetaData;

    private AiProperties aiProperties;
    private ToolService toolService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.getTool().getDatabaseQuery().setAllowedTables(List.of("users"));
        aiProperties.getTool().getDatabaseQuery().setMaxRows(5);
        aiProperties.getTool().getDatabaseQuery().setQueryTimeoutSeconds(3);
        aiProperties.getTool().getApiCall().setAllowedHosts(List.of("api.example.com"));
        meterRegistry = new SimpleMeterRegistry();
        toolService = new ToolService(dataSource, aiProperties, new PlatformMetricsService(meterRegistry));
    }

    @Test
    void shouldRejectNonSelectSql() {
        String response = toolService.queryDatabase("delete from users");

        assertThat(response).contains("Only SELECT queries are allowed");
    }

    @Test
    void shouldRejectSqlAgainstNonWhitelistedTable() {
        String response = toolService.queryDatabase("select * from admins");

        assertThat(response).contains("Access to table is not allowed");
        assertThat(meterRegistry.get("ai.agent.tool.total")
                .tags("tool", "query_database", "status", "invalid_input", "outcome", "error")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void shouldWrapAndExecuteGuardedSelectQuery() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(argThat(sql -> sql.contains("select updated_at from users") && sql.endsWith("LIMIT 5")))).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(resultSetMetaData);
        when(resultSetMetaData.getColumnCount()).thenReturn(1);
        when(resultSetMetaData.getColumnName(1)).thenReturn("updated_at");
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn(123L);

        String response = toolService.queryDatabase("select updated_at from users");

        assertThat(response).contains("Found 1 results").contains("updated_at: 123");
        assertThat(meterRegistry.get("ai.agent.tool.total")
                .tags("tool", "query_database", "status", "success", "outcome", "success")
                .counter().count()).isEqualTo(1.0);
        verify(connection).setReadOnly(true);
        verify(preparedStatement).setMaxRows(5);
        verify(preparedStatement).setQueryTimeout(3);
    }

    @Test
    void shouldNotExposeDatabaseErrorDetails() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("jdbc://user:secret@host"));

        String response = toolService.queryDatabase("select id from users");

        assertThat(response).isEqualTo("Error: database query failed.")
                .doesNotContain("secret");
    }

    @Test
    void shouldRejectInvalidDatabaseToolConfiguration() {
        aiProperties.getTool().getDatabaseQuery().setQueryTimeoutSeconds(0);

        String response = toolService.queryDatabase("select id from users");

        assertThat(response).contains("configuration is invalid");
    }

    @Test
    void shouldRejectOversizedPostBodyBeforeNetworkCall() {
        aiProperties.getTool().getApiCall().setMaxRequestChars(3);

        String response = toolService.callExternalApi("https://api.example.com/orders", "POST", "abcd");

        assertThat(response).contains("Request body exceeds");
    }

    @Test
    void shouldRejectApiCallOutsideAllowlist() {
        String response = toolService.callExternalApi("https://evil.example.com/orders", "GET", null);

        assertThat(response).contains("allowlist");
    }

    @Test
    void shouldRejectPrivateHostEvenIfAllowlisted() {
        aiProperties.getTool().getApiCall().setAllowedHosts(List.of("localhost"));

        String response = toolService.callExternalApi("http://localhost/internal", "GET", null);

        assertThat(response).contains("Private or loopback hosts are not allowed");
    }
}

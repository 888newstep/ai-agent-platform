package com.aiagent.tool;

import com.aiagent.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.getTool().getDatabaseQuery().setAllowedTables(List.of("users"));
        aiProperties.getTool().getDatabaseQuery().setMaxRows(5);
        aiProperties.getTool().getApiCall().setAllowedHosts(List.of("api.example.com"));
        toolService = new ToolService(dataSource, aiProperties);
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
    }

    @Test
    void shouldWrapAndExecuteGuardedSelectQuery() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(argThat(sql -> sql.contains("select id from users") && sql.endsWith("LIMIT 5")))).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(resultSetMetaData);
        when(resultSetMetaData.getColumnCount()).thenReturn(1);
        when(resultSetMetaData.getColumnName(1)).thenReturn("id");
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn(123L);

        String response = toolService.queryDatabase("select id from users");

        assertThat(response).contains("Found 1 results").contains("id: 123");
        verify(connection).setReadOnly(true);
        verify(preparedStatement).setMaxRows(5);
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

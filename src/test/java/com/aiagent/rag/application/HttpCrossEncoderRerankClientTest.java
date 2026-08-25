package com.aiagent.rag.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.shared.exception.KnowledgeRetrievalUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpCrossEncoderRerankClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private AiProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties = new AiProperties();
        AiProperties.CrossEncoder config = properties.getRag().getAdaptive().getCrossEncoder();
        config.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/rerank");
        config.setApiKey("test-key");
        config.setModelName("test-reranker");
        config.setTimeoutMs(2_000);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void shouldSendStandardRerankRequestAndParseScores() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/v1/rerank", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"results\":[{\"index\":1,\"relevance_score\":0.96},{\"index\":0,\"relevance_score\":0.72}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        List<CrossEncoderRerankClient.RerankScore> scores =
                new HttpCrossEncoderRerankClient(objectMapper, properties)
                        .rerank("退款流程", List.of("证据一", "证据二"));

        assertThat(scores).containsExactly(
                new CrossEncoderRerankClient.RerankScore(1, 0.96),
                new CrossEncoderRerankClient.RerankScore(0, 0.72));
        JsonNode payload = objectMapper.readTree(requestBody.get());
        assertThat(payload.path("model").asText()).isEqualTo("test-reranker");
        assertThat(payload.path("query").asText()).isEqualTo("退款流程");
        assertThat(payload.path("top_n").asInt()).isEqualTo(2);
        assertThat(payload.path("return_documents").asBoolean()).isFalse();
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
    }

    @Test
    void shouldRejectNonSuccessfulResponse() {
        server.createContext("/v1/rerank", exchange -> {
            byte[] response = "rate limited".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> new HttpCrossEncoderRerankClient(objectMapper, properties)
                .rerank("退款流程", List.of("证据")))
                .isInstanceOf(KnowledgeRetrievalUnavailableException.class)
                .hasMessageContaining("HTTP 429");
    }

    @Test
    void shouldRejectMissingApiKeyBeforeNetworkCall() {
        properties.getRag().getAdaptive().getCrossEncoder().setApiKey(" ");

        assertThatThrownBy(() -> new HttpCrossEncoderRerankClient(objectMapper, properties)
                .rerank("退款流程", List.of("证据")))
                .isInstanceOf(KnowledgeRetrievalUnavailableException.class)
                .hasMessageContaining("configuration is incomplete");
    }
}

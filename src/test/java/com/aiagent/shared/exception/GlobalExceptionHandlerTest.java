package com.aiagent.shared.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void shouldReturnServiceUnavailableWhenAsyncQueueIsFull() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleRejectedExecution(new RejectedExecutionException("queue full"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("message", "Server is busy; retry later");
    }
}

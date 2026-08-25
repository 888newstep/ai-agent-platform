package com.aiagent.shared.exception;

public class AuthenticationServiceUnavailableException extends RuntimeException {

    public AuthenticationServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

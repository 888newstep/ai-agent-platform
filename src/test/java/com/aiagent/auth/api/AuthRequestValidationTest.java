package com.aiagent.auth.api;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void shouldRejectWeakRegistrationInput() {
        RegisterRequest request = new RegisterRequest("a!", "short", "invalid-email");

        assertThat(validator.validate(request)).hasSize(3);
    }

    @Test
    void shouldAcceptValidRegistrationInput() {
        RegisterRequest request = new RegisterRequest(
                "customer_01", "strong-password", "customer@example.com");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void shouldRejectBlankLoginInput() {
        assertThat(validator.validate(new LoginRequest(" ", " "))).hasSize(2);
    }
}

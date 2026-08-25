package com.aiagent.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "username must not be blank")
        @Pattern(regexp = "^[A-Za-z0-9_-]{3,50}$",
                message = "username must contain 3 to 50 letters, digits, underscores or hyphens")
        String username,

        @NotBlank(message = "password must not be blank")
        @Size(min = 8, max = 64, message = "password must contain 8 to 64 characters")
        String password,

        @Email(message = "email format is invalid")
        @Size(max = 100, message = "email must not exceed 100 characters")
        String email
) {
}

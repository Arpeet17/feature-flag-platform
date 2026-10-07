package com.ffp.feature_flag_platform.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, Instant expiresAt, UserResponse user) {
    }

    public record UserResponse(Long id, String email, String displayName, Role role, Instant createdAt) {
        public static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(), user.getCreatedAt());
        }
    }

    public record CreateUserRequest(@NotBlank @Email String email,
                                    @NotBlank @Size(max = 100) String displayName,
                                    @NotBlank @Size(min = 8, max = 72) String password,
                                    @NotNull Role role) {
    }

    public record UpdateUserRoleRequest(@NotNull Role role) {
    }
}

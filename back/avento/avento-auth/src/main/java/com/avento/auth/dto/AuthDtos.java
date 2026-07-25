package com.avento.auth.dto;

import com.avento.auth.model.TokenAuditEventType;
import com.avento.auth.model.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {}

    public record BootstrapRequest(
            @NotBlank @Email String email,
            @NotBlank String password,
            @NotBlank String displayName) {}

    public record LoginRequest(
            @NotBlank @Email String email, @NotBlank String password) {}

    public record AuthResponse(UserResponse user, Instant expiresAt) {}

    public record UserResponse(UUID id, String email, String displayName, UserRole role) {}

    public record AuditResponse(
            UUID id,
            UUID sessionId,
            String accessJti,
            TokenAuditEventType type,
            String ipAddress,
            String userAgent,
            String detail,
            Instant createdAt) {}
}

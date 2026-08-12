package com.avento.dto.auth;

import com.avento.model.TokenAuditEventType;
import com.avento.model.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

    /** The identity data a signed-in person may safely change without rotating credentials. */
    public record UpdateProfileRequest(@NotBlank @Size(max = 120) String displayName) {}

    public record AuthResponse(UserResponse user, Instant expiresAt) {}

    public record UserResponse(UUID id, String email, String displayName, UserRole role, boolean hasAvatar) {}

    public record AvatarUploadResponse(boolean hasAvatar) {}

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

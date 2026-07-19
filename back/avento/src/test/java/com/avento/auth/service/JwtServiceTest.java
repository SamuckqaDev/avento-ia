package com.avento.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.avento.auth.config.AuthProperties;
import com.avento.auth.model.UserAccount;
import com.avento.auth.model.UserRole;
import io.jsonwebtoken.Claims;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    @Test
    void issuesAccessTokenWithSessionAndJtiClaims() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("test-secret-change-me-avento-local-auth-secret-32-bytes");
        properties.setAccessTokenTtl(Duration.ofMinutes(10));
        JwtService jwtService = new JwtService(properties);

        UserAccount user = new UserAccount();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        user.setId(userId);
        user.setEmail("dev@avento.local");
        user.setDisplayName("Avento Dev");
        user.setRole(UserRole.ADMIN);

        JwtService.IssuedToken issued = jwtService.issueAccessToken(user, sessionId);
        Claims claims = jwtService.parse(issued.token());

        assertEquals(userId.toString(), claims.getSubject());
        assertEquals(sessionId.toString(), claims.get("sid", String.class));
        assertEquals("dev@avento.local", claims.get("email", String.class));
        assertEquals("ADMIN", claims.get("role", String.class));
        assertEquals(issued.jti(), claims.getId());
        assertNotNull(issued.expiresAt());
    }
}

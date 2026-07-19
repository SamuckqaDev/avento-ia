package com.avento.auth.service;

import com.avento.auth.config.AuthProperties;
import com.avento.auth.model.UserAccount;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final AuthProperties properties;

    private final SecretKey key;

    public JwtService(AuthProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public IssuedToken issueAccessToken(UserAccount user, UUID sessionId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getAccessTokenTtl());
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .subject(user.getId().toString())
                .id(jti)
                .claim("sid", sessionId.toString())
                .claim("email", user.getEmail())
                .claim("name", user.getDisplayName())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, jti, expiresAt);
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public Claims parseAllowExpired(String token) {
        try {
            return parse(token);
        } catch (ExpiredJwtException exception) {
            return exception.getClaims();
        }
    }

    public record IssuedToken(String token, String jti, Instant expiresAt) {}
}

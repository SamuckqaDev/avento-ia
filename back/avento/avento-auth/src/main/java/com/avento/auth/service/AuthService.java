package com.avento.auth.service;

import com.avento.auth.config.AuthProperties;
import com.avento.auth.dto.AuthDtos.AuditResponse;
import com.avento.auth.dto.AuthDtos.AuthResponse;
import com.avento.auth.dto.AuthDtos.BootstrapRequest;
import com.avento.auth.dto.AuthDtos.LoginRequest;
import com.avento.auth.dto.AuthDtos.UserResponse;
import com.avento.auth.model.AuthSession;
import com.avento.auth.model.TokenAuditEvent;
import com.avento.auth.model.TokenAuditEventType;
import com.avento.auth.model.UserAccount;
import com.avento.auth.model.UserRole;
import com.avento.auth.repository.AuthSessionRepository;
import com.avento.auth.repository.TokenAuditEventRepository;
import com.avento.auth.repository.UserAccountRepository;
import com.avento.auth.security.AuthPrincipal;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AuthProperties properties;

    private final UserAccountRepository userRepository;

    private final AuthSessionRepository sessionRepository;

    private final TokenAuditEventRepository auditRepository;

    private final PasswordEncoder passwordEncoder;

    private final JwtService jwtService;

    public AuthService(
            AuthProperties properties,
            UserAccountRepository userRepository,
            AuthSessionRepository sessionRepository,
            TokenAuditEventRepository auditRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.auditRepository = auditRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResult bootstrap(BootstrapRequest request, HttpServletRequest servletRequest) {
        if (!properties.isBootstrapEnabled()) {
            throw new IllegalStateException("Bootstrap is disabled.");
        }
        if (userRepository.count() > 0) {
            throw new IllegalStateException("Bootstrap is available only before the first user exists.");
        }

        UserAccount user = new UserAccount();
        user.setEmail(normalizeEmail(request.email()));
        user.setDisplayName(cleanDisplayName(request.displayName(), user.getEmail()));
        user.setPasswordHash(passwordEncoder.encode(requirePassword(request.password())));
        user.setRole(UserRole.ADMIN);
        UserAccount saved = userRepository.save(user);
        audit(saved, null, null, TokenAuditEventType.BOOTSTRAP_USER, servletRequest, "First local admin created");
        return createSession(saved, servletRequest, TokenAuditEventType.LOGIN_SUCCESS);
    }

    @Transactional
    public AuthResult login(LoginRequest request, HttpServletRequest servletRequest) {
        String email = normalizeEmail(request.email());
        UserAccount user = userRepository
                .findByEmailIgnoreCase(email)
                .filter(UserAccount::isActive)
                .orElse(null);

        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            audit(null, null, null, TokenAuditEventType.LOGIN_FAILED, servletRequest, "Failed login for " + email);
            throw new BadCredentialsException("Invalid credentials.");
        }

        return createSession(user, servletRequest, TokenAuditEventType.LOGIN_SUCCESS);
    }

    @Transactional
    public AuthResult refresh(String accessToken, HttpServletRequest request) {
        Claims claims = jwtService.parseAllowExpired(requireToken(accessToken));
        UUID sessionId = UUID.fromString(claims.get("sid", String.class));
        String accessJti = claims.getId();

        AuthSession session = sessionRepository
                .findByIdAndRevokedFalse(sessionId)
                .orElseThrow(() -> new BadCredentialsException("Session is no longer active."));

        if (session.getExpiresAt().isBefore(Instant.now())) {
            revokeSession(session);
            audit(
                    session.getUser(),
                    session,
                    accessJti,
                    TokenAuditEventType.REFRESH_FAILED,
                    request,
                    "Session expired");
            throw new BadCredentialsException("Session expired.");
        }
        if (!session.getCurrentAccessJti().equals(accessJti)) {
            audit(
                    session.getUser(),
                    session,
                    accessJti,
                    TokenAuditEventType.REFRESH_FAILED,
                    request,
                    "Access token was already rotated");
            throw new BadCredentialsException("Access token was already rotated.");
        }
        if (!sameUserAgent(session, request)) {
            revokeSession(session);
            audit(
                    session.getUser(),
                    session,
                    accessJti,
                    TokenAuditEventType.REFRESH_FAILED,
                    request,
                    "User agent changed");
            throw new BadCredentialsException("Session validation failed.");
        }

        JwtService.IssuedToken issuedToken = jwtService.issueAccessToken(session.getUser(), session.getId());
        session.setCurrentAccessJti(issuedToken.jti());
        session.setRefreshTokenHash(hashSecret(randomSecret()));
        session.setLastUsedAt(Instant.now());
        sessionRepository.save(session);
        audit(
                session.getUser(),
                session,
                issuedToken.jti(),
                TokenAuditEventType.TOKEN_REFRESH,
                request,
                "Access token refreshed");
        return new AuthResult(
                issuedToken.token(), new AuthResponse(toUserResponse(session.getUser()), issuedToken.expiresAt()));
    }

    @Transactional
    public void logout(String accessToken, HttpServletRequest request) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }

        try {
            Claims claims = jwtService.parseAllowExpired(accessToken);
            UUID sessionId = UUID.fromString(claims.get("sid", String.class));
            sessionRepository.findByIdAndRevokedFalse(sessionId).ifPresent(session -> {
                revokeSession(session);
                audit(
                        session.getUser(),
                        session,
                        claims.getId(),
                        TokenAuditEventType.LOGOUT,
                        request,
                        "User logged out");
            });
        } catch (RuntimeException ignored) {
            audit(null, null, null, TokenAuditEventType.LOGOUT, request, "Logout with invalid access cookie");
        }
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(AuthPrincipal principal) {
        UserAccount user = userRepository
                .findById(principal.userId())
                .orElseThrow(() -> new BadCredentialsException("User not found."));
        return toUserResponse(user);
    }

    @Transactional(readOnly = true)
    public List<AuditResponse> auditHistory(AuthPrincipal principal) {
        return auditRepository.findTop100ByUserIdOrderByCreatedAtDesc(principal.userId()).stream()
                .map(this::toAuditResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AuthPrincipal authenticate(String token) {
        Claims claims = jwtService.parse(requireToken(token));
        UUID userId = UUID.fromString(claims.getSubject());
        UUID sessionId = UUID.fromString(claims.get("sid", String.class));
        String accessJti = claims.getId();

        AuthSession session = sessionRepository
                .findByIdAndRevokedFalse(sessionId)
                .orElseThrow(() -> new BadCredentialsException("Session is no longer active."));
        if (!session.getUser().getId().equals(userId)
                || !session.getCurrentAccessJti().equals(accessJti)) {
            throw new BadCredentialsException("Token no longer matches the active session.");
        }
        UserAccount user = session.getUser();
        if (!user.isActive()) {
            throw new BadCredentialsException("User is disabled.");
        }

        return new AuthPrincipal(
                user.getId(), session.getId(), accessJti, user.getEmail(), user.getDisplayName(), user.getRole());
    }

    private AuthResult createSession(UserAccount user, HttpServletRequest request, TokenAuditEventType eventType) {
        AuthSession session = new AuthSession();
        session.setUser(user);
        session.setCurrentAccessJti("pending");
        session.setRefreshTokenHash(hashSecret(randomSecret()));
        session.setUserAgentHash(hashNullable(userAgent(request)));
        session.setIpAddress(remoteAddress(request));
        session.setExpiresAt(Instant.now().plus(properties.getRefreshTokenTtl()));
        AuthSession savedSession = sessionRepository.save(session);

        JwtService.IssuedToken issuedToken = jwtService.issueAccessToken(user, savedSession.getId());
        savedSession.setCurrentAccessJti(issuedToken.jti());
        savedSession.setLastUsedAt(Instant.now());
        sessionRepository.save(savedSession);
        audit(user, savedSession, issuedToken.jti(), eventType, request, "Session created");
        return new AuthResult(issuedToken.token(), new AuthResponse(toUserResponse(user), issuedToken.expiresAt()));
    }

    private void revokeSession(AuthSession session) {
        session.setRevoked(true);
        session.setRevokedAt(Instant.now());
        sessionRepository.save(session);
    }

    private void audit(
            UserAccount user,
            AuthSession session,
            String accessJti,
            TokenAuditEventType type,
            HttpServletRequest request,
            String detail) {
        TokenAuditEvent event = new TokenAuditEvent();
        event.setUserId(user != null ? user.getId() : null);
        event.setSessionId(session != null ? session.getId() : null);
        event.setAccessJti(accessJti);
        event.setType(type);
        event.setIpAddress(remoteAddress(request));
        event.setUserAgent(userAgent(request));
        event.setDetail(detail);
        auditRepository.save(event);
    }

    private boolean sameUserAgent(AuthSession session, HttpServletRequest request) {
        String currentHash = hashNullable(userAgent(request));
        return session.getUserAgentHash() == null || session.getUserAgentHash().equals(currentHash);
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required.");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String requirePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must have at least 8 characters.");
        }
        return password;
    }

    private String requireToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BadCredentialsException("Missing access token.");
        }
        return token;
    }

    private String cleanDisplayName(String displayName, String email) {
        if (displayName == null || displayName.isBlank()) {
            return email;
        }
        return displayName.trim();
    }

    private String userAgent(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        return userAgent.length() > 512 ? userAgent.substring(0, 512) : userAgent;
    }

    private String remoteAddress(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String randomSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String hashNullable(String value) {
        return value == null ? null : hashSecret(value);
    }

    private String hashSecret(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private UserResponse toUserResponse(UserAccount user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole());
    }

    private AuditResponse toAuditResponse(TokenAuditEvent event) {
        return new AuditResponse(
                event.getId(),
                event.getSessionId(),
                event.getAccessJti(),
                event.getType(),
                event.getIpAddress(),
                event.getUserAgent(),
                event.getDetail(),
                event.getCreatedAt());
    }

    public record AuthResult(String token, AuthResponse response) {}
}

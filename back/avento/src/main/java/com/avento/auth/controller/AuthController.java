package com.avento.auth.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.BaseResponse;
import com.avento.api.dto.OperationResponse;
import com.avento.auth.config.AuthProperties;
import com.avento.auth.dto.AuthDtos.AuditResponse;
import com.avento.auth.dto.AuthDtos.AuthResponse;
import com.avento.auth.dto.AuthDtos.BootstrapRequest;
import com.avento.auth.dto.AuthDtos.LoginRequest;
import com.avento.auth.dto.AuthDtos.UserResponse;
import com.avento.auth.security.AuthPrincipal;
import com.avento.auth.service.AuthCookieService;
import com.avento.auth.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthProperties properties;

    private final AuthService authService;

    private final AuthCookieService cookieService;

    public AuthController(AuthProperties properties, AuthService authService, AuthCookieService cookieService) {
        this.properties = properties;
        this.authService = authService;
        this.cookieService = cookieService;
    }

    @PostMapping("/bootstrap")
    public ResponseEntity<BaseResponse<AuthResponse>> bootstrap(
            @Valid @RequestBody BootstrapRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse response) {
        AuthService.AuthResult result = authService.bootstrap(request, servletRequest);
        cookieService.writeAccessCookie(response, result.token());
        return ApiResponses.created(result.response());
    }

    @PostMapping("/login")
    public ResponseEntity<BaseResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest, HttpServletResponse response) {
        AuthService.AuthResult result = authService.login(request, servletRequest);
        cookieService.writeAccessCookie(response, result.token());
        return ApiResponses.ok(result.response());
    }

    @PostMapping("/refresh")
    public ResponseEntity<BaseResponse<AuthResponse>> refresh(
            HttpServletRequest servletRequest, HttpServletResponse response) {
        AuthService.AuthResult result =
                authService.refresh(readAccessCookie(servletRequest).orElse(null), servletRequest);
        cookieService.writeAccessCookie(response, result.token());
        return ApiResponses.ok(result.response());
    }

    @PostMapping("/logout")
    public ResponseEntity<BaseResponse<OperationResponse>> logout(
            HttpServletRequest servletRequest, HttpServletResponse response) {
        authService.logout(readAccessCookie(servletRequest).orElse(null), servletRequest);
        cookieService.clearAccessCookie(response);
        return ApiResponses.ok(new OperationResponse("Sessão encerrada."));
    }

    @GetMapping("/me")
    public ResponseEntity<BaseResponse<UserResponse>> me(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponses.ok(authService.currentUser(principal));
    }

    @GetMapping("/access-history")
    public ResponseEntity<BaseResponse<List<AuditResponse>>> accessHistory(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponses.ok(authService.auditHistory(principal));
    }

    @GetMapping("/token-history")
    public ResponseEntity<BaseResponse<List<AuditResponse>>> tokenHistory(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponses.ok(authService.auditHistory(principal));
    }

    private Optional<String> readAccessCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> properties.getCookie().getName().equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }
}

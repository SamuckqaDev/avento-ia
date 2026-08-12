package com.avento.controller;

import com.avento.config.AuthProperties;
import com.avento.dto.BaseResponse;
import com.avento.dto.OperationResponse;
import com.avento.dto.api.ApiResponses;
import com.avento.dto.auth.AuthDtos.AuditResponse;
import com.avento.dto.auth.AuthDtos.AvatarUploadResponse;
import com.avento.dto.auth.AuthDtos.AuthResponse;
import com.avento.dto.auth.AuthDtos.BootstrapRequest;
import com.avento.dto.auth.AuthDtos.LoginRequest;
import com.avento.dto.auth.AuthDtos.UpdateProfileRequest;
import com.avento.dto.auth.AuthDtos.UserResponse;
import com.avento.service.auth.AuthCookieService;
import com.avento.service.auth.AuthPrincipal;
import com.avento.service.auth.AuthService;
import com.avento.service.auth.AvatarService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthProperties properties;

    private final AuthService authService;

    private final AuthCookieService cookieService;

    private final AvatarService avatarService;

    public AuthController(
            AuthProperties properties, AuthService authService, AuthCookieService cookieService, AvatarService avatarService) {
        this.properties = properties;
        this.authService = authService;
        this.cookieService = cookieService;
        this.avatarService = avatarService;
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

    @PatchMapping("/me")
    public ResponseEntity<BaseResponse<UserResponse>> updateProfile(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponses.ok(authService.updateProfile(principal, request));
    }

    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BaseResponse<AvatarUploadResponse>> uploadAvatar(
            @AuthenticationPrincipal AuthPrincipal principal, @RequestPart("file") MultipartFile file) {
        avatarService.upload(principal.userId(), file);
        return ApiResponses.ok(new AvatarUploadResponse(true));
    }

    @GetMapping("/me/avatar")
    public ResponseEntity<byte[]> avatar(@AuthenticationPrincipal AuthPrincipal principal) {
        AvatarService.Avatar avatar = avatarService.avatar(principal.userId());
        return ResponseEntity.ok().contentType(avatar.mediaType()).body(avatar.bytes());
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

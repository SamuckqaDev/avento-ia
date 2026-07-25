package com.avento.auth.service;

import com.avento.auth.config.AuthProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class AuthCookieService {

    private final AuthProperties properties;

    public AuthCookieService(AuthProperties properties) {
        this.properties = properties;
    }

    public void writeAccessCookie(HttpServletResponse response, String token) {
        AuthProperties.Cookie cookie = properties.getCookie();
        ResponseCookie accessCookie = ResponseCookie.from(cookie.getName(), token)
                .httpOnly(cookie.isHttpOnly())
                .secure(cookie.isSecure())
                .sameSite(cookie.getSameSite())
                .path(cookie.getPath())
                .maxAge(properties.getRefreshTokenTtl())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());
    }

    public void clearAccessCookie(HttpServletResponse response) {
        AuthProperties.Cookie cookie = properties.getCookie();
        ResponseCookie accessCookie = ResponseCookie.from(cookie.getName(), "")
                .httpOnly(cookie.isHttpOnly())
                .secure(cookie.isSecure())
                .sameSite(cookie.getSameSite())
                .path(cookie.getPath())
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());
    }
}

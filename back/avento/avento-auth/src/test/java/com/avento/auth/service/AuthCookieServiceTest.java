package com.avento.auth.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.auth.config.AuthProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthCookieServiceTest {

    @Test
    void writesAccessCookieWithHttpOnlyAndSameSite() {
        AuthProperties properties = new AuthProperties();
        properties.getCookie().setHttpOnly(true);
        properties.getCookie().setSecure(false);
        properties.getCookie().setSameSite("Lax");
        properties.setAccessTokenTtl(Duration.ofMinutes(20));
        properties.setRefreshTokenTtl(Duration.ofDays(7));
        AuthCookieService cookieService = new AuthCookieService(properties);
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookieService.writeAccessCookie(response, "access-token");

        String setCookie = response.getHeader("Set-Cookie");
        assertTrue(setCookie.contains("avento_access=access-token"));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Lax"));
        assertTrue(setCookie.contains("Max-Age=604800"));
    }
}

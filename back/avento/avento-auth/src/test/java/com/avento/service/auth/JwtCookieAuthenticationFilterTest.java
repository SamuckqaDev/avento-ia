package com.avento.service.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.config.AuthProperties;
import org.junit.jupiter.api.Test;

class JwtCookieAuthenticationFilterTest {

    @Test
    void preservesTheAuthenticatedContextDuringAsyncStreamDispatch() {
        JwtCookieAuthenticationFilter filter = new JwtCookieAuthenticationFilter(
                new AuthProperties(), new AuthService(null, null, null, null, null, null));

        assertTrue(filter.shouldNotFilterAsyncDispatch());
    }
}

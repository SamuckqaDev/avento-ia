package com.avento.auth.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "avento.auth")
public class AuthProperties {

    private boolean enabled = true;

    private boolean bootstrapEnabled = true;

    private String jwtSecret = "dev-only-change-me-avento-local-auth-secret-32-bytes";

    private Duration accessTokenTtl = Duration.ofMinutes(20);

    private Duration refreshTokenTtl = Duration.ofDays(7);

    private Cookie cookie = new Cookie();

    private Root root = new Root();

    @Getter
    @Setter
    public static class Cookie {
        private String name = "avento_access";

        private String path = "/";

        private boolean httpOnly = true;

        private boolean secure = false;

        private String sameSite = "Lax";
    }

    @Getter
    @Setter
    public static class Root {
        private boolean enabled = true;

        private String email = "admin@avento.local";

        private String password;

        private String displayName = "Avento Root";
    }
}

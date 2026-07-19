package com.avento.auth.config;

import com.avento.auth.model.UserAccount;
import com.avento.auth.model.UserRole;
import com.avento.auth.repository.UserAccountRepository;
import java.time.Instant;
import java.util.Locale;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RootUserSeeder implements ApplicationRunner {

    private final AuthProperties properties;

    private final UserAccountRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    private final JdbcTemplate jdbcTemplate;

    public RootUserSeeder(
            AuthProperties properties,
            UserAccountRepository userRepository,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AuthProperties.Root root = properties.getRoot();
        if (!root.isEnabled()) {
            return;
        }

        String email = normalizeEmail(root.getEmail());
        String password = requirePassword(root.getPassword());
        ensureRootRoleIsAllowed();
        UserAccount user = userRepository.findByEmailIgnoreCase(email).orElseGet(UserAccount::new);

        user.setEmail(email);
        user.setDisplayName(cleanDisplayName(root.getDisplayName(), email));
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(UserRole.ROOT);
        user.setActive(true);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("Root email cannot be blank.");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String requirePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalStateException("Root password must have at least 8 characters.");
        }
        return password;
    }

    private String cleanDisplayName(String displayName, String email) {
        if (displayName == null || displayName.isBlank()) {
            return email;
        }
        return displayName.trim();
    }

    private void ensureRootRoleIsAllowed() {
        try {
            jdbcTemplate.execute("alter table avento_users drop constraint if exists avento_users_role_check");
            jdbcTemplate.execute(
                    "alter table avento_users add constraint avento_users_role_check check (role in ('ROOT','ADMIN','USER'))");
        } catch (DataAccessException ignored) {
            // Fresh embedded databases may not have this generated constraint yet.
        }
    }
}

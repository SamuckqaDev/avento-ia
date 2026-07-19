package com.avento.auth.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.auth.model.UserAccount;
import com.avento.auth.model.UserRole;
import com.avento.auth.repository.UserAccountRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class RootUserSeederTest {

    @Test
    void createsDefaultRootUserWhenEnabled() {
        AuthProperties properties = new AuthProperties();
        properties.getRoot().setPassword("test-root-password");
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        JdbcTemplate jdbcTemplate = new JdbcTemplate() {
            @Override
            public void execute(String sql) {}
        };
        RootUserSeeder seeder = new RootUserSeeder(properties, userRepository, passwordEncoder, jdbcTemplate);
        when(userRepository.findByEmailIgnoreCase("admin@avento.local")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-root-password");

        seeder.run(null);

        ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userRepository).save(userCaptor.capture());
        UserAccount user = userCaptor.getValue();
        assertEquals("admin@avento.local", user.getEmail());
        assertEquals("Avento Root", user.getDisplayName());
        assertEquals("hashed-root-password", user.getPasswordHash());
        assertEquals(UserRole.ROOT, user.getRole());
        assertTrue(user.isActive());
    }

    @Test
    void refusesToStartWithoutAnExplicitRootPassword() {
        AuthProperties properties = new AuthProperties();
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        JdbcTemplate jdbcTemplate = new JdbcTemplate() {
            @Override
            public void execute(String sql) {}
        };
        RootUserSeeder seeder = new RootUserSeeder(properties, userRepository, passwordEncoder, jdbcTemplate);

        assertThrows(IllegalStateException.class, () -> seeder.run(null));
    }
}

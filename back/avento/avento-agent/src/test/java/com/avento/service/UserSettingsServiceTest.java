package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.avento.api.dto.UserSettingsRequest;
import com.avento.model.UserSettings;
import com.avento.repository.UserSettingsRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class UserSettingsServiceTest {

    @Test
    void storesSettingsUnderTheAuthenticatedUsersKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForHash()).thenReturn(hashes);
        UUID userId = UUID.randomUUID();
        UserSettingsService service = new UserSettingsService(provider);

        service.update(userId, new UserSettingsRequest(true, false, null));

        String key = "avento:user:" + userId + ":settings";
        verify(hashes).put(key, "ttsEnabled", "true");
        verify(hashes).put(key, "thinkingEnabled", "false");
    }

    /**
     * O teste chamava "safe defaults" mas afirmava thinking ligado — e ligado não é o padrão seguro:
     * thinking e auto-aprovação são opt-in pelo menu. Como o Redis de desenvolvimento não persiste,
     * a chave some a cada restart e o padrão vira o estado real; com true, desligar no menu não
     * tinha efeito e o modelo seguia gastando o orçamento de tokens raciocinando.
     */
    @Test
    void optInSettingsDefaultToOffWhenRedisIsUnavailable() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        UserSettingsService service = new UserSettingsService(provider);

        assertThat(service.get(UUID.randomUUID()).ttsEnabled()).isFalse();
        assertThat(service.get(UUID.randomUUID()).thinkingEnabled()).isFalse();
        assertThat(service.get(UUID.randomUUID()).autoApproveAll()).isFalse();
    }

    /**
     * O Redis de desenvolvimento não persiste: a preferência precisa viver no banco, senão ligar
     * thinking some no próximo restart e o botão do menu parece não ter efeito.
     */
    @Test
    void persistsSettingsInTheDatabaseNotOnlyInTheCache() {
        UserSettingsRepository repository = mock(UserSettingsRepository.class);
        UUID userId = UUID.randomUUID();
        when(repository.findById(userId)).thenReturn(Optional.empty());
        UserSettingsService service = serviceWith(null, repository);

        service.update(userId, new UserSettingsRequest(null, true, null));

        ArgumentCaptor<UserSettings> saved = ArgumentCaptor.forClass(UserSettings.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().isThinkingEnabled()).isTrue();
    }

    // Sem cache, a leitura tem que cair no banco — e não no padrão.
    @Test
    void readsFromTheDatabaseWhenTheCacheIsEmpty() {
        UserSettingsRepository repository = mock(UserSettingsRepository.class);
        UUID userId = UUID.randomUUID();
        UserSettings stored = new UserSettings(userId);
        stored.setThinkingEnabled(true);
        when(repository.findById(userId)).thenReturn(Optional.of(stored));

        assertThat(serviceWith(null, repository).thinkingEnabled(userId, false)).isTrue();
    }

    // Um campo nulo no pedido significa "não mexi nisso": não pode zerar o que já estava gravado.
    @Test
    void leavesUntouchedFieldsAloneOnPartialUpdate() {
        UserSettingsRepository repository = mock(UserSettingsRepository.class);
        UUID userId = UUID.randomUUID();
        UserSettings stored = new UserSettings(userId);
        stored.setAutoApproveAll(true);
        when(repository.findById(userId)).thenReturn(Optional.of(stored));

        serviceWith(null, repository).update(userId, new UserSettingsRequest(null, true, null));

        ArgumentCaptor<UserSettings> saved = ArgumentCaptor.forClass(UserSettings.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isAutoApproveAll()).isTrue();
        assertThat(saved.getValue().isThinkingEnabled()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private UserSettingsService serviceWith(StringRedisTemplate redis, UserSettingsRepository repository) {
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        ObjectProvider<UserSettingsRepository> repositoryProvider = mock(ObjectProvider.class);
        when(repositoryProvider.getIfAvailable()).thenReturn(repository);
        return new UserSettingsService(redisProvider, repositoryProvider);
    }
}

package com.avento.service;

import com.avento.api.dto.UserSettingsRequest;
import com.avento.api.dto.UserSettingsResponse;
import com.avento.model.UserSettings;
import com.avento.repository.UserSettingsRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Preferências do usuário: Postgres é a verdade, Redis é cache na frente.
 *
 * <p>Antes existiam só no Redis, que no ambiente de desenvolvimento não persiste. A chave sumia a
 * cada restart, o valor efetivo virava o padrão do código, e ligar "thinking" ou a auto-aprovação
 * não sobrevivia ao próximo `dev-up.sh` — o botão do menu parecia não ter efeito nenhum.
 */
@Service
public class UserSettingsService {

    private static final Logger logger = LoggerFactory.getLogger(UserSettingsService.class);
    private static final String KEY_PREFIX = "avento:user:";
    private static final String KEY_SUFFIX = ":settings";
    private static final String TTS_FIELD = "ttsEnabled";
    private static final String THINKING_FIELD = "thinkingEnabled";
    private static final String AUTO_APPROVE_FIELD = "autoApproveAll";

    private final StringRedisTemplate redisTemplate;
    private final UserSettingsRepository repository;

    /** Conveniência para teste. Sem repositório, só o cache responde. */
    public UserSettingsService(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(redisTemplateProvider, null);
    }

    // @Autowired obrigatório: com dois construtores e nenhum marcado, o Spring não escolhe — procura
    // um sem argumentos, não acha, e a aplicação nem sobe.
    @Autowired
    public UserSettingsService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            ObjectProvider<UserSettingsRepository> repositoryProvider) {
        this.redisTemplate = redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable();
        this.repository = repositoryProvider == null ? null : repositoryProvider.getIfAvailable();
    }

    /**
     * Padrões de quem nunca escolheu. Todos false: thinking e auto-aprovação são OPT-IN pelo menu.
     * Com true aqui, a tela abria mostrando "ligado" sem que o usuário tivesse ligado nada.
     */
    public UserSettingsResponse get(UUID userId) {
        UUID owner = requireUser(userId);
        return new UserSettingsResponse(
                readFlag(owner, TTS_FIELD, false),
                readFlag(owner, THINKING_FIELD, false),
                readFlag(owner, AUTO_APPROVE_FIELD, false));
    }

    public UserSettingsResponse update(UUID userId, UserSettingsRequest request) {
        UUID owner = requireUser(userId);
        if (request == null) {
            return get(owner);
        }

        if (repository != null) {
            UserSettings stored = repository.findById(owner).orElseGet(() -> new UserSettings(owner));
            if (request.ttsEnabled() != null) stored.setTtsEnabled(request.ttsEnabled());
            if (request.thinkingEnabled() != null) stored.setThinkingEnabled(request.thinkingEnabled());
            if (request.autoApproveAll() != null) stored.setAutoApproveAll(request.autoApproveAll());
            repository.save(stored);
        }

        // Cache espelha o que foi gravado. Se o Redis falhar, a preferência não se perde — a
        // próxima leitura simplesmente vai ao banco.
        cachePut(owner, TTS_FIELD, request.ttsEnabled());
        cachePut(owner, THINKING_FIELD, request.thinkingEnabled());
        cachePut(owner, AUTO_APPROVE_FIELD, request.autoApproveAll());
        return get(owner);
    }

    public UserSettingsResponse restoreDefaults(UUID userId) {
        UUID owner = requireUser(userId);
        if (repository != null) {
            repository.deleteById(owner);
        }
        evict(owner);
        return get(owner);
    }

    public boolean thinkingEnabled(UUID userId, boolean fallback) {
        if (userId == null) {
            return fallback;
        }
        return readFlag(userId, THINKING_FIELD, fallback);
    }

    public boolean autoApproveAllEnabled(UUID userId, boolean fallback) {
        if (userId == null) {
            return fallback;
        }
        return readFlag(userId, AUTO_APPROVE_FIELD, fallback);
    }

    /** Cache → banco → padrão. O banco é consultado sempre que o cache não souber responder. */
    private boolean readFlag(UUID userId, String field, boolean fallback) {
        Boolean cached = cacheGet(userId, field);
        if (cached != null) {
            return cached;
        }
        Boolean stored = readFromDatabase(userId, field);
        if (stored != null) {
            cachePut(userId, field, stored);
            return stored;
        }
        return fallback;
    }

    private Boolean readFromDatabase(UUID userId, String field) {
        if (repository == null) {
            return null;
        }
        try {
            Optional<UserSettings> found = repository.findById(userId);
            return found.map(settings -> switch (field) {
                        case TTS_FIELD -> settings.isTtsEnabled();
                        case THINKING_FIELD -> settings.isThinkingEnabled();
                        case AUTO_APPROVE_FIELD -> settings.isAutoApproveAll();
                        default -> null;
                    })
                    .orElse(null);
        } catch (RuntimeException exception) {
            logger.warn("Falha ao ler a preferencia {} no banco", field, exception);
            return null;
        }
    }

    private Boolean cacheGet(UUID userId, String field) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            Object raw = redisTemplate.opsForHash().get(key(userId), field);
            return raw == null ? null : Boolean.parseBoolean(raw.toString());
        } catch (RuntimeException exception) {
            // Silenciar aqui escondia uma falha de seguranca: com o Redis fora do ar, autoApproveAll
            // caia no padrao e o usuario nunca sabia por que parou de ser perguntado.
            logger.warn("Falha ao ler a preferencia {} no cache; consultando o banco", field, exception);
            return null;
        }
    }

    private void cachePut(UUID userId, String field, Boolean value) {
        if (redisTemplate == null || value == null) {
            return;
        }
        try {
            redisTemplate.opsForHash().put(key(userId), field, value.toString());
        } catch (RuntimeException exception) {
            logger.warn("Falha ao gravar a preferencia {} no cache; o banco ja tem o valor", field, exception);
        }
    }

    private void evict(UUID userId) {
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.delete(key(userId));
        } catch (RuntimeException exception) {
            logger.warn("Falha ao limpar o cache de preferencias", exception);
        }
    }

    private String key(UUID userId) {
        return KEY_PREFIX + userId + KEY_SUFFIX;
    }

    private UUID requireUser(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("Usuario nao autenticado");
        }
        return userId;
    }
}

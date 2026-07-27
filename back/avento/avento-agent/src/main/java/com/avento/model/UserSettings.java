package com.avento.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Preferências do usuário, uma linha por usuário.
 *
 * <p>Existiam só no Redis, que no ambiente de desenvolvimento não persiste: a cada restart a chave
 * sumia e o valor efetivo virava o padrão do código. Na prática, ligar "thinking" ou a
 * auto-aprovação não sobrevivia ao próximo `dev-up.sh`, e o botão do menu parecia não fazer nada.
 * O Redis continua na frente como cache; a verdade fica aqui.
 */
@Entity
@Table(name = "user_settings")
public class UserSettings {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tts_enabled", nullable = false)
    private boolean ttsEnabled;

    @Column(name = "thinking_enabled", nullable = false)
    private boolean thinkingEnabled;

    @Column(name = "auto_approve_all", nullable = false)
    private boolean autoApproveAll;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected UserSettings() {}

    public UserSettings(UUID userId) {
        this.userId = userId;
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public UUID getUserId() {
        return userId;
    }

    public boolean isTtsEnabled() {
        return ttsEnabled;
    }

    public void setTtsEnabled(boolean ttsEnabled) {
        this.ttsEnabled = ttsEnabled;
    }

    public boolean isThinkingEnabled() {
        return thinkingEnabled;
    }

    public void setThinkingEnabled(boolean thinkingEnabled) {
        this.thinkingEnabled = thinkingEnabled;
    }

    public boolean isAutoApproveAll() {
        return autoApproveAll;
    }

    public void setAutoApproveAll(boolean autoApproveAll) {
        this.autoApproveAll = autoApproveAll;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

package com.avento.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Configuração de provedor por usuário, uma linha por usuário.
 *
 * <p>Vivia só no Redis, que não persiste: a chave de API sumia a cada restart e o usuário tinha de
 * reconfigurá-la para continuar usando a nuvem.
 *
 * <p>{@code cloudApiKeyEncrypted} guarda o valor cifrado (AES-GCM, ver {@code SecretCipher}) — nunca
 * o texto puro. Um dump do banco ou um log de query não pode entregar acesso pago à conta de quem
 * usa o sistema.
 */
@Entity
@Table(name = "provider_settings")
public class ProviderSettings {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "use_personal_cloud", nullable = false)
    private boolean usePersonalCloud;

    @Column(name = "cloud_provider")
    private String cloudProvider;

    @Column(name = "cloud_api_key_encrypted", columnDefinition = "TEXT")
    private String cloudApiKeyEncrypted;

    @Column(name = "cloud_model")
    private String cloudModel;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected ProviderSettings() {}

    public ProviderSettings(UUID userId) {
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

    public boolean isUsePersonalCloud() {
        return usePersonalCloud;
    }

    public void setUsePersonalCloud(boolean usePersonalCloud) {
        this.usePersonalCloud = usePersonalCloud;
    }

    public String getCloudProvider() {
        return cloudProvider;
    }

    public void setCloudProvider(String cloudProvider) {
        this.cloudProvider = cloudProvider;
    }

    public String getCloudApiKeyEncrypted() {
        return cloudApiKeyEncrypted;
    }

    public void setCloudApiKeyEncrypted(String cloudApiKeyEncrypted) {
        this.cloudApiKeyEncrypted = cloudApiKeyEncrypted;
    }

    public String getCloudModel() {
        return cloudModel;
    }

    public void setCloudModel(String cloudModel) {
        this.cloudModel = cloudModel;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

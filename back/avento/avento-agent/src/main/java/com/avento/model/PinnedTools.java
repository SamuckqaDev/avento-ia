package com.avento.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Ferramentas que o usuário fixou, uma linha por usuário.
 *
 * <p>A descoberta progressiva deixa o MODELO decidir o que enxergar: ele pesquisa com
 * {@code search_capabilities} e liga com {@code activate_tools}. Isso economiza contexto e funciona
 * — até o modelo simplesmente não chamar. Quando isso acontece, a ferramenta existe, está
 * conectada, e mesmo assim não é usada; de fora parece que "não funciona".
 *
 * <p>O que está fixado aqui não depende dessa decisão: entra no toolset de toda rodada. É a válvula
 * manual de quem sabe que vai precisar da ferramenta e não quer torcer para o modelo lembrar.
 *
 * <p>Guardado como uma lista separada por vírgula num único campo, e não numa tabela de ligação: o
 * dado é sempre lido e gravado inteiro, por um usuário só, e nunca é consultado por ferramenta.
 */
@Entity
@Table(name = "pinned_tools")
public class PinnedTools {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tool_names", columnDefinition = "TEXT")
    private String toolNames;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected PinnedTools() {}

    public PinnedTools(UUID userId) {
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

    public String getToolNames() {
        return toolNames;
    }

    public void setToolNames(String toolNames) {
        this.toolNames = toolNames;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

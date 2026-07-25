package com.avento.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Agente especializado (persona) que o usuário cria e gerencia — ex.: "Backend Java", "Testes",
 * "UI React". Cada agente tem instruções próprias, ferramentas permitidas e (opcional) modelo. O
 * plano executa UMA tarefa por vez, e cada tarefa roda com a persona do agente atribuído. Vários
 * agentes podem existir, mas a execução é sempre sequencial (um de cada vez) — trava de RAM local.
 */
@Entity
@Table(
        name = "agent_profiles",
        indexes = {@Index(name = "idx_agent_profiles_owner", columnList = "user_id")})
@Getter
@Setter
@NoArgsConstructor
public class AgentProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    // Descrição curta do que o agente faz — usada no roteamento automático de tarefas.
    @Column(columnDefinition = "TEXT")
    private String specialty;

    // Persona/instruções injetadas no prompt quando ESTE agente executa uma tarefa.
    @Column(name = "system_instructions", columnDefinition = "TEXT")
    private String systemInstructions;

    // CSV dos nomes de ferramenta permitidas (subset do registry). Vazio = todas as elegíveis.
    @Column(name = "allowed_tools", columnDefinition = "TEXT")
    private String allowedTools;

    // Palavras-chave (CSV) para o roteamento automático casar tarefa → agente.
    @Column(columnDefinition = "TEXT")
    private String triggers;

    // Modelo preferido; nulo = usa o default do sistema.
    private String model;

    // Agente generalista de fallback. Deve haver exatamente um default por usuário.
    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public AgentProfile(
            UUID userId,
            String name,
            String specialty,
            String systemInstructions,
            String allowedTools,
            String triggers,
            String model,
            boolean isDefault) {
        this.userId = userId;
        this.name = name;
        this.specialty = specialty;
        this.systemInstructions = systemInstructions;
        this.allowedTools = allowedTools;
        this.triggers = triggers;
        this.model = model;
        this.isDefault = isDefault;
    }

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}

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
 * Fato/preferência que o Avento carrega entre conversas — a memória "de longo prazo" que o histórico
 * de um único chat não cobre. O modelo tem pesos congelados, então "aprender" aqui é: guardar o fato
 * no banco e injetá-lo no prompt das próximas conversas. Modelo híbrido: memórias MANUAL entram já
 * ACTIVE; as SUGGESTED (a ferramenta remember, que o modelo chama) entram PENDING e só passam a
 * influenciar o prompt depois que o usuário confirma.
 */
@Entity
@Table(
        name = "user_memories",
        indexes = {
            @Index(name = "idx_user_memories_owner_status", columnList = "user_id,status"),
            @Index(name = "idx_user_memories_owner_updated", columnList = "user_id,updated_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class UserMemory {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String ORIGIN_MANUAL = "MANUAL";
    public static final String ORIGIN_SUGGESTED = "SUGGESTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Nullable only so Hibernate can evolve existing local databases without losing legacy rows.
    // Every new write requires an owner and every read is scoped by this column.
    @Column(name = "user_id")
    private UUID userId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    // Livre: "preferencia", "projeto", "fato", "referencia". Só rotula/agrupa, não muda comportamento.
    private String category;

    // Reservado para escopo por projeto no futuro; nulo = memória global. Hoje todas são injetadas.
    private String scope;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private String origin;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public UserMemory(UUID userId, String content, String category, String scope, String status, String origin) {
        this.userId = userId;
        this.content = content;
        this.category = category;
        this.scope = scope;
        this.status = status;
        this.origin = origin;
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

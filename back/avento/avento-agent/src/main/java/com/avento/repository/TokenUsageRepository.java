package com.avento.repository;

import com.avento.model.TokenUsage;
import com.avento.service.dto.ChatUsage;
import com.avento.service.dto.DayTotal;
import com.avento.service.dto.ModelUsage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TokenUsageRepository extends JpaRepository<TokenUsage, Long> {

    @Query("select coalesce(sum(t.totalTokens), 0) from TokenUsage t "
            + "where t.userId = :userId and t.createdAt >= :since")
    long sumTotalSince(UUID userId, LocalDateTime since);

    @Query("select coalesce(sum(t.promptTokens), 0) from TokenUsage t "
            + "where t.userId = :userId and t.createdAt >= :since")
    long sumPromptSince(UUID userId, LocalDateTime since);

    @Query("select coalesce(sum(t.completionTokens), 0) from TokenUsage t "
            + "where t.userId = :userId and t.createdAt >= :since")
    long sumCompletionSince(UUID userId, LocalDateTime since);

    @Query("select count(t) from TokenUsage t where t.userId = :userId and t.createdAt >= :since")
    long countSince(UUID userId, LocalDateTime since);

    @Query("select t.model as model, sum(t.promptTokens) as promptTokens, "
            + "sum(t.completionTokens) as completionTokens, sum(t.totalTokens) as total "
            + "from TokenUsage t where t.userId = :userId and t.createdAt >= :since "
            + "group by t.model order by total desc")
    List<ModelUsage> usageByModelSince(UUID userId, LocalDateTime since);

    @Query("select cast(t.createdAt as date) as day, sum(t.totalTokens) as total from TokenUsage t "
            + "where t.userId = :userId and t.createdAt >= :since group by cast(t.createdAt as date) order by day")
    List<DayTotal> sumByDaySince(UUID userId, LocalDateTime since);

    @Query("select c.id as chatId, c.title as title, sum(t.totalTokens) as total "
            + "from TokenUsage t, Chat c where t.chatId = c.id and t.userId = :userId and t.createdAt >= :since "
            + "group by c.id, c.title order by total desc")
    List<ChatUsage> usageByChatSince(UUID userId, LocalDateTime since, Pageable pageable);

    long deleteByChatIdAndUserId(Long chatId, UUID userId);
}

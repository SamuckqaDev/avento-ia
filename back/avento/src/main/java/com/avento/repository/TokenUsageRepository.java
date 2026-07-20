package com.avento.repository;

import com.avento.model.TokenUsage;
import com.avento.service.dto.DayTotal;
import com.avento.service.dto.ModelTotal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TokenUsageRepository extends JpaRepository<TokenUsage, Long> {

    @Query("select coalesce(sum(t.totalTokens), 0) from TokenUsage t "
            + "where t.userId = :userId and t.createdAt >= :since")
    long sumTotalSince(UUID userId, LocalDateTime since);

    @Query("select t.model as model, sum(t.totalTokens) as total from TokenUsage t "
            + "where t.userId = :userId and t.createdAt >= :since group by t.model order by total desc")
    List<ModelTotal> sumByModelSince(UUID userId, LocalDateTime since);

    @Query("select cast(t.createdAt as date) as day, sum(t.totalTokens) as total from TokenUsage t "
            + "where t.userId = :userId and t.createdAt >= :since group by cast(t.createdAt as date) order by day")
    List<DayTotal> sumByDaySince(UUID userId, LocalDateTime since);
}

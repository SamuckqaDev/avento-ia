package com.avento.repository;

import com.avento.model.ExecutionOutboxEvent;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionOutboxEventRepository extends JpaRepository<ExecutionOutboxEvent, Long> {

    List<ExecutionOutboxEvent> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();

    long deleteByPublishedAtBefore(LocalDateTime threshold);

    long deleteByAggregateIdIn(List<String> aggregateIds);
}

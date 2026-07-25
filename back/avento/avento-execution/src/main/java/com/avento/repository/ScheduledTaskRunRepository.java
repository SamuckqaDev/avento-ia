package com.avento.repository;

import com.avento.model.ScheduledTaskRun;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledTaskRunRepository extends JpaRepository<ScheduledTaskRun, Long> {
    List<ScheduledTaskRun> findTop50ByTaskIdOrderByCreatedAtDesc(Long taskId);
}

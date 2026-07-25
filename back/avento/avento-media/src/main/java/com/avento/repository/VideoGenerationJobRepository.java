package com.avento.repository;

import com.avento.model.VideoGenerationJob;
import com.avento.model.VideoGenerationJob.Status;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoGenerationJobRepository extends JpaRepository<VideoGenerationJob, UUID> {
    Optional<VideoGenerationJob> findByIdAndUserId(UUID id, UUID userId);

    List<VideoGenerationJob> findByChatIdAndUserId(Long chatId, UUID userId);

    List<VideoGenerationJob> findByStatusIn(Collection<Status> statuses);
}

package com.avento.repository;

import com.avento.model.ImageGenerationJob;
import com.avento.model.ImageGenerationJob.Status;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageGenerationJobRepository extends JpaRepository<ImageGenerationJob, UUID> {
    Optional<ImageGenerationJob> findByIdAndUserId(UUID id, UUID userId);

    List<ImageGenerationJob> findByChatIdAndUserId(Long chatId, UUID userId);

    List<ImageGenerationJob> findByStatusIn(Collection<Status> statuses);
}

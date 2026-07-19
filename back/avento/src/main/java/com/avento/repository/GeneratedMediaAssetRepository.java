package com.avento.repository;

import com.avento.model.GeneratedMediaAsset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeneratedMediaAssetRepository extends JpaRepository<GeneratedMediaAsset, Long> {
    List<GeneratedMediaAsset> findByChatIdAndUserIdOrderByCreatedAtDesc(Long chatId, UUID userId);

    Optional<GeneratedMediaAsset> findFirstByChatIdAndUserIdAndMediaTypeOrderByIdDesc(
            Long chatId, UUID userId, String mediaType);

    Optional<GeneratedMediaAsset> findByFilename(String filename);
}

package com.avento.repository;

import com.avento.model.PinnedTools;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PinnedToolsRepository extends JpaRepository<PinnedTools, UUID> {}

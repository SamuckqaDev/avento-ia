package com.avento.model;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PinnedToolsRepository extends JpaRepository<PinnedTools, UUID> {}

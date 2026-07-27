package com.avento.repository;

import com.avento.model.ProviderSettings;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderSettingsRepository extends JpaRepository<ProviderSettings, UUID> {}

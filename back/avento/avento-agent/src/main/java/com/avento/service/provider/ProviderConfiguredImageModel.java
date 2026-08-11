package com.avento.service.provider;

import com.avento.model.ProviderSettings;
import com.avento.model.ProviderSettingsRepository;
import com.avento.service.image.ConfiguredImageModel;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Serves the image model stored in the provider settings to the media module.
 *
 * <p>Not per user: generation runs on background workers that carry no account, and Avento runs as
 * a local single-user product. With more than one row it yields, leaving the YAML default in charge
 * rather than guessing an owner.
 */
@Component
public class ProviderConfiguredImageModel implements ConfiguredImageModel {

    private final ProviderSettingsRepository repository;

    public ProviderConfiguredImageModel(ObjectProvider<ProviderSettingsRepository> repositoryProvider) {
        this.repository = repositoryProvider.getIfAvailable();
    }

    @Override
    public Optional<String> preferred() {
        if (repository == null) {
            return Optional.empty();
        }
        List<ProviderSettings> all = repository.findAll();
        if (all.size() != 1) {
            return Optional.empty();
        }
        String model = all.get(0).getImageModel();
        return model == null || model.isBlank() ? Optional.empty() : Optional.of(model);
    }
}

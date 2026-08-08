package com.avento.service.image;

import java.util.Optional;

/**
 * Where image generation learns which model the user picked in the settings.
 *
 * <p>Same inversion as the RAG module's embedding source: the choice is stored by the agent module,
 * which already depends on this one, so the dependency cannot point back. With no implementation
 * present the YAML default stays in charge.
 */
public interface ConfiguredImageModel {

    Optional<String> preferred();
}

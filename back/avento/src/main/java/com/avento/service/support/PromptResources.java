package com.avento.service.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;

/**
 * Carrega prompts/textos longos de arquivos de recurso (ex.: {@code agent/prompts/*.md}) em vez de
 * chumbá-los no código. Falha rápido no boot se um recurso obrigatório não existir.
 */
public final class PromptResources {

    private PromptResources() {}

    public static String load(String classpathResource) {
        ClassPathResource resource = new ClassPathResource(classpathResource);
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível carregar o prompt: " + classpathResource, exception);
        }
    }
}

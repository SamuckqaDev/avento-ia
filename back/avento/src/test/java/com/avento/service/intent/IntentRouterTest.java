package com.avento.service.intent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.service.tools.ToolCapabilityRegistry;
import org.junit.jupiter.api.Test;

class IntentRouterTest {

    // embeddingModel nulo faz o classificador cair de volta para vazio sempre,
    // deixando o teste deterministico e sem chamada de rede ao Ollama.
    private final IntentRouter router =
            new IntentRouter(new ToolCapabilityRegistry(), new IntentEmbeddingClassifier(null, 0.55, 2000));

    @Test
    void exposesProjectToolsForProjectAnalysisIntent() {
        assertTrue(router.shouldExposeTool("directory_tree", "Analisa esse projeto para mim."));
        assertTrue(router.shouldExposeTool("read_file", "Analisa esse projeto para mim."));
        assertFalse(router.shouldExposeTool("open_app", "Analisa esse projeto para mim."));
    }

    @Test
    void exposesTerminalToolsForValidationIntent() {
        assertTrue(router.shouldExposeTool("terminal_run", "Roda os testes Maven."));
        assertTrue(router.shouldExposeTool("terminal_logs", "Roda os testes Maven."));
        assertFalse(router.shouldExposeTool("run_shortcut", "Roda os testes Maven."));
    }

    @Test
    void exposesBrowserMcpOnlyForWebIntent() {
        assertTrue(router.shouldExposeTool("browser_navigate", "Pesquisa sobre Spring AI."));
        assertFalse(router.shouldExposeTool("browser_navigate", "Analisa esse projeto."));
    }

    @Test
    void exposesAutomationToolsForAppIntent() {
        assertTrue(router.shouldExposeTool("close_browser_tab", "Fecha a aba da pesquisa no Brave."));
        assertTrue(router.shouldExposeTool("close_app", "Fecha o Brave."));
        assertFalse(router.shouldExposeTool("write_file", "Fecha o Brave."));
    }

    @Test
    void exposesImageToolForImageGenerationIntent() {
        assertTrue(router.shouldExposeTool("generate_image", "Cria uma imagem moderna do Avento."));
        assertFalse(router.shouldExposeTool("open_app", "Cria uma imagem moderna do Avento."));
    }
}

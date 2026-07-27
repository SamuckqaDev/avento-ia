package com.avento.service.intent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.service.tools.ToolCapabilityRegistry;
import org.junit.jupiter.api.Test;

class IntentRouterTest {

    // embeddingModel nulo faz o classificador cair de volta para vazio sempre,
    // deixando o teste deterministico e sem chamada de rede ao Ollama.
    private final IntentRouter router =
            new IntentRouter(new ToolCapabilityRegistry(), new IntentEmbeddingClassifier(null, 0.55, 2000), true);

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

    // generate_pdf ficava preso à intenção IMAGE: "faz um pdf" não expunha a ferramenta, o modelo
    // escrevia a chamada como texto e ela era descartada — o usuário via a promessa sem a ação.
    @Test
    void exposesPdfToolForDocumentIntent() {
        assertTrue(router.shouldExposeTool("generate_pdf", "Faz um pdf com esses dados pra eu baixar."));
        assertTrue(router.shouldExposeTool("generate_pdf", "Monta uma tabela comparativa e exporta."));
    }

    // O auto-connect ligava o servidor fetch para "cotação do dólar", mas as palavras-chave de
    // intenção não conheciam esses termos e escondiam a ferramenta na mesma rodada.
    @Test
    void exposesWebReaderForRealTimeDataIntent() {
        assertTrue(router.shouldExposeTool("fetch", "Qual a cotacao do dolar hoje?"));
        assertTrue(router.shouldExposeTool("fetch", "Me diz o preco do bitcoin agora."));
        assertFalse(router.shouldExposeTool("fetch", "Corrige esse bug no arquivo."));
    }

    /**
     * Casos reais em que a rodada ficou SEM a ferramenta de web e o modelo respondeu prometendo
     * pesquisar. O primeiro é um typo — o casamento é por substring, então o radical "pesquis" cobre
     * "pesquisr" e todas as flexões, enquanto as formas completas deixavam passar.
     */
    @Test
    void exposesWebReaderDespiteTypoOrInflection() {
        assertTrue(router.shouldExposeTool("fetch", "Pode pesquisr sobre"));
        assertTrue(router.shouldExposeTool("fetch", "Estou pesquisando placas de video"));
        assertTrue(router.shouldExposeTool("fetch", "me traz o comparativo de precos"));
        assertTrue(router.shouldExposeTool("fetch", "quanto custa uma RTX 4090"));
        assertTrue(router.shouldExposeTool("fetch", "quero a ficha tecnica dela"));
    }

    // O radical nao pode ser curto a ponto de virar falso positivo: "preciso" nao e pedido de web.
    @Test
    void doesNotTreatCommonWordsAsWebRequests() {
        assertFalse(router.shouldExposeTool("fetch", "preciso corrigir esse metodo"));
        assertFalse(router.shouldExposeTool("fetch", "precisa de um teste aqui"));
    }

    // Pedido composto imagem+pdf mantém as duas ferramentas na mesa.
    @Test
    void keepsPdfGenerationAvailableForImageIntentFallback() {
        assertTrue(router.shouldExposeTool("generate_pdf", "Gera uma imagem e salva num pdf."));
    }
}

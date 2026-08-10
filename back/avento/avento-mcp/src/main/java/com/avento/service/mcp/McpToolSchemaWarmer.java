package com.avento.service.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Aprende, uma vez, quais ferramentas as imagens MCP oferecem — para a tela de criação de agente não
 * nascer vazia numa máquina nova.
 *
 * <p><b>O problema que resolve:</b> o cache de schemas só aprendia na primeira conexão bem-sucedida
 * de cada servidor. Isso significa que, num ambiente recém-instalado, quem abre a tela para montar um
 * agente vê apenas as ferramentas locais; as de container só aparecem depois que alguém, por acaso,
 * usar aquele servidor numa conversa. Escolher ferramenta não pode depender de sorte.
 *
 * <p><b>Fora do caminho da requisição.</b> Roda em uma thread própria, de prioridade mínima, disparada
 * depois que a aplicação já está pronta. Subir quatro containers custa alguns segundos, e esse custo
 * não pode aparecer como demora para quem abriu o app — é o mesmo raciocínio do
 * {@code WorkspaceIndexingService}, que aquece o índice vetorial sem segurar a resposta.
 *
 * <p><b>Só na primeira vez.</b> O aquecimento pula toda imagem que o cache já conhece, então do
 * segundo boot em diante ele não faz nada. A chave é o digest: imagem atualizada volta a ser
 * desconhecida e reaquece sozinha, sem ninguém precisar limpar cache.
 *
 * <p><b>Desligável.</b> {@code avento.mcp.schema-warmup.enabled=false} para quem prefere não gastar
 * containers na subida — numa máquina apertada isso é uma escolha legítima.
 */
@Component
public class McpToolSchemaWarmer {

    private static final Logger logger = LoggerFactory.getLogger(McpToolSchemaWarmer.class);

    private final McpServerCatalogService catalogService;
    private final boolean enabled;

    public McpToolSchemaWarmer(
            McpServerCatalogService catalogService,
            @Value("${avento.mcp.schema-warmup.enabled:true}") boolean enabled) {
        this.catalogService = catalogService;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmInBackground() {
        if (!enabled) {
            return;
        }
        Thread thread = new Thread(this::warm, "mcp-schema-warmup");
        thread.setDaemon(true);
        // Prioridade mínima: aquecer é conveniência. Se a máquina estiver ocupada respondendo a
        // alguém, quem espera é o aquecimento.
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private void warm() {
        try {
            long startedAt = System.nanoTime();
            int learned = catalogService.warmSchemaCache();
            if (learned > 0) {
                logger.info(
                        "Cache de schemas MCP aquecido: {} imagens em {} ms",
                        learned,
                        (System.nanoTime() - startedAt) / 1_000_000);
            }
        } catch (Exception exception) {
            // Aquecimento nunca derruba nada: sem ele o cache aprende na primeira conexao real, que
            // era o comportamento antes desta classe existir.
            logger.debug("Aquecimento do cache de schemas MCP nao concluiu: {}", exception.getMessage());
        }
    }
}

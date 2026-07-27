package com.avento.service.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Flux;

/**
 * Transporte de modelo: leva a requisição do agente até um provedor e traz a resposta de volta no
 * formato interno.
 *
 * <p>Quem tem as ferramentas é o AGENTE, não o modelo. O laço de rodadas monta o toolset, pede
 * aprovação, prende ao sandbox, executa e devolve o resultado — isso não muda de provedor para
 * provedor. O que muda é só o dialeto da chamada.
 *
 * <p>A primeira versão errou nisso: o caminho de nuvem desviava do laço inteiro, então o Gemini
 * ficava sem ferramenta, sem RAG e sem memória. O modelo é processamento; a estrutura tem de
 * entregar as capacidades a ele.
 *
 * <p><b>Contrato.</b> A entrada é a requisição canônica do Avento (o mesmo objeto que sempre foi
 * montado para o Ollama: {@code model}, {@code messages}, {@code tools}, {@code options}). A saída
 * são linhas JSON no formato que o agente já consome:
 *
 * <pre>{@code {"message": {"content": "...", "tool_calls": [...], "thinking": "..."}, "done": false}}</pre>
 *
 * <p>Traduzir nos dois sentidos é responsabilidade da implementação, e é o que permite o resto do
 * agente não saber qual provedor está atendendo.
 */
public interface ModelTransport {

    /** Tipo de provedor que este transporte atende. */
    ProviderKind kind();

    /**
     * @param canonicalRequest requisição no formato interno (model/messages/tools/options)
     * @param baseUrl endereço do provedor
     * @param apiKey chave, quando o provedor exigir; nunca vai para log nem para URL
     */
    Flux<String> stream(ObjectNode canonicalRequest, String baseUrl, String apiKey);
}

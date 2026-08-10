package com.avento.service.rag;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.stereotype.Service;

/**
 * A busca do Avento falando o contrato de recuperação do Spring AI.
 *
 * <p><b>Por que existe:</b> a recuperação já era boa — vetorial com recuo literal, limiar medido,
 * índice por perfil de embedding — mas só era alcançável por classes do próprio Avento. O
 * {@link DocumentRetriever} é a interface que o ecossistema inteiro consome: advisors de RAG,
 * junções de documentos, e qualquer outro agente. Falar esse contrato não custa comportamento e
 * abre a recuperação para tudo isso.
 *
 * <p><b>O que NÃO mudou, de propósito:</b> a decisão continua sendo do {@link CodeSearchService} —
 * vetorial primeiro, literal quando o índice ainda não está pronto ou quando o vetorial vem vazio.
 * Trocar isso pelo {@code VectorStoreDocumentRetriever} padrão perderia o recuo literal, e é ele que
 * faz a ferramenta responder já na primeira mensagem de um projeto, antes de qualquer indexação.
 *
 * <h2>A estratégia viaja nos metadados</h2>
 *
 * O {@code ConcatenationDocumentJoiner} do Spring AI junta resultados de vários recuperadores e
 * descarta de qual deles vieram. Aqui isso importa: sem saber se a resposta é vetorial ou literal, o
 * modelo não distingue "não existe" de "não bate literalmente" — e já respondeu com confiança errada
 * por causa dessa lacuna. Por isso cada documento carrega {@code avento.search.strategy}.
 *
 * <h2>Como o caminho é escolhido</h2>
 *
 * O {@link Query} traz só texto; o diretório vem em {@code context} sob {@link #CONTEXT_PATH}. Sem
 * ele não há o que buscar — o índice é por raiz de projeto, e uma busca sem raiz varreria tudo.
 */
@Service
public class WorkspaceDocumentRetriever implements DocumentRetriever {

    /** Chave em {@link Query#context()} com o diretório a pesquisar. Obrigatória. */
    public static final String CONTEXT_PATH = "avento.search.path";

    /** Chave em {@link Query#context()} com o teto de resultados. Opcional. */
    public static final String CONTEXT_MAX_RESULTS = "avento.search.maxResults";

    /** Metadado que diz qual caminho respondeu: {@code VECTOR} ou {@code LITERAL}. */
    public static final String METADATA_STRATEGY = "avento.search.strategy";

    private static final int DEFAULT_MAX_RESULTS = 8;

    private final CodeSearchService codeSearchService;

    public WorkspaceDocumentRetriever(CodeSearchService codeSearchService) {
        this.codeSearchService = codeSearchService;
    }

    // A checagem de texto em branco e cinto e suspensorio: o proprio Query.Builder do Spring AI ja
    // recusa texto vazio ("text cannot be null or empty"). Ela cobre quem construir o Query por outro
    // caminho; o caso que de fato chega aqui e o argumento nulo.
    @Override
    public List<Document> retrieve(Query query) {
        if (query == null || query.text() == null || query.text().isBlank()) {
            return List.of();
        }
        Path directory = directoryOf(query);
        if (directory == null) {
            return List.of();
        }

        CodeSearchService.Result result = codeSearchService.search(directory, query.text(), maxResultsOf(query));
        return result.hits().stream()
                .map(hit -> toDocument(hit, result.strategy()))
                .toList();
    }

    private Path directoryOf(Query query) {
        Object raw = query.context() == null ? null : query.context().get(CONTEXT_PATH);
        if (raw == null || raw.toString().isBlank()) {
            return null;
        }
        return Paths.get(raw.toString()).toAbsolutePath().normalize();
    }

    private int maxResultsOf(Query query) {
        Object raw = query.context() == null ? null : query.context().get(CONTEXT_MAX_RESULTS);
        if (raw instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        return DEFAULT_MAX_RESULTS;
    }

    /**
     * As linhas entram nos metadados, não no texto.
     *
     * <p>O texto é o que vai para o modelo; número de linha embutido nele vira ruído que o modelo
     * às vezes copia para dentro do código que escreve. Como metadado, quem quiser citar a origem
     * ainda consegue.
     */
    private Document toDocument(CodeSearchService.Hit hit, CodeSearchService.Strategy strategy) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", hit.filePath());
        metadata.put("startLine", hit.startLine());
        metadata.put("endLine", hit.endLine());
        metadata.put("score", hit.score());
        metadata.put(METADATA_STRATEGY, strategy.name());
        return new Document(hit.snippet() == null ? "" : hit.snippet(), metadata);
    }
}

package com.avento.service.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Corta codigo nas fronteiras que a linguagem reconhece, em vez de a cada N tokens.
 *
 * <p>O corte por tamanho fixo nao olha o conteudo, e em codigo isso quase nunca cai num lugar util.
 * Medido neste repositorio: das cinco fronteiras do {@code WorkspaceAccessService.java}, QUATRO caem
 * dentro de um metodo; no {@code RagService.java}, cinco de cinco. Um chunk que abre com um
 * {@code return} solto e fecha num {@code catch} sem {@code try} nao representa nada — e o vetor
 * gerado a partir dele tambem nao.
 *
 * <p>O efeito aparece na busca: procurando pelo nome exato de um metodo deste repo, o trecho certo
 * pontuou 0,489. O metodo tem 38 linhas e NAO CABE INTEIRO em nenhum chunk de 500 tokens — esta
 * partido entre dois, e nenhum dos dois e "o metodo". Baixar o limiar mascara isso; cortar direito
 * resolve.
 *
 * <p>Nao e um parser de verdade: contar chaves e reconhecer linha de declaracao cobre Java, TypeScript
 * e JavaScript, que e o que este projeto indexa. Linguagem que nao usa chaves cai no corte por
 * tamanho, que continua valendo como rede.
 */
public final class CodeAwareSplitter {

    private static final Set<String> BRACE_LANGUAGES = Set.of("java", "ts", "tsx", "js", "jsx");

    /** Acima disto um unico membro vira grande demais para um chunk e e cortado por tamanho. */
    private final int maxChunkChars;

    /** Abaixo disto o trecho e colado no anterior: chunk minusculo dilui o indice sem informar nada. */
    private final int minChunkChars;

    public CodeAwareSplitter(int maxChunkChars, int minChunkChars) {
        this.maxChunkChars = Math.max(500, maxChunkChars);
        this.minChunkChars = Math.max(0, minChunkChars);
    }

    /** Verdadeiro quando vale a pena cortar por estrutura em vez de por tamanho. */
    public static boolean handles(String relativePath) {
        String lower = relativePath == null ? "" : relativePath.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot >= 0 && BRACE_LANGUAGES.contains(lower.substring(dot + 1));
    }

    /**
     * Trechos do arquivo: UM MEMBRO POR CHUNK.
     *
     * <p>A primeira versao acumulava membros ate encostar no teto, para "preservar o contexto de quem
     * chama quem". Medido, era pior: com acumulo o recall@5 ficou em 5/8, sem acumulo foi a 6/8. O
     * caso que expos a causa foi o {@code deleteChunks}, um metodo de tres linhas — acumulado, caiu
     * da posicao 2 para a 73; sozinho, voltou para a 1. Um chunk com seis metodos sem relacao entre
     * si tem um vetor que e a media de seis assuntos, e media nao responde pergunta nenhuma.
     *
     * <p>Membro minusculo ainda cola no anterior: chunk de tres linhas nao informa e so polui o
     * indice. E membro gigante e cortado por tamanho, porque acima de certo ponto ele estoura a
     * janela do proprio modelo de embedding.
     */
    public List<String> split(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        for (String member : membersOf(content)) {
            if (member.isBlank()) {
                continue;
            }
            if (!chunks.isEmpty() && member.strip().length() < minChunkChars) {
                chunks.set(chunks.size() - 1, chunks.get(chunks.size() - 1) + member);
            } else if (member.length() > maxChunkChars) {
                chunks.addAll(bySize(member));
            } else {
                chunks.add(member);
            }
        }
        return chunks;
    }

    /** Ultimo recurso para um membro que sozinho nao cabe na janela do modelo de embedding. */
    private List<String> bySize(String member) {
        List<String> parts = new ArrayList<>();
        for (int start = 0; start < member.length(); start += maxChunkChars) {
            parts.add(member.substring(start, Math.min(start + maxChunkChars, member.length())));
        }
        return parts;
    }

    /**
     * Quebra o arquivo onde a profundidade de chaves volta ao nivel de membro.
     *
     * <p>Profundidade 0 e o topo do arquivo (imports, declaracao da classe); 1 e dentro da classe,
     * onde os metodos vivem. Fechar uma chave e voltar a 1 significa "acabou um metodo" — e esse e o
     * ponto de corte. Comentario e texto entre aspas sao ignorados na contagem, senao uma chave
     * dentro de uma string desalinha tudo dali para a frente.
     */
    private List<String> membersOf(String content) {
        List<String> members = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        char stringDelimiter = 0;

        for (int index = 0; index < content.length(); index++) {
            char c = content.charAt(index);
            char next = index + 1 < content.length() ? content.charAt(index + 1) : 0;
            current.append(c);

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                }
                continue;
            }
            if (stringDelimiter != 0) {
                if (c == '\\') {
                    if (index + 1 < content.length()) {
                        current.append(next);
                        index++;
                    }
                } else if (c == stringDelimiter) {
                    stringDelimiter = 0;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                inLineComment = true;
            } else if (c == '/' && next == '*') {
                inBlockComment = true;
            } else if (c == '"' || c == '\'' || c == '`') {
                stringDelimiter = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                // Voltou ao nivel da classe: fechou um membro. Leva junto o resto da linha.
                if (depth <= 1) {
                    while (index + 1 < content.length() && content.charAt(index + 1) != '\n') {
                        current.append(content.charAt(++index));
                    }
                    if (index + 1 < content.length()) {
                        current.append('\n');
                        index++;
                    }
                    members.add(current.toString());
                    current = new StringBuilder();
                }
            }
        }
        if (current.length() > 0) {
            members.add(current.toString());
        }
        return members;
    }
}

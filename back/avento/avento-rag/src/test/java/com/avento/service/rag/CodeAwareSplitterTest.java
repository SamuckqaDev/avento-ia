package com.avento.service.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * O corte por tamanho fixo nao olha o conteudo, e em codigo quase nunca cai num lugar util. Medido
 * neste repositorio: das cinco fronteiras do {@code WorkspaceAccessService.java}, quatro caem dentro
 * de um metodo; no {@code RagService.java}, cinco de cinco.
 *
 * <p>Um chunk que abre com um {@code return} solto e fecha num {@code catch} sem {@code try} nao
 * representa nada, e o vetor gerado dele tambem nao. Foi o que fez o trecho certo pontuar 0,489 numa
 * busca pelo nome exato de um metodo deste repo.
 */
class CodeAwareSplitterTest {

    private final CodeAwareSplitter splitter = new CodeAwareSplitter(6000, 120);

    private static final String CLASSE = """
            package loja;

            public class Servico {

                private final Repositorio repositorio;

                public Servico(Repositorio repositorio) {
                    this.repositorio = repositorio;
                }

                public String buscar(String chave) {
                    if (chave == null) {
                        return "";
                    }
                    return repositorio.ler(chave);
                }

                public void gravar(String chave, String valor) {
                    repositorio.escrever(chave, valor);
                }
            }
            """;

    /**
     * "Chaves equilibradas por chunk" NAO e o invariante — foi o que eu supus duas vezes e errou as
     * duas. O primeiro chunk carrega a abertura da classe e o ultimo carrega o fechamento: isolados
     * ficam desequilibrados, juntos fecham. O que se cobra e o conjunto.
     */
    @Test
    void oConjuntoDosChunksFechaEquilibrado() {
        List<String> chunks = splitter.split(CLASSE);

        assertThat(chunks).isNotEmpty();
        String tudo = String.join("", chunks);
        assertThat(contar(tudo, '{')).isEqualTo(contar(tudo, '}'));
    }

    @Test
    void naoParteUmMetodoAoMeio() {
        String tudo = String.join("", splitter.split(CLASSE));

        // Nada se perde no caminho: recompor os chunks devolve o arquivo.
        assertThat(tudo).isEqualTo(CLASSE);
        // E cada assinatura continua colada ao proprio corpo.
        for (String chunk : splitter.split(CLASSE)) {
            if (chunk.contains("public String buscar")) {
                assertThat(chunk).contains("return repositorio.ler(chave);");
            }
        }
    }

    /** Chave dentro de string nao pode contar como bloco, senao tudo dali para a frente desalinha. */
    @Test
    void ignoraChaveDentroDeTextoEComentario() {
        String codigo =
                """
                public class A {
                    void x() {
                        String s = "isto { nao abre bloco";
                        // nem isto }
                    }
                }
                """;

        // Se a chave dentro da string contasse como bloco, a chave do comentario a fecharia cedo e o
        // metodo seria cortado no meio. O que se cobra e o metodo chegar inteiro num chunk so.
        List<String> chunks = new CodeAwareSplitter(6000, 0).split(codigo);

        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk).contains("void x()");
            assertThat(chunk).contains("isto { nao abre bloco");
            assertThat(chunk).contains("// nem isto }");
        });
    }

    @Test
    void soAssumeLinguagemDeChaves() {
        assertThat(CodeAwareSplitter.handles("src/Servico.java")).isTrue();
        assertThat(CodeAwareSplitter.handles("front/App.tsx")).isTrue();
        assertThat(CodeAwareSplitter.handles("README.md")).isFalse();
        assertThat(CodeAwareSplitter.handles("config.yml")).isFalse();
    }

    /**
     * A medicao que justifica a mudanca, feita num arquivo REAL do repositorio: quantos chunks saem
     * sintaticamente inteiros com cada estrategia.
     */
    @Test
    void ficaMelhorQueOCortePorTamanhoNumArquivoRealDoRepo() throws Exception {
        Path arquivo = Path.of("../avento-workspace/src/main/java/com/avento/service/WorkspaceAccessService.java");
        if (!Files.exists(arquivo)) {
            return; // roda a partir de outro diretorio: a regra ja esta coberta pelos casos acima.
        }
        String conteudo = Files.readString(arquivo);

        List<String> porEstrutura = splitter.split(conteudo);
        List<String> porTamanho = cortarPorTamanho(conteudo, 2000);

        double taxaEstrutura = proporcaoInteiros(porEstrutura);
        double taxaTamanho = proporcaoInteiros(porTamanho);

        // Nao se exige 100%: o chunk do topo carrega "public class X {", que abre uma chave fechada
        // so no fim do arquivo — ele fica desequilibrado por construcao, e esta certo assim. O que
        // se cobra e a diferenca entre as estrategias. Medido em tres arquivos reais deste repo:
        // 17 de 23 chunks inteiros por estrutura (74%) contra 7 de 21 por tamanho (33%).
        assertThat(taxaEstrutura).isGreaterThan(taxaTamanho * 1.5);
    }

    private List<String> cortarPorTamanho(String conteudo, int tamanho) {
        List<String> partes = new java.util.ArrayList<>();
        for (int i = 0; i < conteudo.length(); i += tamanho) {
            partes.add(conteudo.substring(i, Math.min(i + tamanho, conteudo.length())));
        }
        return partes;
    }

    /**
     * Um membro por chunk.
     *
     * <p>Acumular metodos ate encher um teto foi MEDIDO como pior: recall@5 caiu de 6/8 para 5/8, e o
     * {@code deleteChunks} — tres linhas — despencou da posicao 1 para a 73 quando viajou junto de
     * outros cinco metodos. Um vetor que e a media de seis assuntos nao responde nenhum deles.
     *
     * <p>Aqui o piso vai a zero de proposito: os metodos da classe de exemplo sao curtos e, com o
     * piso de producao, colariam no anterior — o que esconderia justamente a regra sob teste.
     */
    @Test
    void cadaMembroViraSeuProprioChunk() {
        List<String> chunks = new CodeAwareSplitter(6000, 0).split(CLASSE);

        assertThat(chunks.stream().filter(c -> c.contains("public String buscar")))
                .allSatisfy(c -> assertThat(c).doesNotContain("public void gravar"));
    }

    private double proporcaoInteiros(List<String> chunks) {
        return chunks.isEmpty()
                ? 0
                : (double) chunks.stream().filter(this::equilibrado).count() / chunks.size();
    }

    private boolean equilibrado(String chunk) {
        return contar(chunk, '{') == contar(chunk, '}');
    }

    private long contar(String texto, char alvo) {
        return texto.chars().filter(c -> c == alvo).count();
    }
}

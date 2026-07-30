package com.avento.service.intent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Trava a lista de frases que disparam geração de imagem.
 *
 * <p>As frases viviam como noventa {@code contains(...)} encadeados dentro de
 * {@code AgentService.wantsImageGeneration}. Este teste foi escrito ANTES de movê-las para
 * {@code agent/heuristics/image-prompt-signals.txt}, com exatamente a lista que estava no Java,
 * justamente para provar que a mudança não alterou comportamento nenhum — se alguma frase se
 * perdesse na transcrição, aqui quebraria.
 *
 * <p>Vale como rede permanente: editar o arquivo de heurísticas não exige recompilar, então nada
 * além deste teste impede alguém de apagar uma frase sem perceber.
 */
class ImageGenerationTriggerTest {

    private static final List<String> TRIGGERS_FROM_THE_OLD_JAVA_LIST = List.of(
            "gera imagem",
            "gerar imagem",
            "gere imagem",
            "cria imagem",
            "criar imagem",
            "crie imagem",
            "gera a imagem",
            "gerar a imagem",
            "gere a imagem",
            "gera uma imagem",
            "gerar uma imagem",
            "gere uma imagem",
            "gera pra mim imagem",
            "gera pra mim a imagem",
            "gera pra mim uma imagem",
            "gere pra mim imagem",
            "gere pra mim a imagem",
            "gere pra mim uma imagem",
            "gerar pra mim imagem",
            "gerar pra mim a imagem",
            "gerar pra mim uma imagem",
            "cria a imagem",
            "criar a imagem",
            "crie a imagem",
            "cria uma imagem",
            "criar uma imagem",
            "crie uma imagem",
            "cria pra mim imagem",
            "cria pra mim a imagem",
            "cria pra mim uma imagem",
            "faz uma imagem",
            "faca uma imagem",
            "faz pra mim imagem",
            "faz pra mim uma imagem",
            "faca pra mim imagem",
            "faca pra mim uma imagem",
            "produz imagem",
            "produzir imagem",
            "gera uma foto",
            "gerar uma foto",
            "gera pra mim uma foto",
            "gere pra mim uma foto",
            "cria uma foto",
            "criar uma foto",
            "generate image",
            "create image",
            "text to image",
            "imagem artistica",
            "ilustracao de",
            "ilustracao artistica",
            "retrato artistico",
            "gere imagem realista",
            "retrato explicito",
            "pintura de",
            "desenho de",
            "concept art",
            "quero uma imagem",
            "quero uma foto",
            "quero um desenho",
            "quero uma ilustracao",
            "quero um retrato",
            "me manda uma imagem",
            "me da uma imagem",
            "me de uma imagem",
            "me mostra uma imagem",
            "desenha uma",
            "desenha um",
            "desenhe uma",
            "desenhe um",
            "pinta uma",
            "pinta um",
            "pinte uma",
            "pinte um",
            "ilustra uma",
            "ilustra um",
            "ilustre uma",
            "ilustre um",
            "renderiza uma",
            "renderiza um",
            "renderize uma",
            "renderize um");

    @Test
    void everyPhraseFromTheOldJavaListStillTriggers() {
        for (String phrase : TRIGGERS_FROM_THE_OLD_JAVA_LIST) {
            assertThat(ImageIntentService.matchesGenerationTrigger(phrase))
                    .as("frase que disparava antes da migração: '%s'", phrase)
                    .isTrue();
        }
    }

    @Test
    void countsTheWholeList() {
        // Uma frase apagada do arquivo de heurísticas sem querer cairia no teste acima; esta
        // contagem pega o contrário — alguém removendo a frase do teste junto com a do arquivo.
        assertThat(TRIGGERS_FROM_THE_OLD_JAVA_LIST).hasSize(81);
    }

    @Test
    void leavesOrdinaryRequestsAlone() {
        List<String> notImageRequests = List.of(
                "analisa esse projeto pra mim",
                "le o arquivo pom.xml",
                "roda os testes",
                "explica o que esse metodo faz",
                "cria um arquivo de configuracao",
                "gera o relatorio de cobertura",
                "faz um resumo da conversa",
                "abre o terminal",
                "qual a cotacao do dolar",
                "me da um prompt melhor pra essa imagem");
        for (String request : notImageRequests) {
            assertThat(ImageIntentService.matchesGenerationTrigger(request))
                    .as("pedido que não é de geração de imagem: '%s'", request)
                    .isFalse();
        }
    }
}

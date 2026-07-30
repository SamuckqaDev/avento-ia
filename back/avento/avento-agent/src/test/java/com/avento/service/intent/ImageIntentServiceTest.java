package com.avento.service.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

/**
 * Os três julgamentos que se parecem e não são a mesma coisa: protótipo de interface, geração de
 * imagem e captura de tela.
 *
 * <p>Eram testes por reflexão dentro do AgentService. Com o serviço separado, a chamada é direta —
 * e o caso do typo ("terla") continua aqui porque foi um pedido real que caiu no generate_image.
 */
class ImageIntentServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ImageIntentService service = new ImageIntentService(new VisualIntentClassifier());

    private ArrayNode userMessages(String... contents) {
        ArrayNode messages = MAPPER.createArrayNode();
        for (String content : contents) {
            messages.addObject().put("role", "user").put("content", content);
        }
        return messages;
    }

    @Test
    void mockupRequestsRouteToUiPreviewNotImageGeneration() {
        assertThat(service.wantsInterfacePrototype("gera um mockup de um terla de login mobile"))
                .isTrue();
        assertThat(service.wantsImageGeneration("gera um mockup de um terla de login mobile"))
                .isFalse();
        assertThat(service.wantsImageGeneration("quero um mockup com preview da tela de login"))
                .isFalse();
        assertThat(service.wantsInterfacePrototype("faz um wireframe da tela de cadastro"))
                .isTrue();
    }

    @Test
    void realImageRequestsStillRouteToImageGeneration() {
        assertThat(service.wantsImageGeneration("gera uma imagem de um pitbull marrom"))
                .isTrue();
        assertThat(service.wantsInterfacePrototype("gera uma imagem de um pitbull marrom"))
                .isFalse();
        // "captura de tela" nao pode ser confundido com prototipo de tela.
        assertThat(service.wantsInterfacePrototype("tira um print da tela")).isFalse();
    }

    @Test
    void defersModelNarrationForMediaGenerationRequests() {
        assertThat(service.shouldDeferMediaNarration(userMessages("Gere uma imagem realista de um carro vermelho.")))
                .isTrue();
        assertThat(service.shouldDeferMediaNarration(userMessages("Crie um vídeo curto a partir desta cena.")))
                .isTrue();
        assertThat(service.shouldDeferMediaNarration(userMessages("Explique como o ComfyUI gera imagens.")))
                .isFalse();
    }
}

package com.avento.service.intent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class VisualIntentClassifierTest {

    private final VisualIntentClassifier classifier = new VisualIntentClassifier();

    @ParameterizedTest
    @MethodSource("interfaceCases")
    void classifiesInterfacePrototypes(String message, boolean expected) {
        assertThat(classifier.isInterfacePrototype(message)).isEqualTo(expected);
    }

    static Stream<Arguments> interfaceCases() {
        return Stream.of(
                Arguments.of("crie um mockup da tela de login", true),
                Arguments.of("create a dashboard ui mockup", true),
                Arguments.of("mockup de embalagem para cafe", false),
                Arguments.of("mockup de camiseta preta", false),
                Arguments.of("implemente este mockup no front", false),
                Arguments.of("tire um screenshot da tela", false));
    }

    @ParameterizedTest
    @MethodSource("productCases")
    void classifiesProductMockups(String message, boolean expected) {
        assertThat(classifier.isProductMockup(message)).isEqualTo(expected);
    }

    static Stream<Arguments> productCases() {
        return Stream.of(
                Arguments.of("crie um mockup de embalagem", true),
                Arguments.of("gere um mock-up de camiseta", true),
                Arguments.of("crie um mockup da tela mobile", false));
    }
}

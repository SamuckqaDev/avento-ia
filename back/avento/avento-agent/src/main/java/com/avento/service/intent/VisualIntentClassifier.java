package com.avento.service.intent;

import com.avento.service.support.HeuristicWordLists;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Separa três pedidos visuais que se parecem e pedem caminhos diferentes: protótipo de interface
 * (vira HTML), mockup de produto (vira imagem) e implementação de código (não vira nenhum dos dois).
 *
 * <p>Os termos vivem em {@code agent/heuristics/visual-intent.txt}, não aqui: ajustar o vocabulário
 * é a coisa que mais se faz nesta classe, e no arquivo isso não exige recompilar.
 */
@Component
public class VisualIntentClassifier {

    private static final Map<String, List<String>> TERMS =
            HeuristicWordLists.loadSections("agent/heuristics/visual-intent.txt");

    public boolean isInterfacePrototype(String message) {
        if (blank(message) || isScreenCapture(message) || isCodeImplementation(message)) {
            return false;
        }
        boolean prototypeSignal = containsAny(message, "PROTOTYPE_SIGNAL");
        boolean interfaceQualifier = containsAny(message, "INTERFACE_QUALIFIER");
        return (prototypeSignal && interfaceQualifier) || containsAny(message, "INTERFACE_PHRASE");
    }

    public boolean isCodeImplementation(String message) {
        if (blank(message)) {
            return false;
        }
        return containsAny(message, "IMPLEMENTATION_VERB") && containsAny(message, "CODE_TARGET");
    }

    public boolean isProductMockup(String message) {
        return !blank(message)
                && containsAny(message, "PRODUCT_MOCKUP_SIGNAL")
                && containsAny(message, "PRODUCT_TARGET");
    }

    private boolean isScreenCapture(String message) {
        return containsAny(message, "SCREEN_CAPTURE");
    }

    private boolean containsAny(String message, String section) {
        for (String value : TERMS.getOrDefault(section, List.of())) {
            if (message.contains(value) || startsWithBoundedTerm(message, value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Um termo com espaço nas bordas, como {@code " ui "}, nunca casaria no começo da mensagem —
     * "ui mockup do painel" não tem espaço antes do "ui". A borda da esquerda também é o começo do
     * texto.
     */
    private boolean startsWithBoundedTerm(String message, String value) {
        return value.startsWith(" ") && message.startsWith(value.stripLeading());
    }

    private boolean blank(String message) {
        return message == null || message.isBlank();
    }
}

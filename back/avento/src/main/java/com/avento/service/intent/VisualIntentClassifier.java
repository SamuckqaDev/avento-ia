package com.avento.service.intent;

import org.springframework.stereotype.Component;

@Component
public class VisualIntentClassifier {

    public boolean isInterfacePrototype(String message) {
        if (blank(message) || isScreenCapture(message) || isCodeImplementation(message)) {
            return false;
        }
        boolean prototypeSignal =
                containsAny(message, "mockup", "mock-up", "wireframe", "prototipo", "prototipar", "prototype");
        boolean interfaceQualifier = containsAny(
                message,
                "tela",
                "screen",
                "interface",
                " ui ",
                "dashboard",
                "painel",
                "site",
                "pagina web",
                "web page",
                "aplicativo",
                " app ",
                "login",
                "cadastro");
        return (prototypeSignal && interfaceQualifier)
                || containsAny(
                        message,
                        "tela de login",
                        "tela de cadastro",
                        "tela mobile",
                        "tela do app",
                        "interface de",
                        "layout de tela",
                        "ui mockup");
    }

    public boolean isCodeImplementation(String message) {
        if (blank(message)) {
            return false;
        }
        boolean implementationVerb = containsAny(
                message,
                "implemente",
                "implementar",
                "codifique",
                "codar",
                "transforme",
                "aplique",
                "implement",
                "code this",
                "apply");
        boolean codeTarget = containsAny(
                message,
                "codigo",
                "code",
                "projeto",
                "project",
                "componente",
                "component",
                "front",
                "mockup",
                "layout");
        return implementationVerb && codeTarget;
    }

    public boolean isProductMockup(String message) {
        return !blank(message)
                && containsAny(message, "mockup", "mock-up")
                && containsAny(
                        message,
                        "embalagem",
                        "package",
                        "produto",
                        "product",
                        "camiseta",
                        "t-shirt",
                        "roupa",
                        "garrafa",
                        "poster",
                        "cartaz");
    }

    private boolean isScreenCapture(String message) {
        return containsAny(message, "captura de tela", "screenshot", "print da tela", "tire um print");
    }

    private boolean containsAny(String message, String... values) {
        for (String value : values) {
            if (message.contains(value) || (" ui ".equals(value) && message.startsWith("ui "))) {
                return true;
            }
        }
        return false;
    }

    private boolean blank(String message) {
        return message == null || message.isBlank();
    }
}

package com.avento.service.image;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ImagePromptPlanner {

    private static final Pattern DIRECT_IMAGE_COMMAND =
            Pattern.compile("(?is)^\\s*/(?:generate-image|generate_image)\\s+(.+?)\\s*$");
    private static final Pattern HUMAN_TERMS =
            Pattern.compile("\\b(mulher(?:es)?|homem|homens|pessoa(?:s)?|rapaz(?:es)?|garot[oa]s?|moc[oa]s?|"
                    + "woman|women|man|men|person|people|boy|boys|girl|girls)\\b");
    private static final Pattern IDENTITY_PERSON_TERMS =
            Pattern.compile("\\b(um|uma|one|a|an)\\s+(negro|negra|japones|japonesa|black|japanese)\\b");
    private static final Pattern MALE_TERMS =
            Pattern.compile("\\b(homem|homens|rapaz|rapazes|garoto|garotos|moco|mocos|man|men|male|boy|boys)\\b"
                    + "|\\bum\\s+(negro|japones)\\b");
    private static final Pattern FEMALE_TERMS =
            Pattern.compile("\\b(mulher|mulheres|garota|garotas|moca|mocas|woman|women|female|girl|girls)\\b"
                    + "|\\buma\\s+(negra|japonesa)\\b");
    private static final Pattern BLACK_IDENTITY_TERMS = Pattern.compile("\\b(negro|negra|black)\\b");
    private static final Pattern JAPANESE_IDENTITY_TERMS = Pattern.compile("\\b(japones|japonesa|japanese)\\b");
    private static final Pattern REALISTIC_TERMS = Pattern.compile(
            "\\b(real|realista|realistic|foto|fotografia|photo|photography|photorealistic|editorial|propaganda|advertising)\\b");
    private static final Pattern PARKING_TERMS =
            Pattern.compile("\\b(estacionamento|garagem|parking lot|parking garage)\\b");
    private static final Pattern CAR_TERMS = Pattern.compile(
            "\\b(carro|carros|automovel|automoveis|veiculo|veiculos|car|cars|automobile|vehicle|vehicles)\\b");
    private static final Pattern RED_TERMS = Pattern.compile("\\b(vermelh[oa]s?|red)\\b");
    private static final Pattern FRONTAL_VIEW_TERMS = Pattern.compile(
            "\\b(frontal|front view|facing the camera|de frente|vista frontal|olhando para a camera)\\b");
    private static final Pattern FULL_BODY_TERMS = Pattern.compile(
            "\\b(full body|head to toe|feet visible|both feet|corpo inteiro|dos pes a cabeca|pes visiveis)\\b");
    private static final Pattern HANDS_AWAY_TERMS = Pattern.compile(
            "\\b(hands away|arms relaxed|arms at (?:the )?sides|maos afastadas|bracos relaxados|bracos ao lado)\\b");
    private static final Pattern CLEAN_BACKGROUND_TERMS = Pattern.compile(
            "\\b(clean (?:seamless )?background|neutral background|fundo limpo|fundo neutro|sem equipamentos)\\b");
    private static final Pattern MACRO_TERMS =
            Pattern.compile("\\b(macro|extreme close-up|close-up|closeup|grande plano|primeirissimo plano)\\b");
    private static final Pattern ANATOMICAL_TERMS = Pattern.compile(
            "\\b(anatomy|anatomical|anatomia|anatomico|anatomica|body part|body region|parte do corpo|regiao do corpo|"
                    + "eye|eyes|olho|olhos|ear|ears|orelha|orelhas|nose|nariz|mouth|boca|hand|hands|mao|maos|"
                    + "foot|feet|pe|pes|torso|chest|peito|skin|pele|genital|genitalia|vulva|clitoris|labia)\\b");

    private ImagePromptPlanner() {}

    public static ImagePromptPlan plan(String prompt, ImageGenerationOptions options) {
        String original = sanitizePrompt(prompt);
        ImageGenerationOptions effectiveOptions = options == null ? ImageGenerationOptions.defaults() : options;
        String normalized = normalize(original);
        SubjectType subjectType = SubjectType.from(effectiveOptions.subjectType());
        boolean inferredHumanSubject = HUMAN_TERMS.matcher(normalized).find()
                || IDENTITY_PERSON_TERMS.matcher(normalized).find();
        boolean humanSubject =
                subjectType == SubjectType.PERSON || (subjectType == SubjectType.AUTO && inferredHumanSubject);
        boolean realistic = REALISTIC_TERMS.matcher(normalized).find();
        boolean parkingScene = PARKING_TERMS.matcher(normalized).find();
        boolean carSubject = CAR_TERMS.matcher(normalized).find();
        boolean redSubject = RED_TERMS.matcher(normalized).find();
        boolean frontalView = FRONTAL_VIEW_TERMS.matcher(normalized).find();
        boolean fullBody = FULL_BODY_TERMS.matcher(normalized).find();
        boolean handsAway = HANDS_AWAY_TERMS.matcher(normalized).find();
        boolean cleanBackground = CLEAN_BACKGROUND_TERMS.matcher(normalized).find();
        boolean anatomicalCloseUp = MACRO_TERMS.matcher(normalized).find()
                && ANATOMICAL_TERMS.matcher(normalized).find();
        SubjectGender gender = inferGender(normalized);
        int subjectCount = anatomicalCloseUp
                ? 0
                : effectiveOptions.subjectCount() > 0
                        ? effectiveOptions.subjectCount()
                        : inferSubjectCount(normalized, humanSubject);

        // The user's request always leads the positive prompt: CLIP truncates at 77 tokens,
        // so guidance appended by the planner is what gets cut, never the requested content.
        StringBuilder positive = new StringBuilder(original);
        if (subjectCount > 0) {
            positive.append(", ").append(exactCountInstruction(subjectCount, humanSubject));
        }
        if (subjectType != SubjectType.AUTO && !anatomicalCloseUp) {
            positive.append(", ").append(primarySubjectInstruction(subjectType));
        }
        if (effectiveOptions.enhancePrompt() && humanSubject && !anatomicalCloseUp) {
            String identityInstruction = identityInstruction(normalized, gender);
            if (!identityInstruction.isBlank()) {
                positive.append(", ").append(identityInstruction);
            }
        }
        if (effectiveOptions.enhancePrompt()) {
            if (anatomicalCloseUp) {
                positive.append(
                        ", isolated anatomical macro composition, frame contains only the requested anatomical region and explicitly requested interacting body parts, unrelated face and head structures excluded");
            } else {
                appendNonHumanSceneGuidance(
                        positive, subjectType, humanSubject, parkingScene, carSubject, redSubject, subjectCount);
            }
            if (realistic) {
                positive.append(
                        ", photorealistic photography, natural skin texture, realistic proportions, clean subject separation");
            } else {
                positive.append(", coherent composition, clear subject separation, consistent proportions");
            }
            if (humanSubject && !anatomicalCloseUp) {
                positive.append(
                        ", anatomically coherent body, natural hands, symmetrical facial features, each face located only on its subject's head");
                appendHumanCompositionGuidance(positive, frontalView, fullBody, handsAway, cleanBackground);
            }
        }

        StringBuilder negative = new StringBuilder(
                "low quality, blurry, distorted, malformed anatomy, fused body, extra limbs, missing limbs, "
                        + "duplicated face, asymmetrical eyes, warped body, watermark, text");
        if (!anatomicalCloseUp) {
            negative.append(", cropped subject, cut off body");
        }
        if (anatomicalCloseUp) {
            negative.append(
                    ", unrelated face, mouth, lips, teeth, tongue, head, incorrect body region, substituted anatomy, fused anatomy");
        } else if (humanSubject) {
            negative.append(
                    ", extra fingers, missing fingers, fused fingers, malformed hands, plastic skin, face outside head, face on torso, face on abdomen, face on pelvis, body-face fusion");
            appendGenderNegative(negative, gender);
            appendCompositionNegative(negative, frontalView, fullBody, handsAway, cleanBackground);
        } else {
            negative.append(", person, people, woman, man, human, portrait, face, body");
            if (parkingScene || carSubject) {
                negative.append(", hat, clothing, fashion portrait, human silhouette");
            }
            appendSubjectTypeNegative(negative, subjectType);
        }
        appendCountNegative(negative, subjectCount, humanSubject);
        return new ImagePromptPlan(original, positive.toString(), negative.toString(), subjectCount, humanSubject);
    }

    private static String sanitizePrompt(String prompt) {
        String sanitized = prompt == null ? "" : prompt.trim();
        var commandMatcher = DIRECT_IMAGE_COMMAND.matcher(sanitized);
        if (commandMatcher.matches()) {
            sanitized = commandMatcher.group(1).trim();
        }
        if (sanitized.length() >= 2) {
            char first = sanitized.charAt(0);
            char last = sanitized.charAt(sanitized.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                sanitized = sanitized.substring(1, sanitized.length() - 1).trim();
            }
        }
        return sanitized;
    }

    private static void appendHumanCompositionGuidance(
            StringBuilder positive, boolean frontalView, boolean fullBody, boolean handsAway, boolean cleanBackground) {
        if (frontalView) {
            positive.append(", (strict frontal view:1.35), shoulders and hips facing camera directly");
        }
        if (fullBody) {
            positive.append(", (complete head-to-toe framing:1.3), both feet visible inside frame");
        }
        if (handsAway) {
            positive.append(", (arms relaxed beside the body:1.25), hands unobstructing the torso");
        }
        if (cleanBackground) {
            positive.append(", (clean seamless background:1.2), subject-only studio composition");
        }
    }

    private static void appendCompositionNegative(
            StringBuilder negative, boolean frontalView, boolean fullBody, boolean handsAway, boolean cleanBackground) {
        if (frontalView) {
            negative.append(", side view, profile view, three-quarter view, turned body");
        }
        if (fullBody) {
            negative.append(", close-up, upper body crop, cropped legs, feet outside frame");
        }
        if (handsAway) {
            negative.append(", hands covering torso, crossed arms, obstructed body");
        }
        if (cleanBackground) {
            negative.append(", studio equipment, light stands, softbox, backdrop clutter");
        }
    }

    private static void appendNonHumanSceneGuidance(
            StringBuilder positive,
            SubjectType subjectType,
            boolean humanSubject,
            boolean parkingScene,
            boolean carSubject,
            boolean redSubject,
            int subjectCount) {
        if (humanSubject) {
            return;
        }
        positive.append(", scene focused exclusively on the requested objects and environment, no human subjects");
        boolean vehicleSubject = subjectType == SubjectType.VEHICLE || carSubject;
        if (subjectType == SubjectType.OBJECT) {
            positive.append(", object-focused composition, complete requested object, coherent product geometry");
        } else if (subjectType == SubjectType.ENVIRONMENT) {
            positive.append(
                    ", environment-focused composition, coherent spatial layout, clear foreground and background");
        } else if (subjectType == SubjectType.ANIMAL) {
            positive.append(
                    ", animal-focused composition, species-accurate anatomy, natural limbs and facial structure");
        }
        if (parkingScene && vehicleSubject && subjectCount <= 1) {
            positive.append(", (parking lot as the main scene:1.25), (single car as the main subject:1.3)");
        } else if (parkingScene) {
            positive.append(", (parking lot as the main scene:1.25)");
        } else if (vehicleSubject) {
            positive.append(
                    ", (vehicle as the main subject:1.3), automotive photography, complete coherent vehicle body");
        }
        if (vehicleSubject && redSubject) {
            positive.append(", (red painted car body:1.25)");
        }
        if (parkingScene || vehicleSubject) {
            positive.append(", vehicle-focused composition, empty scene, no people");
        }
    }

    private static String primarySubjectInstruction(SubjectType subjectType) {
        return switch (subjectType) {
            case PERSON -> "(human person as the primary subject:1.3)";
            case OBJECT -> "(requested object or product as the primary subject:1.3)";
            case ENVIRONMENT -> "(requested environment or scene as the primary subject:1.3)";
            case VEHICLE -> "(requested vehicle as the primary subject:1.35)";
            case ANIMAL -> "(requested animal as the primary subject:1.3)";
            case AUTO -> "";
        };
    }

    private static void appendSubjectTypeNegative(StringBuilder negative, SubjectType subjectType) {
        switch (subjectType) {
            case OBJECT -> negative.append(", fashion model, product worn by person, object fused with body");
            case ENVIRONMENT -> negative.append(", close-up portrait, dominant human subject, fashion photography");
            case VEHICLE -> negative.append(", warped vehicle, fused vehicle parts, extra wheels, missing wheels");
            case ANIMAL -> negative.append(", human-like anatomy, anthropomorphic body, extra animal limbs");
            case AUTO, PERSON -> {
                // No category-specific exclusions are needed.
            }
        }
    }

    private static int inferSubjectCount(String normalized, boolean humanSubject) {
        if (!humanSubject) {
            return 0;
        }
        if (containsAny(normalized, "quatro ", "four ")) return 4;
        if (containsAny(normalized, "tres ", "three ")) return 3;
        if (containsAny(normalized, "duas ", "dois ", "two ", "casal", "couple")) return 2;
        if (normalized.matches(".*\\b(mulheres|homens|pessoas|women|men|people)\\b.*")) return 2;

        int humanMentions = 0;
        var matcher = HUMAN_TERMS.matcher(normalized);
        while (matcher.find()) {
            humanMentions++;
        }
        if (humanMentions >= 2) return 2;
        return humanSubject ? 1 : 0;
    }

    private static String exactCountInstruction(int count, boolean humanSubject) {
        String noun = humanSubject ? (count == 1 ? "person" : "people") : (count == 1 ? "subject" : "subjects");
        return "exactly " + count + " " + noun + ", "
                + (count == 1 ? "solo composition" : "all subjects clearly separated");
    }

    private static void appendCountNegative(StringBuilder negative, int count, boolean humanSubject) {
        if (!humanSubject || count <= 0) {
            negative.append(", duplicated subject, merged subjects");
            return;
        }
        negative.append(", duplicated person, merged people");
        if (count == 1) {
            negative.append(", extra person, multiple people, second person");
        } else if (count == 2) {
            negative.append(", extra person, third person");
        } else if (count == 3) {
            negative.append(", extra person, fourth person");
        } else {
            negative.append(", extra person beyond requested count");
        }
    }

    private static SubjectGender inferGender(String normalized) {
        boolean male = MALE_TERMS.matcher(normalized).find();
        boolean female = FEMALE_TERMS.matcher(normalized).find();
        if (male == female) {
            return SubjectGender.UNSPECIFIED;
        }
        return male ? SubjectGender.MALE : SubjectGender.FEMALE;
    }

    private static String identityInstruction(String normalized, SubjectGender gender) {
        boolean blackIdentity = BLACK_IDENTITY_TERMS.matcher(normalized).find();
        boolean japaneseIdentity = JAPANESE_IDENTITY_TERMS.matcher(normalized).find();
        String noun =
                switch (gender) {
                    case MALE -> "man";
                    case FEMALE -> "woman";
                    case UNSPECIFIED -> "person";
                };

        if (blackIdentity && japaneseIdentity) {
            return "(Black Japanese adult " + noun
                    + ":1.3), (dark brown skin:1.2), Japanese nationality and African ancestry, "
                    + "facial features reflecting both Japanese and African heritage";
        }
        if (blackIdentity) {
            return "(Black adult " + noun + ":1.25), (dark brown skin:1.15), African ancestry";
        }
        if (japaneseIdentity) {
            return "(Japanese adult " + noun + ":1.25), Japanese facial features";
        }
        return switch (gender) {
            case MALE -> "adult man, clearly masculine facial structure and body";
            case FEMALE -> "adult woman, clearly feminine facial structure and body";
            case UNSPECIFIED -> "";
        };
    }

    private static void appendGenderNegative(StringBuilder negative, SubjectGender gender) {
        if (gender == SubjectGender.MALE) {
            negative.append(", woman, female subject, feminine body, breasts");
        } else if (gender == SubjectGender.FEMALE) {
            negative.append(", man, male subject, masculine body");
        }
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
    }

    private enum SubjectGender {
        MALE,
        FEMALE,
        UNSPECIFIED
    }

    private enum SubjectType {
        AUTO,
        PERSON,
        OBJECT,
        ENVIRONMENT,
        VEHICLE,
        ANIMAL;

        private static SubjectType from(String value) {
            if (value == null || value.isBlank()) {
                return AUTO;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return AUTO;
            }
        }
    }
}

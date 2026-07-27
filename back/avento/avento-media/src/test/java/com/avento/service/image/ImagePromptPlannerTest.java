package com.avento.service.image;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImagePromptPlannerTest {

    @Test
    void reinforcesASingleRealisticPersonWithoutChangingTheOriginalRequest() {
        ImageGenerationOptions options = new ImageGenerationOptions("quality", "portrait", 42L, 1, true);

        ImagePromptPlan plan =
                ImagePromptPlanner.plan("Uma mulher realista em uma campanha editorial de lingerie", options);

        assertThat(plan.originalPrompt()).isEqualTo("Uma mulher realista em uma campanha editorial de lingerie");
        assertThat(plan.positivePrompt())
                .contains("exactly 1 person")
                .contains("photorealistic photography")
                .contains("anatomically coherent body")
                .contains("each face located only on its subject's head");
        assertThat(plan.negativePrompt())
                .contains("extra person", "second person", "malformed hands", "face on pelvis", "body-face fusion");
        assertThat(plan.subjectCount()).isEqualTo(1);
    }

    @Test
    void doesNotUseSinglePersonNegativesForTwoRequestedPeople() {
        ImageGenerationOptions options = new ImageGenerationOptions("balanced", "landscape", null, 2, true);

        ImagePromptPlan plan = ImagePromptPlanner.plan("Duas mulheres em uma fotografia editorial", options);

        assertThat(plan.positivePrompt()).contains("exactly 2 people");
        assertThat(plan.negativePrompt()).contains("third person").doesNotContain("multiple people", "second person");
        assertThat(plan.subjectCount()).isEqualTo(2);
    }

    @Test
    void infersOnePersonEvenWhenThePromptContainsAnActionConnector() {
        ImagePromptPlan plan =
                ImagePromptPlanner.plan("Uma mulher em pé e sorrindo para a câmera", ImageGenerationOptions.defaults());

        assertThat(plan.subjectCount()).isEqualTo(1);
        assertThat(plan.positivePrompt()).contains("exactly 1 person");
    }

    @Test
    void locksMasculineBlackJapaneseIdentityFromPortugueseGrammar() {
        ImagePromptPlan plan = ImagePromptPlanner.plan("um negro japonês", ImageGenerationOptions.defaults());

        assertThat(plan.subjectCount()).isEqualTo(1);
        assertThat(plan.positivePrompt())
                .contains("exactly 1 person")
                .contains("(Black Japanese adult man:1.3)")
                .contains("Japanese nationality and African ancestry")
                .contains("dark brown skin");
        assertThat(plan.negativePrompt()).contains("woman", "female subject", "feminine body");
    }

    @Test
    void locksFeminineIdentityWithoutApplyingMaleNegatives() {
        ImagePromptPlan plan = ImagePromptPlanner.plan("uma negra japonesa", ImageGenerationOptions.defaults());

        assertThat(plan.positivePrompt()).contains("(Black Japanese adult woman:1.3)");
        assertThat(plan.negativePrompt()).contains("man", "male subject").doesNotContain("female subject");
    }

    @Test
    void keepsTheUserContentAtTheStartOfThePositivePrompt() {
        ImagePromptPlan plan = ImagePromptPlanner.plan(
                "a woman in a realistic editorial lingerie campaign", ImageGenerationOptions.defaults());

        assertThat(plan.positivePrompt()).startsWith("a woman in a realistic editorial lingerie campaign");
    }

    @Test
    void keepsPromptUntouchedWhenEnhancementAndExactCountAreDisabled() {
        ImageGenerationOptions options = new ImageGenerationOptions("draft", "square", null, 0, false);

        ImagePromptPlan plan = ImagePromptPlanner.plan("Uma paisagem futurista", options);

        assertThat(plan.positivePrompt()).isEqualTo("Uma paisagem futurista");
        assertThat(plan.subjectCount()).isZero();
    }

    @Test
    void removesSlashCommandEnvelopeBeforePlanning() {
        ImagePromptPlan plan = ImagePromptPlanner.plan(
                "/generate-image \"Macro photography of a human eye, anatomical reference\"",
                ImageGenerationOptions.defaults());

        assertThat(plan.originalPrompt()).isEqualTo("Macro photography of a human eye, anatomical reference");
        assertThat(plan.positivePrompt()).doesNotContain("/generate-image", "\"");
    }

    @Test
    void treatsAnatomicalMacroAsARegionInsteadOfAWholePerson() {
        ImageGenerationOptions options = new ImageGenerationOptions("quality", "square", "person", null, 1, true);

        ImagePromptPlan plan =
                ImagePromptPlanner.plan("Macro photography of a human hand, anatomical reference", options);

        assertThat(plan.subjectCount()).isZero();
        assertThat(plan.positivePrompt())
                .contains("isolated anatomical macro composition")
                .contains("only the requested anatomical region")
                .doesNotContain(
                        "exactly 1 person", "human person as the primary subject", "symmetrical facial features");
        assertThat(plan.negativePrompt())
                .contains("unrelated face", "incorrect body region")
                .doesNotContain("cropped subject", "cut off body");
    }

    @Test
    void locksParkingAndRedCarAsTheSubjectsWithoutInventingPeople() {
        ImagePromptPlan plan =
                ImagePromptPlanner.plan("Um estacionamento com um carro vermelho", ImageGenerationOptions.defaults());

        assertThat(plan.humanSubject()).isFalse();
        assertThat(plan.positivePrompt())
                .contains("parking lot as the main scene")
                .contains("single car as the main subject")
                .contains("red painted car body")
                .contains("no people");
        assertThat(plan.negativePrompt()).contains("woman", "man", "portrait", "hat", "fashion portrait");
    }

    @Test
    void explicitVehicleTypeOverridesHumanTermsAndKeepsTheVehiclePrimary() {
        ImageGenerationOptions options = new ImageGenerationOptions("quality", "landscape", "vehicle", null, 1, true);

        ImagePromptPlan plan =
                ImagePromptPlanner.plan("Um carro vermelho em um estacionamento com uma mulher ao fundo", options);

        assertThat(plan.humanSubject()).isFalse();
        assertThat(plan.positivePrompt())
                .contains("requested vehicle as the primary subject")
                .contains("single car as the main subject")
                .contains("red painted car body")
                .contains("no people");
        assertThat(plan.negativePrompt()).contains("woman", "portrait", "warped vehicle", "extra wheels");
    }

    @Test
    void explicitPersonTypeEnablesHumanPlanningForAnAmbiguousPortraitPrompt() {
        ImageGenerationOptions options = new ImageGenerationOptions("balanced", "portrait", "person", null, 0, true);

        ImagePromptPlan plan = ImagePromptPlanner.plan("Retrato editorial realista", options);

        assertThat(plan.humanSubject()).isTrue();
        assertThat(plan.subjectCount()).isEqualTo(1);
        assertThat(plan.positivePrompt())
                .contains("exactly 1 person")
                .contains("human person as the primary subject")
                .contains("anatomically coherent body");
    }

    @Test
    void explicitEnvironmentTypePreventsAPortraitFromDominatingTheScene() {
        ImageGenerationOptions options =
                new ImageGenerationOptions("balanced", "landscape", "environment", null, 0, true);

        ImagePromptPlan plan = ImagePromptPlanner.plan("Praça japonesa com uma mulher ao longe", options);

        assertThat(plan.humanSubject()).isFalse();
        assertThat(plan.positivePrompt())
                .contains("requested environment or scene as the primary subject")
                .contains("environment-focused composition");
        assertThat(plan.negativePrompt()).contains("dominant human subject", "close-up portrait");
    }

    @Test
    void convertsExplicitHumanCompositionConstraintsIntoWeightedConditioning() {
        ImagePromptPlan plan = ImagePromptPlanner.plan(
                "Photorealistic full body adult woman, strict frontal view, facing the camera, arms relaxed at the sides, both feet visible, clean neutral background",
                ImageGenerationOptions.defaults());

        assertThat(plan.positivePrompt())
                .contains("(strict frontal view:1.35)")
                .contains("complete head-to-toe framing")
                .contains("arms relaxed beside the body")
                .contains("clean seamless background");
        assertThat(plan.negativePrompt())
                .contains("side view", "three-quarter view")
                .contains("cropped legs", "feet outside frame")
                .contains("hands covering torso", "crossed arms")
                .contains("studio equipment", "softbox");
    }

    @Test
    void exposesStableQualityAndAspectPresets() {
        ImageGenerationOptions options = new ImageGenerationOptions("quality", "portrait", 99L, 9, true);

        assertThat(options.steps()).isEqualTo(24);
        assertThat(options.cfg()).isEqualTo(5.8);
        assertThat(options.size()).isEqualTo("512x768");
        assertThat(options.subjectCount()).isEqualTo(6);
        assertThat(options.seed()).isEqualTo(99L);
        assertThat(ImageGenerationOptions.defaults().size()).isEqualTo("512x512");
        assertThat(ImageGenerationOptions.defaults().subjectType()).isEqualTo("auto");
    }

    @Test
    void normalizesUnsupportedSubjectTypesToAutomaticMode() {
        ImageGenerationOptions options = new ImageGenerationOptions("balanced", "square", "spaceship", null, 0, true);

        assertThat(options.subjectType()).isEqualTo("auto");
    }

    @Test
    void boundsAdvancedImageControlsAndRejectsInvalidPoseData() {
        ImageGenerationOptions options = new ImageGenerationOptions(
                "quality",
                "portrait",
                null,
                1,
                true,
                true,
                9.0,
                "face-hands",
                99.0,
                "not-a-reference",
                7.0,
                "not-an-image",
                4.0);

        assertThat(options.refinementStrength()).isEqualTo(0.55);
        assertThat(options.detailMode()).isEqualTo("face-hands");
        assertThat(options.cfg()).isEqualTo(12.0);
        assertThat(options.referenceImageDataUrl()).isEmpty();
        assertThat(options.referenceStrength()).isEqualTo(0.9);
        assertThat(options.poseReferenceDataUrl()).isEmpty();
        assertThat(options.poseStrength()).isEqualTo(1.5);
        assertThat(options.refinementSteps()).isEqualTo(8);
        assertThat(options.detailerSteps()).isEqualTo(8);
    }
}

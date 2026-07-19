package com.avento.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.service.dto.Skill;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillRegistryTest {

    private final SkillRegistry registry = new SkillRegistry();

    @Test
    void loadsTheNestjsProjectSkillFromTheClasspath() {
        Optional<Skill> skill = registry.find("nestjs-project");

        assertTrue(skill.isPresent());
        assertEquals(
                "Cria um projeto NestJS na pasta indicada usando o CLI oficial",
                skill.get().description());
        assertTrue(skill.get().body().contains("npx --yes @nestjs/cli@latest new"));
        assertTrue(skill.get().body().contains("timeoutSeconds: 300"));
    }

    @Test
    void loadsTheJavaMaintenanceSkillWithArchitectureRules() {
        Skill skill = registry.find("java-project-maintenance").orElseThrow();

        assertTrue(skill.body().contains("@RequiredArgsConstructor"));
        assertTrue(skill.body().contains("Nunca exponha entidade JPA diretamente"));
        assertTrue(skill.body().contains("controller -> service/application -> domain"));
    }

    @Test
    void parsesTriggersFromTheGatilhosLineAndKeepsThemOutOfTheBody() {
        Skill skill = registry.find("nestjs-project").orElseThrow();

        assertTrue(skill.triggers().contains("criar nestjs"));
        assertTrue(skill.triggers().contains("projeto nestjs"));
        assertTrue(!skill.body().toLowerCase().contains("gatilhos:"));
    }

    @Test
    void autoMatchFindsTheSkillWhenAllTriggerWordsAppearInTheMessage() {
        Optional<Skill> matched =
                registry.autoMatch("agora vc vai executar criar um projeto nestjs dentro dessa pasta back");

        assertTrue(matched.isPresent());
        assertEquals("nestjs-project", matched.get().name());
    }

    @Test
    void autoMatchFindsJavaMaintenanceForASpringRefactor() {
        Skill matched =
                registry.autoMatch("preciso refatorar este service java").orElseThrow();

        assertEquals("java-project-maintenance", matched.name());
    }

    @Test
    void autoMatchRoutesInterfaceMockupsToTheHtmlPrototypeSkill() {
        Skill portuguese =
                registry.autoMatch("crie um mockup para a tela de login").orElseThrow();
        Skill english = registry.autoMatch("build a responsive ui mockup for the dashboard")
                .orElseThrow();

        assertEquals("prototype-interface", portuguese.name());
        assertEquals("prototype-interface", english.name());
        assertTrue(portuguese.body().contains("Nao chame `generate_image`"));
        assertTrue(portuguese.body().contains("miniatura recolhida"));
    }

    @Test
    void autoMatchFindsFaithfulTranslationForPortugueseAndEnglishRequests() {
        Skill portuguese = registry.autoMatch("traduza este texto para ingles: conteudo de teste")
                .orElseThrow();
        Skill english = registry.autoMatch("translate this text to Portuguese: test content")
                .orElseThrow();

        assertEquals("translate-content", portuguese.name());
        assertEquals("translate-content", english.name());
        assertTrue(portuguese.body().contains("Do not censor, soften, omit"));
        assertTrue(portuguese.body().contains("adult or explicit language"));
    }

    @Test
    void autoMatchIgnoresMessagesWithoutTriggerWords() {
        assertTrue(registry.autoMatch("cria uma pasta chamada back nesse repositorio")
                .isEmpty());
        assertTrue(registry.autoMatch("bom dia, tudo bem?").isEmpty());
    }

    @Test
    void returnsEmptyForAnUnknownSkillName() {
        assertTrue(registry.find("does-not-exist").isEmpty());
    }

    @Test
    void namesIncludesEveryLoadedSkill() {
        assertTrue(registry.names()
                .containsAll(Set.of(
                        "analyze-project",
                        "async-execution-diagnostics",
                        "avento-finalize-change",
                        "avento-frontend-maintenance",
                        "create-vite-project",
                        "database-migration",
                        "dependency-modernization",
                        "diagnose-project",
                        "docker-workflow",
                        "fix-project",
                        "generate-image",
                        "generate-video",
                        "git-workflow",
                        "inspect-database",
                        "java-project-maintenance",
                        "mac-workflow",
                        "manage-mcp",
                        "manage-memory",
                        "mcp-integration-maintenance",
                        "media-pipeline-maintenance",
                        "nestjs-project",
                        "rag-knowledge-maintenance",
                        "read-document",
                        "release-readiness",
                        "run-project",
                        "spring-security-maintenance",
                        "translate-content",
                        "voice-pipeline-maintenance",
                        "web-research")));
    }

    @Test
    void imageSkillStaysExplicitToPreserveItsDirectDetectorRoute() {
        // generate-image nao tem gatilhos de proposito: "gera uma imagem" ja e capturado pelo
        // detector direto (que pula o modelo). Auto-ativar a skill roubaria essa rota.
        assertTrue(registry.find("generate-image").orElseThrow().triggers().isEmpty());
        assertTrue(registry.find("media-pipeline-maintenance")
                .orElseThrow()
                .triggers()
                .isEmpty());
        assertTrue(registry.find("mac-workflow").orElseThrow().triggers().isEmpty());
    }

    @Test
    void mediaSkillsDeclareTheirToolForDeterministicRouting() {
        assertEquals(
                "generate_image", registry.find("generate-image").orElseThrow().tool());
        assertTrue(registry.find("generate-image").orElseThrow().declaresTool());

        Skill video = registry.find("generate-video").orElseThrow();
        assertEquals("generate_video", video.tool());
        assertTrue(video.declaresTool());
    }

    @Test
    void videoSkillGainsTriggersAndKeepsTheToolAndBodyOutOfHeaderLines() {
        Skill video = registry.find("generate-video").orElseThrow();

        assertTrue(video.triggers().contains("gera um video"));
        assertTrue(video.triggers().contains("anima essa imagem"));
        assertFalse(video.body().toLowerCase(java.util.Locale.ROOT).contains("gatilhos:"));
        assertFalse(video.body().toLowerCase(java.util.Locale.ROOT).contains("ferramenta:"));
        assertTrue(video.body().contains("generate_video com o argumento"));
    }

    @Test
    void skillsWithoutAToolHeaderReportNoTool() {
        assertFalse(registry.find("nestjs-project").orElseThrow().declaresTool());
        assertEquals("", registry.find("nestjs-project").orElseThrow().tool());
    }

    @Test
    void researchSkillParsesMultipleToolsAndARoundBudgetAboveTheGlobalCap() {
        Skill research = registry.find("research").orElseThrow();

        assertTrue(research.declaresTool());
        assertTrue(research.tools().contains("fetch"));
        assertTrue(research.tools().contains("browser_navigate"));
        assertTrue(research.tools().contains("browser_snapshot"));
        // MaxRodadas precisa ficar ACIMA do teto global (6), senão a pesquisa morre cedo.
        assertTrue(research.maxRounds() != null && research.maxRounds() > 6);
        // search_web nao existe como ferramenta e nao pode voltar a aparecer.
        assertFalse(research.tools().contains("search_web"));
        assertFalse(research.body().contains("search_web"));
    }

    @Test
    void loadsAsyncDiagnosticsWithTheDurableExecutionPath() {
        Skill skill = registry.find("async-execution-diagnostics").orElseThrow();

        assertTrue(skill.body().contains("agent_run_jobs"));
        assertTrue(skill.body().contains("avento:jobs:agent"));
        assertTrue(skill.body().contains("runId"));
    }

    @Test
    void normalizesAccentsInBuiltinTriggers() {
        Skill skill = registry.find("analyze-project").orElseThrow();

        assertTrue(skill.triggers().contains("diagnostico projeto"));
        assertFalse(skill.triggers().contains("diagnóstico projeto"));
    }

    @Test
    void autoMatchPrefersTheMostSpecificTrigger(@TempDir Path tempDir) throws Exception {
        SkillRegistry writable = new SkillRegistry(tempDir.toString());
        writable.saveCustomSkill(
                "complete-project-review",
                "Faz uma revisão completa",
                List.of("analisar projeto completo"),
                "Procedimento específico.");

        Skill matched =
                writable.autoMatch("quero analisar este projeto completo agora").orElseThrow();

        assertEquals("complete-project-review", matched.name());
    }

    @Test
    void savesAndReloadsACustomSkillFromDisk(@TempDir Path tempDir) throws Exception {
        SkillRegistry writable = new SkillRegistry(tempDir.toString());

        writable.saveCustomSkill(
                "deploy-front",
                "Faz o build e valida o frontend",
                List.of("Deploy do Front", "buildar frontend"),
                "Use terminal_run com npm run validate dentro de avento-web.");

        SkillRegistry reloaded = new SkillRegistry(tempDir.toString());
        Skill skill = reloaded.find("deploy-front").orElseThrow();
        assertEquals("Faz o build e valida o frontend", skill.description());
        assertTrue(skill.triggers().contains("deploy do front"));
        assertTrue(skill.body().contains("npm run validate"));
        assertFalse(skill.builtin());
    }

    @Test
    void deleteRemovesACustomSkillButRefusesBuiltinOnes(@TempDir Path tempDir) throws Exception {
        SkillRegistry writable = new SkillRegistry(tempDir.toString());
        writable.saveCustomSkill("temporaria", "Uma skill de teste", List.of(), "Corpo qualquer.");

        writable.deleteCustomSkill("temporaria");
        assertTrue(writable.find("temporaria").isEmpty());

        assertThrows(IllegalArgumentException.class, () -> writable.deleteCustomSkill("nestjs-project"));
        assertTrue(writable.find("nestjs-project").isPresent());
    }

    @Test
    void rejectsInvalidSkillNamesAndOverwritingBuiltins(@TempDir Path tempDir) {
        SkillRegistry writable = new SkillRegistry(tempDir.toString());

        assertThrows(
                IllegalArgumentException.class,
                () -> writable.saveCustomSkill("Nome Com Espaço", "desc", List.of(), "corpo"));
        assertThrows(
                IllegalArgumentException.class,
                () -> writable.saveCustomSkill("nestjs-project", "desc", List.of(), "corpo"));
    }
}

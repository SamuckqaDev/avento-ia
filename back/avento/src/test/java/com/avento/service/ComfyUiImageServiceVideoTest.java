package com.avento.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.service.dto.LocalModelInfo;
import com.avento.service.image.ImageGenerationOptions;
import com.avento.service.image.ImageModelPreset;
import com.avento.service.image.ImageModelPresetCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComfyUiImageServiceVideoTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ImageModelPresetCatalog presetCatalog = new ImageModelPresetCatalog(mapper, "");
    private final ComfyUiImageService service = new ComfyUiImageService(mapper, "http://127.0.0.1:8188", presetCatalog);

    @Test
    void videoWorkflowLoadsAndReceivesPromptDimensionsAndFrameCount() throws Exception {
        setField("videoWorkflowLocation", "classpath:comfyui/workflows/text-to-video-api.json");

        Method loadWorkflow = ComfyUiImageService.class.getDeclaredMethod("loadWorkflow", String.class);
        loadWorkflow.setAccessible(true);
        ObjectNode workflow =
                (ObjectNode) loadWorkflow.invoke(service, "classpath:comfyui/workflows/text-to-video-api.json");

        Method apply = ComfyUiImageService.class.getDeclaredMethod(
                "applyVideoWorkflowInputs", ObjectNode.class, String.class, int.class, int.class, int.class);
        apply.setAccessible(true);
        apply.invoke(service, workflow, "um gato correndo na chuva", 832, 480, 33);

        String rendered = workflow.toString();
        assertTrue(rendered.contains("um gato correndo na chuva"));
        assertTrue(!rendered.contains("__POSITIVE_PROMPT__"));
        assertTrue(!rendered.contains("__NEGATIVE_PROMPT__"));
        assertEquals(33, workflow.path("7").path("inputs").path("length").asInt());
        assertEquals(832, workflow.path("7").path("inputs").path("width").asInt());
        assertEquals(480, workflow.path("7").path("inputs").path("height").asInt());
        assertEquals(
                "wan2.2_ti2v_5B_fp16.safetensors",
                workflow.path("1").path("inputs").path("unet_name").asText());
        assertEquals(
                "umt5_xxl_fp8_e4m3fn_scaled.safetensors",
                workflow.path("2").path("inputs").path("clip_name").asText());
        assertEquals(
                "wan2.2_vae.safetensors",
                workflow.path("3").path("inputs").path("vae_name").asText());
        // O seed do KSampler precisa ter sido randomizado (deixamos 0 no arquivo).
        assertTrue(workflow.path("8").path("inputs").path("seed").asLong() != 0L);
    }

    @Test
    void frameCountFollowsTheWanFourNPlusOneRule() throws Exception {
        Method boundedFrames = ComfyUiImageService.class.getDeclaredMethod("boundedFrames", int.class);
        boundedFrames.setAccessible(true);

        // Clipes curtos usam pelo menos 33 quadros para o modelo manter coerência temporal.
        assertEquals(33, (int) boundedFrames.invoke(service, 2));
        // Pedido gigante trava no teto de 81 frames.
        assertEquals(81, (int) boundedFrames.invoke(service, 60));
        // Zero/negativo cai no padrão de 2 segundos.
        assertEquals(33, (int) boundedFrames.invoke(service, 0));
    }

    @Test
    void imageToVideoConnectsTheUploadedImageAsTheFirstFrame() throws Exception {
        ObjectNode workflow = loadVideoWorkflow();
        Method connect =
                ComfyUiImageService.class.getDeclaredMethod("connectVideoSourceImage", ObjectNode.class, String.class);
        connect.setAccessible(true);

        connect.invoke(service, workflow, "avento-video-source-test.png");

        assertEquals(
                "avento-video-source-test.png",
                workflow.path("avento_video_source")
                        .path("inputs")
                        .path("image")
                        .asText());
        assertEquals(
                "avento_video_source",
                workflow.path("7").path("inputs").path("start_image").path(0).asText());
    }

    @Test
    void animatedOutputCanBeReadFromComfyGifResults() throws Exception {
        ObjectNode outputs = mapper.createObjectNode();
        ObjectNode output = outputs.putObject("10");
        output.putArray("gifs")
                .addObject()
                .put("filename", "avento_video_00001_.webp")
                .put("subfolder", "")
                .put("type", "output");

        Method findImage = ComfyUiImageService.class.getDeclaredMethod("findImage", JsonNode.class);
        findImage.setAccessible(true);
        Object reference = findImage.invoke(service, outputs);

        assertNotNull(reference);
        assertTrue(reference.toString().contains("avento_video_00001_.webp"));
    }

    @Test
    void imageWorkflowAppliesHighResolutionRefinementSettings() throws Exception {
        setField("imageVae", "vae-ft-mse-840000-ema-pruned.safetensors");
        ObjectNode workflow = loadImageWorkflow();
        ImageGenerationOptions options = new ImageGenerationOptions(
                "quality", "portrait", 42L, 1, true, true, 0.32, "face-hands", 5.5, "", 0.75);

        applyImageWorkflow(workflow, options);

        assertEquals(512, workflow.path("4").path("inputs").path("width").asInt());
        assertEquals(768, workflow.path("4").path("inputs").path("height").asInt());
        assertEquals(592, workflow.path("6").path("inputs").path("width").asInt());
        assertEquals(896, workflow.path("6").path("inputs").path("height").asInt());
        assertEquals(8, workflow.path("7").path("inputs").path("steps").asInt());
        assertEquals(0.32, workflow.path("7").path("inputs").path("denoise").asDouble());
        assertEquals(5.5, workflow.path("7").path("inputs").path("cfg").asDouble());
        assertEquals(
                "vae-ft-mse-840000-ema-pruned.safetensors",
                workflow.path("8").path("inputs").path("vae_name").asText());
        assertEquals(
                "7", workflow.path("9").path("inputs").path("samples").path(0).asText());
    }

    @Test
    void imageWorkflowCanDisableTheSecondPass() throws Exception {
        ObjectNode workflow = loadImageWorkflow();
        ImageGenerationOptions options =
                new ImageGenerationOptions("draft", "square", null, 0, true, false, 0.30, "none", null, "", 0.75);

        applyImageWorkflow(workflow, options);

        assertFalse(workflow.has("6"));
        assertFalse(workflow.has("7"));
        assertEquals(
                "5", workflow.path("9").path("inputs").path("samples").path(0).asText());
    }

    @Test
    void sdxlWorkflowUsesNativeResolutionAndSdxlSamplingDefaults() throws Exception {
        setField("sdxlVae", "sdxl_vae.safetensors");
        ObjectNode workflow = loadSdxlWorkflow();
        ImageGenerationOptions options = new ImageGenerationOptions("quality", "portrait", 42L, 1, true);

        applyImageWorkflow(workflow, options, true, 768, 1024, "RealVisXL_V5.0_fp16.safetensors");

        assertEquals(
                "RealVisXL_V5.0_fp16.safetensors",
                workflow.path("1").path("inputs").path("ckpt_name").asText());
        assertEquals(768, workflow.path("4").path("inputs").path("width").asInt());
        assertEquals(1024, workflow.path("4").path("inputs").path("height").asInt());
        assertEquals(960, workflow.path("6").path("inputs").path("width").asInt());
        assertEquals(1280, workflow.path("6").path("inputs").path("height").asInt());
        assertEquals(32, workflow.path("5").path("inputs").path("steps").asInt());
        assertEquals(10, workflow.path("7").path("inputs").path("steps").asInt());
        assertEquals(4.8, workflow.path("5").path("inputs").path("cfg").asDouble());
        assertEquals(
                "sdxl_vae.safetensors",
                workflow.path("8").path("inputs").path("vae_name").asText());
    }

    @Test
    void flux2WorkflowUsesTheDistilledNativeSettings() throws Exception {
        setField("flux2TextEncoder", "qwen_3_4b.safetensors");
        setField("flux2Vae", "flux2-vae.safetensors");
        ObjectNode workflow = loadFlux2Workflow();
        Method apply = ComfyUiImageService.class.getDeclaredMethod(
                "applyFlux2WorkflowInputs",
                ObjectNode.class,
                String.class,
                String.class,
                int[].class,
                long.class,
                ImageGenerationOptions.class,
                ImageModelPreset.class);
        apply.setAccessible(true);

        apply.invoke(
                service,
                workflow,
                "(requested vehicle as the primary subject:1.35), red car in a parking lot",
                "flux-2-klein-4b-fp8.safetensors",
                new int[] {1024, 768},
                69L,
                ImageGenerationOptions.defaults(),
                presetCatalog.forModel("flux-2-klein-4b-fp8.safetensors"));

        assertEquals(
                "flux-2-klein-4b-fp8.safetensors",
                workflow.path("1").path("inputs").path("unet_name").asText());
        assertEquals(
                "qwen_3_4b.safetensors",
                workflow.path("2").path("inputs").path("clip_name").asText());
        assertEquals("flux2", workflow.path("2").path("inputs").path("type").asText());
        assertEquals(
                "flux2-vae.safetensors",
                workflow.path("3").path("inputs").path("vae_name").asText());
        assertEquals(
                "requested vehicle as the primary subject, red car in a parking lot",
                workflow.path("4").path("inputs").path("text").asText());
        assertEquals(1024, workflow.path("6").path("inputs").path("width").asInt());
        assertEquals(768, workflow.path("6").path("inputs").path("height").asInt());
        assertEquals(69L, workflow.path("7").path("inputs").path("noise_seed").asLong());
        assertEquals(4, workflow.path("9").path("inputs").path("steps").asInt());
        assertEquals(1.0, workflow.path("10").path("inputs").path("cfg").asDouble());
    }

    @Test
    void flux2ReceivesTheNaturalUserPromptInsteadOfPlannerTagSoup() throws Exception {
        setField("flux2WorkflowLocation", "classpath:comfyui/workflows/flux2-klein-text-to-image-api.json");
        setField("flux2TextEncoder", "qwen_3_4b.safetensors");
        setField("flux2Vae", "flux2-vae.safetensors");
        com.avento.service.image.ImagePromptPlan plan = new com.avento.service.image.ImagePromptPlan(
                "a red car in an empty parking lot, front view",
                "a red car in an empty parking lot, front view, exactly 1 subject, (vehicle as the main subject:1.3), no people",
                "low quality, person",
                0,
                false);
        Method prepare = ComfyUiImageService.class.getDeclaredMethod(
                "prepareImageWorkflow",
                com.avento.service.image.ImagePromptPlan.class,
                String.class,
                int[].class,
                ImageGenerationOptions.class,
                long.class,
                boolean.class,
                boolean.class,
                ImageModelPreset.class,
                List.class);
        prepare.setAccessible(true);

        Object prepared = prepare.invoke(
                service,
                plan,
                "flux-2-klein-4b.safetensors",
                new int[] {896, 896},
                ImageGenerationOptions.defaults(),
                7L,
                true,
                false,
                presetCatalog.forModel("flux-2-klein-4b.safetensors"),
                new java.util.ArrayList<String>());

        Method workflowAccessor = prepared.getClass().getDeclaredMethod("workflow");
        workflowAccessor.setAccessible(true);
        ObjectNode workflow = (ObjectNode) workflowAccessor.invoke(prepared);
        assertEquals(
                "a red car in an empty parking lot, front view",
                workflow.path("4").path("inputs").path("text").asText());
    }

    @Test
    void flux2QualityPresetsControlNativeResolution() {
        ImageModelPreset preset = presetCatalog.forModel("flux-2-klein-4b-fp8.safetensors");

        int[] draft = preset.dimensions("draft", "portrait");
        int[] balanced = preset.dimensions("balanced", "square");
        int[] quality = preset.dimensions("quality", "landscape");

        assertEquals(576, draft[0]);
        assertEquals(768, draft[1]);
        assertEquals(896, balanced[0]);
        assertEquals(896, balanced[1]);
        assertEquals(1024, quality[0]);
        assertEquals(768, quality[1]);
    }

    @Test
    @SuppressWarnings("unchecked")
    void diffusionModelCatalogIncludesOnlyTheSupportedFlux2Checkpoint() throws Exception {
        ArrayNode models =
                mapper.createArrayNode().add("wan2.2_ti2v_5B_fp16.safetensors").add("flux-2-klein-4b.safetensors");
        Method parse = ComfyUiImageService.class.getDeclaredMethod("parseModels", JsonNode.class, boolean.class);
        parse.setAccessible(true);

        List<LocalModelInfo> parsed = (List<LocalModelInfo>) parse.invoke(service, models, true);

        assertEquals(1, parsed.size());
        assertEquals("comfyui:flux-2-klein-4b.safetensors", parsed.get(0).name());
        assertTrue(parsed.get(0).heavy());
    }

    @Test
    void executionErrorsExposeTheFailingNodeAndRuntimeMessage() throws Exception {
        ObjectNode promptHistory = mapper.createObjectNode();
        ArrayNode messages = promptHistory.putObject("status").putArray("messages");
        ObjectNode error = mapper.createObjectNode();
        error.put("node_type", "SamplerCustomAdvanced");
        error.put("exception_type", "RuntimeError");
        error.put("exception_message", "Undefined type Float8_e4m3fn\n");
        messages.add(mapper.createArrayNode().add("execution_error").add(error));
        Method extract = ComfyUiImageService.class.getDeclaredMethod("extractExecutionError", JsonNode.class);
        extract.setAccessible(true);

        String details = (String) extract.invoke(service, promptHistory);

        assertEquals(
                "ComfyUI falhou no nó SamplerCustomAdvanced (RuntimeError): Undefined type Float8_e4m3fn", details);
    }

    @Test
    void imageWorkflowChainsFaceAndHandDetailersWhenAvailable() throws Exception {
        ObjectNode workflow = loadImageWorkflow();
        ImageGenerationOptions options = new ImageGenerationOptions(
                "quality", "portrait", 42L, 1, true, true, 0.30, "face-hands", 5.8, "", 0.75);
        applyImageWorkflow(workflow, options);
        ObjectNode objectInfo = mapper.createObjectNode();
        objectInfo.putObject("BboxDetectorSEGS");
        objectInfo.putObject("ImpactSEGSOrderedFilter");
        objectInfo.putObject("DetailerForEach");
        ObjectNode detectorInput = objectInfo
                .putObject("UltralyticsDetectorProvider")
                .putObject("input")
                .putObject("required");
        detectorInput
                .putArray("model_name")
                .addArray()
                .add("bbox/face_yolov8m.pt")
                .add("bbox/hand_yolov8s.pt");

        Method configureDetailing = ComfyUiImageService.class.getDeclaredMethod(
                "configureDetailing",
                ObjectNode.class,
                ImageGenerationOptions.class,
                JsonNode.class,
                long.class,
                boolean.class,
                int.class,
                List.class);
        configureDetailing.setAccessible(true);
        String applied = (String)
                configureDetailing.invoke(service, workflow, options, objectInfo, 42L, true, 1, new ArrayList<>());

        assertEquals("face-hands", applied);
        assertTrue(workflow.has("avento_face_detailer"));
        assertTrue(workflow.has("avento_hands_detailer"));
        assertEquals(
                8,
                workflow.path("avento_face_detailer")
                        .path("inputs")
                        .path("steps")
                        .asInt());
        assertEquals(
                768,
                workflow.path("avento_face_detailer")
                        .path("inputs")
                        .path("max_size")
                        .asInt());
        assertEquals(
                384,
                workflow.path("avento_face_detailer")
                        .path("inputs")
                        .path("guide_size")
                        .asInt());
        assertEquals(
                512,
                workflow.path("avento_hands_detailer")
                        .path("inputs")
                        .path("max_size")
                        .asInt());
        assertEquals(
                256,
                workflow.path("avento_hands_detailer")
                        .path("inputs")
                        .path("guide_size")
                        .asInt());
        assertEquals(
                0.68,
                workflow.path("avento_face_segments")
                        .path("inputs")
                        .path("threshold")
                        .asDouble());
        assertEquals(
                "confidence",
                workflow.path("avento_face_filter")
                        .path("inputs")
                        .path("target")
                        .asText());
        assertEquals(
                1,
                workflow.path("avento_face_filter")
                        .path("inputs")
                        .path("take_count")
                        .asInt());
        assertEquals(
                2,
                workflow.path("avento_hands_filter")
                        .path("inputs")
                        .path("take_count")
                        .asInt());
        assertEquals(
                "avento_face_filter",
                workflow.path("avento_face_detailer")
                        .path("inputs")
                        .path("segs")
                        .path(0)
                        .asText());
        assertEquals(
                0.30,
                workflow.path("avento_face_detailer")
                        .path("inputs")
                        .path("denoise")
                        .asDouble());
        assertEquals(
                "avento_hands_detailer",
                workflow.path("30").path("inputs").path("images").path(0).asText());
    }

    @Test
    void imageWorkflowSkipsPortraitDetailingForNonHumanScenes() throws Exception {
        ObjectNode workflow = loadImageWorkflow();
        ImageGenerationOptions options = ImageGenerationOptions.defaults();
        List<String> warnings = new ArrayList<>();
        Method configureDetailing = ComfyUiImageService.class.getDeclaredMethod(
                "configureDetailing",
                ObjectNode.class,
                ImageGenerationOptions.class,
                JsonNode.class,
                long.class,
                boolean.class,
                int.class,
                List.class);
        configureDetailing.setAccessible(true);

        String applied = (String) configureDetailing.invoke(
                service, workflow, options, mapper.createObjectNode(), 42L, false, 0, warnings);

        assertEquals("none", applied);
        assertFalse(warnings.isEmpty());
        assertFalse(workflow.has("avento_face_detailer"));
    }

    @Test
    void imageReferenceStrengthMapsToPredictableImg2ImgDenoise() throws Exception {
        Method referenceDenoise = ComfyUiImageService.class.getDeclaredMethod("referenceDenoise", double.class);
        referenceDenoise.setAccessible(true);

        assertEquals(0.35, (double) referenceDenoise.invoke(service, 0.65), 0.0001);
        assertEquals(0.15, (double) referenceDenoise.invoke(service, 0.95), 0.0001);
        assertEquals(0.85, (double) referenceDenoise.invoke(service, 0.05), 0.0001);
    }

    @Test
    void imageWorkflowPreservesPoseWhileReducingOtherPassesUnderSevereMemoryPressure() throws Exception {
        ImageGenerationOptions options = new ImageGenerationOptions(
                "quality",
                "portrait",
                42L,
                1,
                true,
                true,
                0.30,
                "face-hands",
                5.5,
                "",
                0.65,
                "data:image/png;base64,iVBORw0KGgo=",
                0.75,
                "vehicle");
        List<String> warnings = new ArrayList<>();
        Method applyMemoryBudget = ComfyUiImageService.class.getDeclaredMethod(
                "applyMemoryBudget", ImageGenerationOptions.class, long.class, List.class);
        applyMemoryBudget.setAccessible(true);

        ImageGenerationOptions adjusted =
                (ImageGenerationOptions) applyMemoryBudget.invoke(service, options, 900L * 1024 * 1024, warnings);

        assertEquals("draft", adjusted.qualityPreset());
        assertFalse(adjusted.refinementEnabled());
        assertEquals("none", adjusted.detailMode());
        assertEquals("vehicle", adjusted.subjectType());
        assertTrue(adjusted.hasPoseReference());
        assertFalse(warnings.isEmpty());
    }

    @Test
    void imageWorkflowDropsHandDetailerUnderModerateMemoryPressure() throws Exception {
        ImageGenerationOptions options = new ImageGenerationOptions(
                "quality", "portrait", 42L, 1, true, true, 0.30, "face-hands", 5.5, "", 0.75);
        List<String> warnings = new ArrayList<>();
        Method applyMemoryBudget = ComfyUiImageService.class.getDeclaredMethod(
                "applyMemoryBudget", ImageGenerationOptions.class, long.class, List.class);
        applyMemoryBudget.setAccessible(true);

        ImageGenerationOptions adjusted =
                (ImageGenerationOptions) applyMemoryBudget.invoke(service, options, 3L * 1024 * 1024 * 1024, warnings);

        assertEquals("quality", adjusted.qualityPreset());
        assertTrue(adjusted.refinementEnabled());
        assertEquals("face", adjusted.detailMode());
        assertFalse(warnings.isEmpty());
    }

    private ObjectNode loadImageWorkflow() throws Exception {
        Method loadWorkflow = ComfyUiImageService.class.getDeclaredMethod("loadWorkflow", String.class);
        loadWorkflow.setAccessible(true);
        return (ObjectNode) loadWorkflow.invoke(service, "classpath:comfyui/workflows/text-to-image-api.json");
    }

    private ObjectNode loadVideoWorkflow() throws Exception {
        Method loadWorkflow = ComfyUiImageService.class.getDeclaredMethod("loadWorkflow", String.class);
        loadWorkflow.setAccessible(true);
        return (ObjectNode) loadWorkflow.invoke(service, "classpath:comfyui/workflows/text-to-video-api.json");
    }

    private ObjectNode loadFlux2Workflow() throws Exception {
        Method loadWorkflow = ComfyUiImageService.class.getDeclaredMethod("loadWorkflow", String.class);
        loadWorkflow.setAccessible(true);
        return (ObjectNode)
                loadWorkflow.invoke(service, "classpath:comfyui/workflows/flux2-klein-text-to-image-api.json");
    }

    private ObjectNode loadSdxlWorkflow() throws Exception {
        Method loadWorkflow = ComfyUiImageService.class.getDeclaredMethod("loadWorkflow", String.class);
        loadWorkflow.setAccessible(true);
        return (ObjectNode) loadWorkflow.invoke(service, "classpath:comfyui/workflows/sdxl-text-to-image-api.json");
    }

    private void applyImageWorkflow(ObjectNode workflow, ImageGenerationOptions options) throws Exception {
        applyImageWorkflow(workflow, options, false, 512, 768, "Realistic_Vision_V6.0_NV_B1_fp16.safetensors");
    }

    private void applyImageWorkflow(
            ObjectNode workflow, ImageGenerationOptions options, boolean sdxl, int width, int height, String model)
            throws Exception {
        Method apply = ComfyUiImageService.class.getDeclaredMethod(
                "applyWorkflowInputs",
                ObjectNode.class,
                String.class,
                String.class,
                String.class,
                int.class,
                int.class,
                ImageGenerationOptions.class,
                long.class,
                boolean.class,
                ImageModelPreset.class);
        apply.setAccessible(true);
        apply.invoke(
                service,
                workflow,
                "photorealistic person",
                "malformed anatomy",
                model,
                width,
                height,
                options,
                42L,
                sdxl,
                presetCatalog.forModel(model));
    }

    private void setField(String name, Object value) throws Exception {
        Field field = ComfyUiImageService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}

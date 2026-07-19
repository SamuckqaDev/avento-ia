package com.avento.service.image;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Map;

public record ImageGenerationOptions(
        String qualityPreset,
        String aspectRatio,
        Long seed,
        int subjectCount,
        boolean enhancePrompt,
        boolean refinementEnabled,
        double refinementStrength,
        String detailMode,
        Double cfgScale,
        String referenceImageDataUrl,
        double referenceStrength,
        String poseReferenceDataUrl,
        double poseStrength,
        String subjectType,
        String referenceMode,
        String structureControl,
        double structureStrength,
        boolean adherenceValidationEnabled,
        int maxAdherenceRetries) {

    private static final int MAX_IMAGE_DATA_URL_CHARS = 8_500_000;

    public ImageGenerationOptions {
        qualityPreset = normalizeQuality(qualityPreset);
        aspectRatio = normalizeAspectRatio(aspectRatio);
        seed = seed == null || seed < 0 ? null : seed;
        subjectCount = Math.max(0, Math.min(6, subjectCount));
        refinementStrength = clamp(refinementStrength, 0.15, 0.55);
        detailMode = normalizeDetailMode(detailMode);
        cfgScale = cfgScale == null ? null : clamp(cfgScale, 1.0, 12.0);
        referenceImageDataUrl = normalizeImageReference(referenceImageDataUrl);
        referenceStrength = clamp(referenceStrength, 0.1, 0.9);
        poseReferenceDataUrl = normalizeImageReference(poseReferenceDataUrl);
        poseStrength = clamp(poseStrength, 0.2, 1.5);
        subjectType = normalizeSubjectType(subjectType);
        referenceMode = normalizeReferenceMode(referenceMode);
        structureControl = normalizeStructureControl(structureControl);
        structureStrength = clamp(structureStrength, 0.2, 1.5);
        maxAdherenceRetries = Math.max(0, Math.min(2, maxAdherenceRetries));
    }

    public ImageGenerationOptions(
            String qualityPreset,
            String aspectRatio,
            Long seed,
            int subjectCount,
            boolean enhancePrompt,
            boolean refinementEnabled,
            double refinementStrength,
            String detailMode,
            Double cfgScale,
            String referenceImageDataUrl,
            double referenceStrength,
            String poseReferenceDataUrl,
            double poseStrength,
            String subjectType) {
        this(
                qualityPreset,
                aspectRatio,
                seed,
                subjectCount,
                enhancePrompt,
                refinementEnabled,
                refinementStrength,
                detailMode,
                cfgScale,
                referenceImageDataUrl,
                referenceStrength,
                poseReferenceDataUrl,
                poseStrength,
                subjectType,
                "composition",
                "depth",
                0.75,
                true,
                1);
    }

    public ImageGenerationOptions(
            String qualityPreset,
            String aspectRatio,
            Long seed,
            int subjectCount,
            boolean enhancePrompt,
            boolean refinementEnabled,
            double refinementStrength,
            String detailMode,
            Double cfgScale,
            String referenceImageDataUrl,
            double referenceStrength,
            String poseReferenceDataUrl,
            double poseStrength) {
        this(
                qualityPreset,
                aspectRatio,
                seed,
                subjectCount,
                enhancePrompt,
                refinementEnabled,
                refinementStrength,
                detailMode,
                cfgScale,
                referenceImageDataUrl,
                referenceStrength,
                poseReferenceDataUrl,
                poseStrength,
                "auto");
    }

    public ImageGenerationOptions(
            String qualityPreset,
            String aspectRatio,
            Long seed,
            int subjectCount,
            boolean enhancePrompt,
            boolean refinementEnabled,
            double refinementStrength,
            String detailMode,
            Double cfgScale,
            String poseReferenceDataUrl,
            double poseStrength) {
        this(
                qualityPreset,
                aspectRatio,
                seed,
                subjectCount,
                enhancePrompt,
                refinementEnabled,
                refinementStrength,
                detailMode,
                cfgScale,
                "",
                0.65,
                poseReferenceDataUrl,
                poseStrength,
                "auto");
    }

    public ImageGenerationOptions(
            String qualityPreset, String aspectRatio, Long seed, int subjectCount, boolean enhancePrompt) {
        this(
                qualityPreset,
                aspectRatio,
                seed,
                subjectCount,
                enhancePrompt,
                true,
                0.30,
                "face",
                null,
                "",
                0.65,
                "",
                0.75,
                "auto");
    }

    public ImageGenerationOptions(
            String qualityPreset,
            String aspectRatio,
            String subjectType,
            Long seed,
            int subjectCount,
            boolean enhancePrompt) {
        this(
                qualityPreset,
                aspectRatio,
                seed,
                subjectCount,
                enhancePrompt,
                true,
                0.30,
                "face",
                null,
                "",
                0.65,
                "",
                0.75,
                subjectType);
    }

    public static ImageGenerationOptions defaults() {
        return new ImageGenerationOptions("balanced", "square", null, 0, true);
    }

    public static ImageGenerationOptions from(JsonNode node) {
        if (node == null || !node.isObject()) {
            return defaults();
        }
        Long seed = node.path("seed").canConvertToLong() ? node.path("seed").asLong() : null;
        return new ImageGenerationOptions(
                node.path("qualityPreset").asText("balanced"),
                node.path("aspectRatio").asText("square"),
                seed,
                node.path("subjectCount").asInt(0),
                node.path("enhancePrompt").asBoolean(true),
                node.path("refinementEnabled").asBoolean(true),
                node.path("refinementStrength").asDouble(0.30),
                node.path("detailMode").asText("face"),
                node.path("cfgScale").isNumber() ? node.path("cfgScale").asDouble() : null,
                node.path("referenceImageDataUrl").asText(""),
                node.path("referenceStrength").asDouble(0.65),
                node.path("poseReferenceDataUrl").asText(""),
                node.path("poseStrength").asDouble(0.75),
                node.path("subjectType").asText("auto"),
                node.path("referenceMode").asText("composition"),
                node.path("structureControl").asText("depth"),
                node.path("structureStrength").asDouble(0.75),
                node.path("adherenceValidationEnabled").asBoolean(true),
                node.path("maxAdherenceRetries").asInt(1));
    }

    public static ImageGenerationOptions from(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return defaults();
        }
        return new ImageGenerationOptions(
                stringValue(values.get("qualityPreset"), "balanced"),
                stringValue(values.get("aspectRatio"), "square"),
                longValue(values.get("seed")),
                intValue(values.get("subjectCount"), 0),
                booleanValue(values.get("enhancePrompt"), true),
                booleanValue(values.get("refinementEnabled"), true),
                doubleValue(values.get("refinementStrength"), 0.30),
                stringValue(values.get("detailMode"), "face"),
                nullableDoubleValue(values.get("cfgScale")),
                stringValue(values.get("referenceImageDataUrl"), ""),
                doubleValue(values.get("referenceStrength"), 0.65),
                stringValue(values.get("poseReferenceDataUrl"), ""),
                doubleValue(values.get("poseStrength"), 0.75),
                stringValue(values.get("subjectType"), "auto"),
                stringValue(values.get("referenceMode"), "composition"),
                stringValue(values.get("structureControl"), "depth"),
                doubleValue(values.get("structureStrength"), 0.75),
                booleanValue(values.get("adherenceValidationEnabled"), true),
                intValue(values.get("maxAdherenceRetries"), 1));
    }

    public String size() {
        return switch (aspectRatio) {
            case "portrait" -> "512x768";
            case "landscape" -> "768x512";
            default -> "512x512";
        };
    }

    public int steps() {
        return switch (qualityPreset) {
            case "draft" -> 14;
            case "quality" -> 24;
            default -> 20;
        };
    }

    public double cfg() {
        if (cfgScale != null) {
            return cfgScale;
        }
        return switch (qualityPreset) {
            case "draft" -> 6.5;
            case "quality" -> 5.8;
            default -> 6.0;
        };
    }

    public int refinementSteps() {
        return switch (qualityPreset) {
            case "draft" -> 4;
            case "quality" -> 8;
            default -> 6;
        };
    }

    public int detailerSteps() {
        return switch (qualityPreset) {
            case "draft" -> 4;
            case "quality" -> 8;
            default -> 6;
        };
    }

    public boolean hasPoseReference() {
        return !poseReferenceDataUrl.isBlank();
    }

    public boolean hasReferenceImage() {
        return !referenceImageDataUrl.isBlank();
    }

    public boolean usesImg2ImgReference() {
        return hasReferenceImage() && "transform".equals(referenceMode);
    }

    public boolean usesIdentityReference() {
        return hasReferenceImage() && "identity".equals(referenceMode);
    }

    public boolean usesStructureReference() {
        return hasReferenceImage() && "composition".equals(referenceMode) && !"none".equals(structureControl);
    }

    public boolean detailsFace() {
        return "face".equals(detailMode) || "face-hands".equals(detailMode);
    }

    public boolean detailsHands() {
        return "face-hands".equals(detailMode);
    }

    private static String normalizeQuality(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "draft", "quality" -> normalized;
            default -> "balanced";
        };
    }

    private static String normalizeAspectRatio(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "portrait", "landscape" -> normalized;
            default -> "square";
        };
    }

    private static String normalizeDetailMode(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "none", "face-hands" -> normalized;
            default -> "face";
        };
    }

    private static String normalizeSubjectType(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "person", "object", "environment", "vehicle", "animal" -> normalized;
            default -> "auto";
        };
    }

    private static String normalizeReferenceMode(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "transform", "identity" -> normalized;
            default -> "composition";
        };
    }

    private static String normalizeStructureControl(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "none", "canny" -> normalized;
            default -> "depth";
        };
    }

    private static String normalizeImageReference(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_IMAGE_DATA_URL_CHARS) {
            return "";
        }
        String normalized = value.trim();
        return normalized.matches("(?is)^data:image/(png|jpe?g|webp);base64,[a-z0-9+/=\\r\\n]+$") ? normalized : "";
    }

    private static String stringValue(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static double doubleValue(Object value, double fallback) {
        Double parsed = nullableDoubleValue(value);
        return parsed == null ? fallback : parsed;
    }

    private static Double nullableDoubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) {
            return minimum;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}

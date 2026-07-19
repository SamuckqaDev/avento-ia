package com.avento.service.image;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generation settings tuned for a family of checkpoints. Values are keyed by the
 * quality preset ("draft", "balanced", "quality"); missing tiers fall back to
 * "balanced" and then to a safe default. An empty {@code longEdge} map means the
 * model uses the legacy 512-based size derived from the aspect ratio.
 */
public record ImageModelPreset(
        String name,
        List<String> match,
        String sampler,
        String scheduler,
        Map<String, Integer> steps,
        Map<String, Integer> refinementSteps,
        Map<String, Double> cfg,
        Map<String, Integer> longEdge,
        String promptStyle) {

    public ImageModelPreset {
        match = match == null ? List.of() : List.copyOf(match);
        sampler = sampler == null ? "" : sampler.trim();
        scheduler = scheduler == null ? "" : scheduler.trim();
        steps = steps == null ? Map.of() : Map.copyOf(steps);
        refinementSteps = refinementSteps == null ? Map.of() : Map.copyOf(refinementSteps);
        cfg = cfg == null ? Map.of() : Map.copyOf(cfg);
        longEdge = longEdge == null ? Map.of() : Map.copyOf(longEdge);
        promptStyle = "natural".equalsIgnoreCase(promptStyle == null ? "" : promptStyle.trim()) ? "natural" : "tags";
    }

    /**
     * "tags": the model uses a CLIP-style encoder and receives the planner's weighted
     * keyword prompt. "natural": the model uses an LLM text encoder (FLUX.2/qwen) that
     * follows plain sentences and treats keyword soup as noise — it receives the user's
     * (translated) request as-is.
     */
    public boolean usesNaturalPrompt() {
        return "natural".equals(promptStyle);
    }

    public boolean matches(String normalizedModel) {
        for (String candidate : match) {
            String normalized = candidate == null ? "" : candidate.trim().toLowerCase(Locale.ROOT);
            if ("*".equals(normalized) || (!normalized.isBlank() && normalizedModel.contains(normalized))) {
                return true;
            }
        }
        return false;
    }

    public int steps(String qualityPreset) {
        return intTier(steps, qualityPreset, 20);
    }

    public int refinementSteps(String qualityPreset) {
        return intTier(refinementSteps, qualityPreset, 6);
    }

    public double cfg(String qualityPreset) {
        Double exact = cfg.get(qualityPreset);
        if (exact != null) {
            return exact;
        }
        Double balanced = cfg.get("balanced");
        return balanced != null ? balanced : 6.0;
    }

    public boolean hasNativeDimensions() {
        return !longEdge.isEmpty();
    }

    public int[] dimensions(String qualityPreset, String aspectRatio) {
        int edge = intTier(longEdge, qualityPreset, 896);
        return switch (aspectRatio) {
            case "portrait" -> new int[] {edge * 3 / 4, edge};
            case "landscape" -> new int[] {edge, edge * 3 / 4};
            default -> new int[] {edge, edge};
        };
    }

    public boolean hasSampler() {
        return !sampler.isBlank();
    }

    public boolean hasScheduler() {
        return !scheduler.isBlank();
    }

    private static int intTier(Map<String, Integer> tiers, String tier, int fallback) {
        Integer exact = tiers.get(tier);
        if (exact != null) {
            return exact;
        }
        Integer balanced = tiers.get("balanced");
        return balanced != null ? balanced : fallback;
    }
}

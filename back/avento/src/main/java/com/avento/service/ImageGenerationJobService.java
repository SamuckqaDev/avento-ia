package com.avento.service;

import com.avento.model.ImageGenerationJob;
import com.avento.model.ImageGenerationJob.Status;
import com.avento.repository.ImageGenerationJobRepository;
import com.avento.service.dto.*;
import com.avento.service.image.ImageGenerationJobWorker;
import com.avento.service.image.ImageGenerationOptions;
import com.avento.service.image.ImageGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ImageGenerationJobService {

    private static final Logger logger = LoggerFactory.getLogger(ImageGenerationJobService.class);
    private static final Set<Status> RECOVERABLE_STATUSES =
            Set.of(Status.QUEUED, Status.PREPARING, Status.GENERATING, Status.SAVING);

    private final ImageGenerationJobRepository repository;
    private final ImageGenerator imageGenerator;
    private final ObjectMapper mapper;
    private final ImageGenerationJobWorker worker;

    public ObjectNode enqueue(Map<String, Object> payload, Long chatId, UUID userId) {
        if (chatId == null || userId == null) {
            throw new IllegalArgumentException("A geração de imagem precisa estar vinculada a um chat autenticado.");
        }

        ImageGenerationOptions options = ImageGenerationOptions.from(payload);
        ImageGenerationJob job = new ImageGenerationJob();
        job.setId(UUID.randomUUID());
        job.setChatId(chatId);
        job.setUserId(userId);
        job.setPrompt(requiredString(payload, "prompt").trim());
        job.setModel(imageGenerator.resolveModel(payload));
        job.setSize(stringValue(payload.get("size"), options.size()));
        job.setRequestPayload(writePayload(payload));
        job.setStatus(Status.QUEUED);
        job.setStage("Na fila");
        job.setProgress(0);
        job.setEstimatedTotalSeconds(estimateTotalSeconds(options));
        job.setEstimatedRemainingSeconds(job.getEstimatedTotalSeconds());
        repository.saveAndFlush(job);
        worker.submit(job.getId());

        ObjectNode result = mapper.createObjectNode();
        result.put("status", "queued");
        result.put("provider", "local-image-job");
        result.put("jobId", job.getId().toString());
        result.put("chatId", chatId);
        result.put("message", "Imagem enfileirada. O progresso aparecerá no chat.");
        return result;
    }

    public ImageJobView getOwnedJob(UUID jobId, UUID userId) {
        return toView(findOwned(jobId, userId));
    }

    public List<ImageJobView> getChatJobs(Long chatId, UUID userId) {
        return repository.findByChatIdAndUserId(chatId, userId).stream()
                .map(this::toView)
                .toList();
    }

    public ImageJobView cancel(UUID jobId, UUID userId) {
        ImageGenerationJob job = findOwned(jobId, userId);
        if (job.getStatus().isTerminal()) {
            return toView(job);
        }
        job.setCancellationRequested(true);
        job.setStatus(Status.CANCELLED);
        job.setStage("Cancelado");
        job.setEstimatedRemainingSeconds(0L);
        job.setCompletedAt(LocalDateTime.now());
        repository.saveAndFlush(job);
        worker.cancel(job);
        return toView(job);
    }

    public JobDeletionResult deleteForChat(Long chatId, UUID userId) {
        List<ImageGenerationJob> jobs = repository.findByChatIdAndUserId(chatId, userId);
        int deletedFiles = 0;
        for (ImageGenerationJob job : jobs) {
            if (worker.delete(job)) {
                deletedFiles++;
            }
        }
        repository.deleteAllInBatch(jobs);
        return new JobDeletionResult(jobs.size(), deletedFiles);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverIncompleteJobs() {
        List<ImageGenerationJob> jobs = repository.findByStatusIn(RECOVERABLE_STATUSES);
        if (jobs.isEmpty()) {
            return;
        }
        logger.info("Recovering {} unfinished image generation job(s)", jobs.size());
        jobs.forEach(job -> {
            job.setStatus(Status.QUEUED);
            job.setStage("Retomando após reinício");
            job.setProgress(0);
            repository.saveAndFlush(job);
            worker.submit(job.getId());
        });
    }

    private ImageJobView toView(ImageGenerationJob job) {
        long elapsed = elapsedSeconds(job);
        int progress = estimatedProgress(job, elapsed);
        Long remaining = job.getStatus().isTerminal()
                ? 0L
                : Math.max(1L, Math.max(job.getEstimatedTotalSeconds(), elapsed + 1L) - elapsed);
        String mediaUrl = job.getStatus() == Status.COMPLETED && job.getOutputPath() != null
                ? "/api/media/" + Path.of(job.getOutputPath()).getFileName()
                : "";
        return new ImageJobView(
                job.getId(),
                job.getChatId(),
                job.getStatus().name().toLowerCase(),
                job.getStage(),
                progress,
                remaining,
                elapsed,
                job.getPrompt(),
                job.getModel(),
                job.getSize(),
                mediaUrl,
                job.getError(),
                job.getCreatedAt(),
                job.getCompletedAt());
    }

    private int estimatedProgress(ImageGenerationJob job, long elapsed) {
        if (job.getStatus() == Status.COMPLETED) {
            return 100;
        }
        if (job.getStatus().isTerminal() || job.getStatus() == Status.QUEUED || job.getStatus() == Status.PREPARING) {
            return job.getProgress();
        }
        long total = Math.max(30L, job.getEstimatedTotalSeconds());
        return (int) Math.min(95L, Math.max(job.getProgress(), 5L + elapsed * 90L / total));
    }

    private long elapsedSeconds(ImageGenerationJob job) {
        LocalDateTime startedAt = job.getStartedAt() == null ? job.getCreatedAt() : job.getStartedAt();
        return startedAt == null
                ? 0L
                : Math.max(0L, Duration.between(startedAt, LocalDateTime.now()).toSeconds());
    }

    private long estimateTotalSeconds(ImageGenerationOptions options) {
        long base =
                switch (options.qualityPreset()) {
                    case "draft" -> 75L;
                    case "quality" -> 210L;
                    default -> 130L;
                };
        if (options.refinementEnabled()) {
            base += 45L;
        }
        if (options.adherenceValidationEnabled()) {
            base *= options.maxAdherenceRetries() + 1L;
            base += 30L;
        }
        return Math.min(1_800L, base);
    }

    private ImageGenerationJob findOwned(UUID jobId, UUID userId) {
        return repository.findByIdAndUserId(jobId, userId).orElseThrow(() -> new ImageJobNotFoundException(jobId));
    }

    private String writePayload(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Não foi possível preparar os parâmetros da imagem.", exception);
        }
    }

    private String requiredString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return text;
    }

    private String stringValue(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    public static class ImageJobNotFoundException extends RuntimeException {
        public ImageJobNotFoundException(UUID id) {
            super("Image generation job not found: " + id);
        }
    }
}

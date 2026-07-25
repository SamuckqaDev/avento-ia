package com.avento.service;

import com.avento.model.VideoGenerationJob;
import com.avento.model.VideoGenerationJob.Status;
import com.avento.repository.VideoGenerationJobRepository;
import com.avento.service.dto.*;
import com.avento.service.dto.VideoStatus;
import com.avento.service.dto.VideoSubmission;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class VideoGenerationJobService {

    private static final Logger logger = LoggerFactory.getLogger(VideoGenerationJobService.class);
    private static final Set<Status> RECOVERABLE_STATUSES =
            Set.of(Status.QUEUED, Status.SUBMITTING, Status.GENERATING, Status.SAVING);
    private static final long POLL_INTERVAL_MILLIS = 2_000L;

    private final VideoGenerationJobRepository repository;
    private final ComfyUiImageService comfyUiImageService;
    private final ObjectMapper mapper;
    private final Executor executor;
    private final Path mediaDirectory;
    private final GeneratedMediaAssetService generatedMediaAssetService;
    private final Set<UUID> deletedJobs = ConcurrentHashMap.newKeySet();

    public VideoGenerationJobService(
            VideoGenerationJobRepository repository,
            ComfyUiImageService comfyUiImageService,
            ObjectMapper mapper,
            @Qualifier("videoGenerationExecutor") Executor executor,
            @Value("${avento.media.directory:}") String configuredMediaDirectory) {
        this(repository, comfyUiImageService, mapper, executor, configuredMediaDirectory, null);
    }

    @Autowired
    public VideoGenerationJobService(
            VideoGenerationJobRepository repository,
            ComfyUiImageService comfyUiImageService,
            ObjectMapper mapper,
            @Qualifier("videoGenerationExecutor") Executor executor,
            @Value("${avento.media.directory:}") String configuredMediaDirectory,
            GeneratedMediaAssetService generatedMediaAssetService) {
        this.repository = repository;
        this.comfyUiImageService = comfyUiImageService;
        this.mapper = mapper;
        this.executor = executor;
        this.generatedMediaAssetService = generatedMediaAssetService;
        this.mediaDirectory = configuredMediaDirectory == null || configuredMediaDirectory.isBlank()
                ? Paths.get(System.getProperty("user.home"), "Pictures", "Avento Generated Images")
                        .toAbsolutePath()
                        .normalize()
                : Paths.get(configuredMediaDirectory).toAbsolutePath().normalize();
    }

    public ObjectNode enqueue(String prompt, String size, int seconds, Long chatId, UUID userId) {
        return enqueue(prompt, size, seconds, chatId, userId, null);
    }

    public ObjectNode enqueue(String prompt, String size, int seconds, Long chatId, UUID userId, Path sourceImagePath) {
        if (chatId == null || userId == null) {
            throw new IllegalArgumentException("A geração de vídeo precisa estar vinculada a um chat autenticado.");
        }

        VideoGenerationJob job = new VideoGenerationJob();
        job.setId(UUID.randomUUID());
        job.setChatId(chatId);
        job.setUserId(userId);
        job.setPrompt(prompt);
        if (sourceImagePath != null) {
            job.setSourceImagePath(sourceImagePath.toAbsolutePath().normalize().toString());
        }
        job.setStatus(Status.QUEUED);
        job.setStage("Na fila");
        job.setProgress(0);
        int[] dimensions = requestedDimensions(size, sourceImagePath);
        job.setWidth(dimensions[0]);
        job.setHeight(dimensions[1]);
        job.setFps(16);
        int requestedFrames = Math.max(33, Math.min(81, Math.max(1, seconds) * 16));
        job.setFrames(requestedFrames - ((requestedFrames - 1) % 4));
        repository.saveAndFlush(job);
        submitWorker(job.getId());

        ObjectNode result = mapper.createObjectNode();
        result.put("status", "queued");
        result.put("provider", "comfyui");
        result.put("jobId", job.getId().toString());
        result.put("chatId", chatId);
        result.put("mode", sourceImagePath == null ? "text-to-video" : "image-to-video");
        result.put("message", "Vídeo enfileirado. O progresso aparecerá no chat.");
        return result;
    }

    public VideoJobView getOwnedJob(UUID jobId, UUID userId) {
        return toView(findOwned(jobId, userId));
    }

    public List<VideoJobView> getChatJobs(Long chatId, UUID userId) {
        return repository.findByChatIdAndUserId(chatId, userId).stream()
                .map(this::toView)
                .toList();
    }

    public VideoJobView cancel(UUID jobId, UUID userId) {
        VideoGenerationJob job = findOwned(jobId, userId);
        if (job.getStatus().isTerminal()) {
            return toView(job);
        }
        job.setCancellationRequested(true);
        job.setStatus(Status.CANCELLED);
        job.setStage("Cancelado");
        job.setEstimatedRemainingSeconds(0L);
        job.setCompletedAt(LocalDateTime.now());
        repository.saveAndFlush(job);
        cancelComfyPrompt(job);
        return toView(job);
    }

    public JobDeletionResult deleteForChat(Long chatId, UUID userId) {
        List<VideoGenerationJob> jobs = repository.findByChatIdAndUserId(chatId, userId);
        int deletedFiles = 0;
        for (VideoGenerationJob job : jobs) {
            deletedJobs.add(job.getId());
            if (!job.getStatus().isTerminal()) {
                cancelComfyPrompt(job);
            }
            if (deleteOutput(job.getOutputPath())) {
                deletedFiles++;
            }
        }
        repository.deleteAllInBatch(jobs);
        return new JobDeletionResult(jobs.size(), deletedFiles);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverIncompleteJobs() {
        List<VideoGenerationJob> jobs = repository.findByStatusIn(RECOVERABLE_STATUSES);
        if (!jobs.isEmpty()) {
            logger.info("Recovering {} unfinished video generation job(s)", jobs.size());
            jobs.forEach(job -> submitWorker(job.getId()));
        }
    }

    private void submitWorker(UUID jobId) {
        executor.execute(() -> process(jobId));
    }

    private void process(UUID jobId) {
        try {
            VideoGenerationJob job = repository.findById(jobId).orElse(null);
            if (job == null || shouldStop(job)) {
                return;
            }

            if (job.getComfyPromptId() == null || job.getComfyPromptId().isBlank()) {
                job.setStatus(Status.SUBMITTING);
                job.setStage("Preparando o workflow");
                job.setProgress(1);
                job.setStartedAt(LocalDateTime.now());
                repository.saveAndFlush(job);

                Path sourceImage = job.getSourceImagePath() == null
                                || job.getSourceImagePath().isBlank()
                        ? null
                        : Path.of(job.getSourceImagePath());
                VideoSubmission submission = comfyUiImageService.submitVideo(
                        job.getPrompt(), requestedSize(job), requestedSeconds(job), sourceImage);
                if (deletedJobs.contains(jobId)) {
                    comfyUiImageService.cancelVideo(submission.promptId());
                    return;
                }
                job = repository.findById(jobId).orElse(null);
                if (job == null || shouldStop(job)) {
                    comfyUiImageService.cancelVideo(submission.promptId());
                    return;
                }
                applySubmission(job, submission);
                repository.saveAndFlush(job);
            }

            monitor(jobId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(jobId, "A geração de vídeo foi interrompida.");
        } catch (Exception exception) {
            logger.error("Video generation job {} failed", jobId, exception);
            fail(jobId, readableError(exception));
        }
    }

    private void monitor(UUID jobId) throws IOException, InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            VideoGenerationJob job = repository.findById(jobId).orElse(null);
            if (job == null || deletedJobs.contains(jobId)) {
                return;
            }
            if (job.isCancellationRequested() || job.getStatus() == Status.CANCELLED) {
                cancelComfyPrompt(job);
                return;
            }

            VideoStatus comfyStatus = comfyUiImageService.inspectVideo(job.getComfyPromptId());
            if ("completed".equals(comfyStatus.status())) {
                complete(job, comfyStatus);
                return;
            }
            if ("failed".equals(comfyStatus.status())) {
                fail(jobId, comfyStatus.error().isBlank() ? "O ComfyUI não concluiu o vídeo." : comfyStatus.error());
                return;
            }
            if ("missing".equals(comfyStatus.status())) {
                fail(jobId, "O ComfyUI não encontrou mais esse job na fila nem no histórico.");
                return;
            }

            updateEstimate(job, "queued".equals(comfyStatus.status()));
            repository.saveAndFlush(job);
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
    }

    private void complete(VideoGenerationJob job, VideoStatus comfyStatus) throws IOException, InterruptedException {
        if (deletedJobs.contains(job.getId())) {
            return;
        }
        job.setStatus(Status.SAVING);
        job.setStage("Salvando o vídeo");
        job.setProgress(97);
        job.setEstimatedRemainingSeconds(2L);
        repository.saveAndFlush(job);

        Path outputPath = comfyUiImageService.saveVideoOutput(comfyStatus.output());
        VideoGenerationJob latest = repository.findById(job.getId()).orElse(null);
        if (deletedJobs.contains(job.getId())
                || latest == null
                || latest.isCancellationRequested()
                || latest.getStatus() == Status.CANCELLED) {
            Files.deleteIfExists(outputPath);
            return;
        }

        latest.setOutputPath(outputPath.toString());
        latest.setStatus(Status.COMPLETED);
        latest.setStage("Concluído");
        latest.setProgress(100);
        latest.setEstimatedRemainingSeconds(0L);
        latest.setCompletedAt(LocalDateTime.now());
        repository.saveAndFlush(latest);
        if (generatedMediaAssetService != null) {
            generatedMediaAssetService.register(outputPath, latest.getChatId(), latest.getUserId(), "video");
        }
        logger.info("Video generation job {} completed at {}", latest.getId(), outputPath);
    }

    private void applySubmission(VideoGenerationJob job, VideoSubmission submission) {
        job.setComfyPromptId(submission.promptId());
        job.setComfyClientId(submission.clientId());
        job.setWidth(submission.width());
        job.setHeight(submission.height());
        job.setFrames(submission.frames());
        job.setFps(submission.fps());
        job.setSteps(submission.steps());
        long work = (long) submission.width() * submission.height() * submission.frames() * submission.steps();
        long estimatedSeconds = Math.max(60L, Math.min(14_400L, Math.round(45D + work * 0.0000022D)));
        job.setEstimatedTotalSeconds(estimatedSeconds);
        job.setEstimatedRemainingSeconds(estimatedSeconds);
        job.setStatus(Status.GENERATING);
        job.setStage("Gerando no ComfyUI");
        job.setProgress(4);
    }

    private void updateEstimate(VideoGenerationJob job, boolean queued) {
        long elapsed = elapsedSeconds(job);
        long total = Math.max(60L, job.getEstimatedTotalSeconds());
        if (elapsed >= total) {
            total = elapsed + Math.max(60L, total / 4L);
            job.setEstimatedTotalSeconds(total);
        }
        int progress = queued ? 3 : (int) Math.min(95L, Math.max(5L, elapsed * 95L / total));
        job.setStatus(queued ? Status.QUEUED : Status.GENERATING);
        job.setStage(queued ? "Aguardando o ComfyUI" : "Gerando no ComfyUI");
        job.setProgress(progress);
        job.setEstimatedRemainingSeconds(Math.max(1L, total - elapsed));
    }

    private long elapsedSeconds(VideoGenerationJob job) {
        LocalDateTime startedAt = job.getStartedAt() == null ? job.getCreatedAt() : job.getStartedAt();
        if (startedAt == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(startedAt, LocalDateTime.now()).toSeconds());
    }

    private void fail(UUID jobId, String error) {
        repository.findById(jobId).ifPresent(job -> {
            if (deletedJobs.contains(jobId) || job.getStatus() == Status.CANCELLED) {
                return;
            }
            job.setStatus(Status.FAILED);
            job.setStage("Falhou");
            job.setError(error);
            job.setEstimatedRemainingSeconds(0L);
            job.setCompletedAt(LocalDateTime.now());
            repository.saveAndFlush(job);
        });
    }

    private void cancelComfyPrompt(VideoGenerationJob job) {
        if (job.getComfyPromptId() == null || job.getComfyPromptId().isBlank()) {
            return;
        }
        try {
            comfyUiImageService.cancelVideo(job.getComfyPromptId());
        } catch (Exception exception) {
            logger.warn("Could not cancel ComfyUI prompt {}", job.getComfyPromptId(), exception);
        }
    }

    private boolean shouldStop(VideoGenerationJob job) {
        return deletedJobs.contains(job.getId())
                || job.isCancellationRequested()
                || job.getStatus().isTerminal();
    }

    private VideoGenerationJob findOwned(UUID jobId, UUID userId) {
        return repository.findByIdAndUserId(jobId, userId).orElseThrow(() -> new VideoJobNotFoundException(jobId));
    }

    private VideoJobView toView(VideoGenerationJob job) {
        String mediaUrl = "";
        if (job.getStatus() == Status.COMPLETED
                && job.getOutputPath() != null
                && !job.getOutputPath().isBlank()) {
            mediaUrl = "/api/media/" + Paths.get(job.getOutputPath()).getFileName();
        }
        return new VideoJobView(
                job.getId(),
                job.getChatId(),
                job.getStatus().name().toLowerCase(),
                job.getStage(),
                job.getProgress(),
                job.getEstimatedRemainingSeconds(),
                elapsedSeconds(job),
                job.getWidth(),
                job.getHeight(),
                job.getFrames(),
                job.getFps(),
                job.getPrompt(),
                job.getSourceImagePath() == null || job.getSourceImagePath().isBlank()
                        ? ""
                        : "/api/media/" + Paths.get(job.getSourceImagePath()).getFileName(),
                mediaUrl,
                job.getError(),
                job.getCreatedAt(),
                job.getCompletedAt());
    }

    private String requestedSize(VideoGenerationJob job) {
        return job.getWidth() > 0 && job.getHeight() > 0 ? job.getWidth() + "x" + job.getHeight() : "832x480";
    }

    private int requestedSeconds(VideoGenerationJob job) {
        return job.getFrames() > 0 && job.getFps() > 0
                ? Math.max(1, Math.round((float) job.getFrames() / job.getFps()))
                : 2;
    }

    private int[] requestedDimensions(String size, Path sourceImagePath) {
        if ((size == null || size.isBlank() || "auto".equalsIgnoreCase(size)) && sourceImagePath != null) {
            int[] sourceDimensions = dimensionsForSourceImage(sourceImagePath);
            if (sourceDimensions != null) {
                return sourceDimensions;
            }
        }
        String[] parts = size == null ? new String[0] : size.toLowerCase().split("x");
        return new int[] {requestedDimension(parts, 0, 832), requestedDimension(parts, 1, 480)};
    }

    private int[] dimensionsForSourceImage(Path sourceImagePath) {
        try {
            BufferedImage image = ImageIO.read(sourceImagePath.toFile());
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return null;
            }
            double aspectRatio = (double) image.getWidth() / image.getHeight();
            double targetPixels = 832D * 480D;
            int width = alignVideoDimension((int) Math.sqrt(targetPixels * aspectRatio));
            int height = alignVideoDimension((int) Math.sqrt(targetPixels / aspectRatio));
            return new int[] {width, height};
        } catch (IOException exception) {
            logger.warn("Could not inspect source image dimensions: {}", sourceImagePath, exception);
            return null;
        }
    }

    private int alignVideoDimension(int value) {
        int bounded = Math.max(256, Math.min(1024, value));
        return bounded - bounded % 32;
    }

    private int requestedDimension(String[] parts, int index, int fallback) {
        if (parts.length != 2) {
            return fallback;
        }
        try {
            int value = Math.max(256, Math.min(1024, Integer.parseInt(parts[index].trim())));
            return value - value % 8;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private boolean deleteOutput(String outputPath) {
        if (outputPath == null || outputPath.isBlank()) {
            return false;
        }
        try {
            Path candidate = Path.of(outputPath).toAbsolutePath().normalize();
            String filename = candidate.getFileName().toString();
            if (!candidate.startsWith(mediaDirectory)
                    || !filename.startsWith("avento-video-")
                    || !filename.endsWith(".webp")) {
                logger.warn("Ignoring unmanaged video path during chat deletion: {}", candidate);
                return false;
            }
            return Files.deleteIfExists(candidate);
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível apagar o vídeo " + outputPath, exception);
        }
    }

    private String readableError(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    public static class VideoJobNotFoundException extends RuntimeException {
        public VideoJobNotFoundException(UUID id) {
            super("Video generation job not found: " + id);
        }
    }
}

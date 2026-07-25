package com.avento.service.image;

import com.avento.model.ImageGenerationJob;
import com.avento.model.ImageGenerationJob.Status;
import com.avento.repository.ImageGenerationJobRepository;
import com.avento.service.GeneratedMediaAssetService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ImageGenerationJobWorker {

    private static final Logger logger = LoggerFactory.getLogger(ImageGenerationJobWorker.class);

    private final ImageGenerationJobRepository repository;
    private final ImageGenerator imageGenerator;
    private final GeneratedMediaAssetService generatedMediaAssetService;
    private final ObjectMapper mapper;
    private final Executor executor;
    private final Path mediaDirectory;
    private final Map<UUID, Thread> runningWorkers = new ConcurrentHashMap<>();
    private final Set<UUID> deletedJobs = ConcurrentHashMap.newKeySet();

    public ImageGenerationJobWorker(
            ImageGenerationJobRepository repository,
            ImageGenerator imageGenerator,
            GeneratedMediaAssetService generatedMediaAssetService,
            ObjectMapper mapper,
            @Qualifier("imageGenerationExecutor") Executor executor,
            @Value("${avento.media.directory:}") String configuredMediaDirectory) {
        this.repository = repository;
        this.imageGenerator = imageGenerator;
        this.generatedMediaAssetService = generatedMediaAssetService;
        this.mapper = mapper;
        this.executor = executor;
        this.mediaDirectory = configuredMediaDirectory == null || configuredMediaDirectory.isBlank()
                ? Paths.get(System.getProperty("user.home"), "Pictures", "Avento Generated Images")
                        .toAbsolutePath()
                        .normalize()
                : Paths.get(configuredMediaDirectory).toAbsolutePath().normalize();
    }

    public void submit(UUID jobId) {
        executor.execute(() -> process(jobId));
    }

    public void cancel(ImageGenerationJob job) {
        imageGenerator.cancel(runningWorkers.get(job.getId()), job.getModel());
    }

    public boolean delete(ImageGenerationJob job) {
        deletedJobs.add(job.getId());
        if (!job.getStatus().isTerminal()) {
            cancel(job);
        }
        return deleteOutput(job.getOutputPath());
    }

    private void process(UUID jobId) {
        Thread currentWorker = Thread.currentThread();
        runningWorkers.put(jobId, currentWorker);
        try {
            ImageGenerationJob job = repository.findById(jobId).orElse(null);
            if (job == null || shouldStop(job)) {
                return;
            }

            prepare(job);
            ObjectNode result = imageGenerator.generate(readPayload(job));
            ImageGenerationJob latest = repository.findById(jobId).orElse(null);
            if (latest == null || shouldStop(latest)) {
                deleteResultOutput(result);
                return;
            }
            if (result.has("error") || "failed".equals(result.path("status").asText())) {
                fail(latest, readableError(result));
                return;
            }
            complete(latest, result);
        } catch (Exception exception) {
            if (!Thread.currentThread().isInterrupted()) {
                logger.error("Image generation job {} failed", jobId, exception);
                repository.findById(jobId).ifPresent(job -> fail(job, readableError(exception)));
            }
        } finally {
            runningWorkers.remove(jobId, currentWorker);
        }
    }

    private void prepare(ImageGenerationJob job) throws IOException {
        job.setStatus(Status.PREPARING);
        job.setStage("Preparando modelo e workflow");
        job.setProgress(2);
        job.setStartedAt(LocalDateTime.now());
        repository.saveAndFlush(job);

        job.setStatus(Status.GENERATING);
        job.setStage(job.getModel().startsWith("comfyui:") ? "Gerando no ComfyUI" : "Gerando imagem");
        job.setProgress(5);
        repository.saveAndFlush(job);
    }

    private Map<String, Object> readPayload(ImageGenerationJob job) throws IOException {
        return mapper.readValue(job.getRequestPayload(), new TypeReference<>() {});
    }

    private void complete(ImageGenerationJob job, ObjectNode result) throws IOException {
        Path outputPath = Path.of(result.path("path").asText(""));
        if (!Files.isRegularFile(outputPath)) {
            fail(job, "O gerador concluiu sem produzir um arquivo de imagem válido.");
            return;
        }

        job.setStatus(Status.SAVING);
        job.setStage("Registrando a imagem");
        job.setProgress(97);
        job.setEstimatedRemainingSeconds(1L);
        job.setOutputPath(outputPath.toString());
        repository.saveAndFlush(job);

        if (deletedJobs.contains(job.getId()) || job.isCancellationRequested()) {
            Files.deleteIfExists(outputPath);
            return;
        }
        if (generatedMediaAssetService != null) {
            generatedMediaAssetService.register(outputPath, job.getChatId(), job.getUserId(), "image");
        }
        job.setStatus(Status.COMPLETED);
        job.setStage("Concluída");
        job.setProgress(100);
        job.setEstimatedRemainingSeconds(0L);
        job.setCompletedAt(LocalDateTime.now());
        repository.saveAndFlush(job);
        logger.info("Image generation job {} completed at {}", job.getId(), outputPath);
    }

    private void fail(ImageGenerationJob job, String error) {
        if (deletedJobs.contains(job.getId()) || job.getStatus() == Status.CANCELLED) {
            return;
        }
        job.setStatus(Status.FAILED);
        job.setStage("Falha na geração");
        job.setError(error);
        job.setEstimatedRemainingSeconds(0L);
        job.setCompletedAt(LocalDateTime.now());
        repository.saveAndFlush(job);
    }

    private boolean shouldStop(ImageGenerationJob job) {
        return deletedJobs.contains(job.getId())
                || job.isCancellationRequested()
                || job.getStatus().isTerminal();
    }

    private void deleteResultOutput(ObjectNode result) {
        String outputPath = result.path("path").asText("");
        if (!outputPath.isBlank()) {
            deleteOutput(outputPath);
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
                    || !filename.startsWith("avento-image-")
                    || !filename.endsWith(".png")) {
                logger.warn("Ignoring unmanaged image path during deletion: {}", candidate);
                return false;
            }
            return Files.deleteIfExists(candidate);
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível apagar a imagem " + outputPath, exception);
        }
    }

    private String readableError(ObjectNode result) {
        String error = result.path("error").asText("Falha desconhecida ao gerar imagem.");
        String details = result.path("details").asText("");
        return details.isBlank() ? error : error + " " + details;
    }

    private String readableError(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}

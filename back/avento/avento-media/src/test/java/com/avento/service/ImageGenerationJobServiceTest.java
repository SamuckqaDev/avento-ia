package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.model.ImageGenerationJob;
import com.avento.model.ImageGenerationJob.Status;
import com.avento.repository.ImageGenerationJobRepository;
import com.avento.service.dto.ImageJobView;
import com.avento.service.image.ImageGenerationJobWorker;
import com.avento.service.image.ImageGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageGenerationJobServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir
    Path tempDirectory;

    @Test
    void enqueuesImmediatelyAndCompletesInTheBackground() throws Exception {
        ImageGenerationJobRepository repository = mock(ImageGenerationJobRepository.class);
        FakeImageGenerator generationService = new FakeImageGenerator();
        AtomicReference<ImageGenerationJob> stored = new AtomicReference<>();
        List<Runnable> tasks = new ArrayList<>();
        Executor executor = tasks::add;
        when(repository.saveAndFlush(any(ImageGenerationJob.class))).thenAnswer(invocation -> {
            ImageGenerationJob job = invocation.getArgument(0);
            stored.set(job);
            return job;
        });
        when(repository.findById(any(UUID.class))).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        Path generated = Files.writeString(tempDirectory.resolve("avento-image-test.png"), "image");
        ObjectNode success =
                new ObjectMapper().createObjectNode().put("status", "success").put("path", generated.toString());
        generationService.result = success;
        ObjectMapper mapper = new ObjectMapper();
        ImageGenerationJobWorker worker = new ImageGenerationJobWorker(
                repository, generationService, null, mapper, executor, tempDirectory.toString());
        ImageGenerationJobService service =
                new ImageGenerationJobService(repository, generationService, mapper, worker);

        ObjectNode result = service.enqueue(Map.of("prompt", "um carro vermelho"), 7L, USER_ID);

        assertThat(result.path("status").asText()).isEqualTo("queued");
        assertThat(tasks).hasSize(1);
        assertThat(stored.get().getStatus()).isEqualTo(Status.QUEUED);

        tasks.removeFirst().run();

        assertThat(stored.get().getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(stored.get().getProgress()).isEqualTo(100);
        assertThat(stored.get().getOutputPath()).isEqualTo(generated.toString());
    }

    @Test
    void cancelsAnOwnedQueuedJob() {
        ImageGenerationJobRepository repository = mock(ImageGenerationJobRepository.class);
        FakeImageGenerator generationService = new FakeImageGenerator();
        ImageGenerationJob job = new ImageGenerationJob();
        job.setId(UUID.randomUUID());
        job.setChatId(7L);
        job.setUserId(USER_ID);
        job.setPrompt("teste");
        job.setModel("comfyui:test.safetensors");
        job.setSize("512x512");
        job.setStatus(Status.QUEUED);
        job.setStage("Na fila");
        when(repository.findByIdAndUserId(job.getId(), USER_ID)).thenReturn(Optional.of(job));
        when(repository.saveAndFlush(job)).thenReturn(job);
        ObjectMapper mapper = new ObjectMapper();
        ImageGenerationJobWorker worker = new ImageGenerationJobWorker(
                repository, generationService, null, mapper, Runnable::run, tempDirectory.toString());
        ImageGenerationJobService service =
                new ImageGenerationJobService(repository, generationService, mapper, worker);

        ImageJobView view = service.cancel(job.getId(), USER_ID);

        assertThat(view.status()).isEqualTo("cancelled");
        assertThat(job.isCancellationRequested()).isTrue();
        assertThat(job.getEstimatedRemainingSeconds()).isZero();
    }

    private static class FakeImageGenerator implements ImageGenerator {
        private ObjectNode result;

        @Override
        public ObjectNode generate(Map<String, Object> payload) {
            return result;
        }

        @Override
        public String resolveModel(Map<String, Object> payload) {
            return "comfyui:test.safetensors";
        }

        @Override
        public void cancel(Thread worker, String model) {
            if (worker != null) {
                worker.interrupt();
            }
        }
    }
}

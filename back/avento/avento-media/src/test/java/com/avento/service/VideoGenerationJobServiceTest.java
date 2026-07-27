package com.avento.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.avento.model.VideoGenerationJob;
import com.avento.model.VideoGenerationJob.Status;
import com.avento.repository.VideoGenerationJobRepository;
import com.avento.service.dto.ImageReference;
import com.avento.service.dto.VideoStatus;
import com.avento.service.dto.VideoSubmission;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoGenerationJobServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir
    Path tempDirectory;

    @Test
    void enqueuesImmediatelyAndCompletesInTheBackground() throws Exception {
        VideoGenerationJobRepository repository = mock(VideoGenerationJobRepository.class);
        FakeComfyUiImageService comfyUi = new FakeComfyUiImageService();
        AtomicReference<VideoGenerationJob> stored = new AtomicReference<>();
        List<Runnable> tasks = new ArrayList<>();
        Executor executor = tasks::add;
        when(repository.saveAndFlush(any(VideoGenerationJob.class))).thenAnswer(invocation -> {
            VideoGenerationJob job = invocation.getArgument(0);
            stored.set(job);
            return job;
        });
        when(repository.findById(any(UUID.class))).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        comfyUi.submission = new VideoSubmission("prompt-1", "client-1", 640, 360, 29, 16, 20);
        comfyUi.videoStatus = new VideoStatus("completed", new ImageReference("source.webp", "", "output"), "");
        Path generated = Files.writeString(tempDirectory.resolve("avento-video-test.webp"), "video");
        comfyUi.outputPath = generated;
        VideoGenerationJobService service = new VideoGenerationJobService(
                repository, comfyUi, new ObjectMapper(), executor, tempDirectory.toString());

        var result = service.enqueue("um clipe", "640x360", 2, 7L, USER_ID);

        assertThat(result.path("status").asText()).isEqualTo("queued");
        assertThat(tasks).hasSize(1);
        assertThat(stored.get().getStatus()).isEqualTo(Status.QUEUED);

        tasks.remove(0).run();

        assertThat(stored.get().getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(stored.get().getProgress()).isEqualTo(100);
        assertThat(stored.get().getOutputPath()).isEqualTo(generated.toString());
    }

    @Test
    void imageToVideoPreservesTheSourceAspectRatio() throws Exception {
        VideoGenerationJobRepository repository = mock(VideoGenerationJobRepository.class);
        AtomicReference<VideoGenerationJob> stored = new AtomicReference<>();
        when(repository.saveAndFlush(any(VideoGenerationJob.class))).thenAnswer(invocation -> {
            VideoGenerationJob job = invocation.getArgument(0);
            stored.set(job);
            return job;
        });
        Path source = tempDirectory.resolve("avento-image-portrait.png");
        ImageIO.write(new BufferedImage(512, 768, BufferedImage.TYPE_INT_RGB), "png", source.toFile());
        VideoGenerationJobService service = new VideoGenerationJobService(
                repository, new FakeComfyUiImageService(), new ObjectMapper(), task -> {}, tempDirectory.toString());

        service.enqueue("movimento suave", "auto", 2, 7L, USER_ID, source);

        assertThat(stored.get().getWidth()).isEqualTo(512);
        assertThat(stored.get().getHeight()).isEqualTo(768);
        assertThat(stored.get().getSourceImagePath()).isEqualTo(source.toString());
    }

    @Test
    void cancelsTheOwnedComfyPrompt() throws Exception {
        VideoGenerationJobRepository repository = mock(VideoGenerationJobRepository.class);
        FakeComfyUiImageService comfyUi = new FakeComfyUiImageService();
        VideoGenerationJob job = job(Status.GENERATING);
        job.setComfyPromptId("prompt-running");
        when(repository.findByIdAndUserId(job.getId(), USER_ID)).thenReturn(Optional.of(job));
        when(repository.saveAndFlush(job)).thenReturn(job);
        VideoGenerationJobService service = new VideoGenerationJobService(
                repository, comfyUi, new ObjectMapper(), Runnable::run, tempDirectory.toString());

        var view = service.cancel(job.getId(), USER_ID);

        assertThat(view.status()).isEqualTo("cancelled");
        assertThat(job.isCancellationRequested()).isTrue();
        assertThat(comfyUi.cancelledPromptId).isEqualTo("prompt-running");
    }

    @Test
    void deletingAChatRemovesJobsAndGeneratedFiles() throws Exception {
        VideoGenerationJobRepository repository = mock(VideoGenerationJobRepository.class);
        FakeComfyUiImageService comfyUi = new FakeComfyUiImageService();
        Path generated = Files.writeString(tempDirectory.resolve("avento-video-delete.webp"), "video");
        VideoGenerationJob job = job(Status.COMPLETED);
        job.setOutputPath(generated.toString());
        when(repository.findByChatIdAndUserId(7L, USER_ID)).thenReturn(List.of(job));
        VideoGenerationJobService service = new VideoGenerationJobService(
                repository, comfyUi, new ObjectMapper(), Runnable::run, tempDirectory.toString());

        var result = service.deleteForChat(7L, USER_ID);

        assertThat(result.deletedJobs()).isEqualTo(1);
        assertThat(result.deletedFiles()).isEqualTo(1);
        assertThat(generated).doesNotExist();
        org.mockito.Mockito.verify(repository).deleteAllInBatch(List.of(job));
    }

    private VideoGenerationJob job(Status status) {
        VideoGenerationJob job = new VideoGenerationJob();
        job.setId(UUID.randomUUID());
        job.setChatId(7L);
        job.setUserId(USER_ID);
        job.setPrompt("teste");
        job.setStatus(status);
        job.setStage(status.name());
        return job;
    }

    private static class FakeComfyUiImageService extends ComfyUiImageService {
        VideoSubmission submission;
        VideoStatus videoStatus;
        Path outputPath;
        String cancelledPromptId;

        FakeComfyUiImageService() {
            super(
                    new ObjectMapper(),
                    "http://127.0.0.1:8188",
                    new com.avento.service.image.ImageModelPresetCatalog(new ObjectMapper(), ""));
        }

        @Override
        public VideoSubmission submitVideo(String prompt, String size, int seconds, Path sourceImage) {
            return submission;
        }

        @Override
        public VideoStatus inspectVideo(String promptId) {
            return videoStatus;
        }

        @Override
        public Path saveVideoOutput(ImageReference video) {
            return outputPath;
        }

        @Override
        public void cancelVideo(String promptId) {
            cancelledPromptId = promptId;
        }
    }
}

package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.BaseResponse;
import com.avento.auth.security.AuthPrincipal;
import com.avento.service.VideoGenerationJobService;
import com.avento.service.dto.VideoJobView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/videos")
public class VideoGenerationController {

    private final VideoGenerationJobService videoGenerationJobService;

    public VideoGenerationController(VideoGenerationJobService videoGenerationJobService) {
        this.videoGenerationJobService = videoGenerationJobService;
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<BaseResponse<VideoJobView>> getJob(
            @PathVariable UUID jobId, @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponses.ok(videoGenerationJobService.getOwnedJob(jobId, requiredUserId(principal)));
    }

    @PostMapping("/{jobId}/cancel")
    public ResponseEntity<BaseResponse<VideoJobView>> cancelJob(
            @PathVariable UUID jobId, @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponses.ok(videoGenerationJobService.cancel(jobId, requiredUserId(principal)));
    }

    @GetMapping("/chat/{chatId}")
    public ResponseEntity<BaseResponse<List<VideoJobView>>> getChatJobs(
            @PathVariable Long chatId, @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponses.ok(videoGenerationJobService.getChatJobs(chatId, requiredUserId(principal)));
    }

    private UUID requiredUserId(AuthPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}

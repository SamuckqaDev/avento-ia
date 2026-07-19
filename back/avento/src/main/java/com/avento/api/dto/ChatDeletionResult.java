package com.avento.api.dto;

public record ChatDeletionResult(
        Long chatId,
        int deletedMessages,
        int deletedArtifacts,
        int deletedMediaAssets,
        int deletedVideoJobs,
        int deletedImageJobs,
        int deletedAgentJobs) {}

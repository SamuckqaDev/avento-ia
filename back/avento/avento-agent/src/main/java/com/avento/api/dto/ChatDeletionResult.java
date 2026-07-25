package com.avento.api.dto;

public record ChatDeletionResult(
        Long chatId,
        int deletedMessages,
        int deletedArtifacts,
        int deletedMediaAssets,
        int deletedVideoJobs,
        int deletedImageJobs,
        int deletedAgentJobs,
        long deletedResidueRows,
        int failedFiles) {

    public ChatDeletionResult(
            Long chatId,
            int deletedMessages,
            int deletedArtifacts,
            int deletedMediaAssets,
            int deletedVideoJobs,
            int deletedImageJobs,
            int deletedAgentJobs) {
        this(
                chatId,
                deletedMessages,
                deletedArtifacts,
                deletedMediaAssets,
                deletedVideoJobs,
                deletedImageJobs,
                deletedAgentJobs,
                0,
                0);
    }
}

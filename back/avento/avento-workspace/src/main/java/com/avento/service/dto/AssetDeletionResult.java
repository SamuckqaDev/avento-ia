package com.avento.service.dto;

public record AssetDeletionResult(int deletedAssets, int deletedFiles, int failedFiles) {

    public AssetDeletionResult(int deletedAssets, int deletedFiles) {
        this(deletedAssets, deletedFiles, 0);
    }
}

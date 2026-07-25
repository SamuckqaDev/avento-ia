package com.avento.service.dto;

/** keptFiles are files referenced by text but owned by another chat — those are never deleted. */
public record ArtifactDeletionResult(int referencedFiles, int deletedFiles, int keptFiles, int failedFiles) {

    public ArtifactDeletionResult(int referencedFiles, int deletedFiles) {
        this(referencedFiles, deletedFiles, 0, 0);
    }
}

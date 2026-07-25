package com.avento.service.dto;

/** Rows and files removed from the tables a chat writes to outside messages and media. */
public record ResidueDeletionResult(long deletedRows, int deletedFiles, int failedFiles) {

    public static ResidueDeletionResult empty() {
        return new ResidueDeletionResult(0, 0, 0);
    }
}

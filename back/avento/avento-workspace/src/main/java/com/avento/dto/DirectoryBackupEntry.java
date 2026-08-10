package com.avento.dto;

public record DirectoryBackupEntry(
        String id, String originalPath, String backupPath, long fileCount, boolean backedUp, String createdAt) {}

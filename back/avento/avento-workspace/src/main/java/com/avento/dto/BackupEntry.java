package com.avento.dto;

public record BackupEntry(String id, String originalPath, String backupPath, boolean existed, String createdAt) {}

package com.avento.service.dto;

public record BackupEntry(String id, String originalPath, String backupPath, boolean existed, String createdAt) {}

package com.avento.dto;

/** Public status of the local Obsidian knowledge source. */
public record ObsidianVaultStatus(boolean available, String path, String indexState, String message) {}

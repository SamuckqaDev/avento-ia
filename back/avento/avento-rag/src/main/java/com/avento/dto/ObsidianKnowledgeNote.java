package com.avento.dto;

/** Result of persisting one explicit, user-approved Markdown note in the local vault. */
public record ObsidianKnowledgeNote(String title, String path, boolean indexQueued) {}

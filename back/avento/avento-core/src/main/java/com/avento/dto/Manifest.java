package com.avento.dto;

import java.util.Map;

/**
 * What the RAG index already holds for one project root.
 *
 * <p>{@code indexName} records WHICH vector index these chunks live in. It is a field instead of part
 * of the manifest key on purpose: a key per index leaves the previous manifest unreachable, and with
 * it every chunk it listed, orphaned in Redis forever. Keeping one manifest per root means the
 * indexing pass can always see what the old index held, and delete it.
 *
 * <p>A manifest written before this field existed deserializes with {@code indexName == null}, which
 * compares as "different index" and therefore triggers exactly the cleanup and full reindex it needs.
 */
public record Manifest(String projectRoot, String indexName, Map<String, FileManifest> files) {}

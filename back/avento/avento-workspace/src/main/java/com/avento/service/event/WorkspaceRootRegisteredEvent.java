package com.avento.service.event;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Published whenever a workspace root becomes authorized for a user.
 *
 * <p>The workspace module owns the event so it does not have to know who reacts to it. The RAG
 * module listens and warms its vector index; nothing else in the workspace module changes if that
 * listener is absent.
 *
 * @param root canonical, already normalized root directory
 * @param userId owner of the scope, or {@code null} for the local (unauthenticated) scope
 */
public record WorkspaceRootRegisteredEvent(Path root, UUID userId) {}

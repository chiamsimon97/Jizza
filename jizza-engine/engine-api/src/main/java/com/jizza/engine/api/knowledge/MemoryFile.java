package com.jizza.engine.api.knowledge;

import java.time.Instant;
import java.util.UUID;

/**
 * A file in one agent's memory: a document, PDF or image the user gave that agent to know.
 *
 * <p>{@code readableChars} is how much text the agent can actually search from it. Zero means the
 * agent holds the file but cannot read inside it yet (an image, or a PDF that is only scans).
 */
public record MemoryFile(UUID id, UUID agentId, String filename, String contentType,
                         long sizeBytes, int readableChars, Instant createdAt) {

  /** The file itself, decrypted, for showing it back to its owner. */
  public record Content(MemoryFile file, byte[] bytes) {}
}

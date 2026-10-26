package com.jizza.engine.api.chat;

import java.util.Optional;
import java.util.UUID;

/**
 * Files the user attaches to a chat message: PDFs and photos the model reads itself. The message
 * refers to a file by id ({@code <attachment ... ref="<id>">}); the model client looks the file up
 * when it sends that message, and only for the user whose chat turn it is.
 */
public interface ChatFiles {

  /** Stores the file and returns what the chat needs: its id and any text read from it. */
  Upload add(UUID userId, String filename, String contentType, byte[] bytes);

  /** The file's bytes, if it exists and belongs to {@code userId}. */
  Optional<Content> content(UUID userId, UUID fileId);

  /** @param text the readable text (a PDF's text layer), cut to a length a message can hold */
  record Upload(UUID id, String name, String contentType, long sizeBytes, String text) {}

  record Content(String name, String contentType, byte[] bytes) {}
}

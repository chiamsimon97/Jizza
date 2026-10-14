package com.jizza.platform.api.rest;

import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.knowledge.MemoryFile;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.api.rest.dto.Responses;
import com.jizza.platform.usecase.agent.AgentMemoryUseCase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Each agent's memory: what it knows about the user, and the files the user gave it. */
@RestController
@RequestMapping("/api/agents/{agentId}/memory")
public class AgentMemoryController {

  /** Shown in the browser; everything else is downloaded rather than rendered. */
  private static final Set<String> INLINE_TYPES = Set.of(
      "image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf", "text/plain",
      "text/markdown", "text/csv", "application/json");

  private final AgentMemoryUseCase memory;

  public AgentMemoryController(AgentMemoryUseCase memory) {
    this.memory = memory;
  }

  @GetMapping
  public AgentMemoryUseCase.AgentMemory view(@PathVariable UUID agentId) {
    return memory.view(CurrentUser.userId(), agentId);
  }

  @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public MemoryFile upload(@PathVariable UUID agentId, @RequestParam("file") MultipartFile file) {
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new ValidationException("The upload could not be read");
    }
    return memory.addFile(CurrentUser.userId(), agentId, file.getOriginalFilename(),
        file.getContentType(), bytes);
  }

  @GetMapping("/files/{fileId}/content")
  public ResponseEntity<byte[]> content(@PathVariable UUID agentId, @PathVariable UUID fileId) {
    MemoryFile.Content content = memory.fileContent(CurrentUser.userId(), agentId, fileId);
    MemoryFile file = content.file();
    boolean inline = INLINE_TYPES.contains(file.contentType());
    ContentDisposition disposition = (inline ? ContentDisposition.inline()
        : ContentDisposition.attachment())
        .filename(file.filename(), StandardCharsets.UTF_8).build();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.contentType()))
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        // Personal data: never cached, and never run as a page even if a type is mislabelled.
        .cacheControl(CacheControl.noStore())
        .header("Content-Security-Policy", "sandbox; default-src 'none'")
        .body(content.bytes());
  }

  @DeleteMapping("/files/{fileId}")
  public Responses.Message deleteFile(@PathVariable UUID agentId, @PathVariable UUID fileId) {
    memory.deleteFile(CurrentUser.userId(), agentId, fileId);
    return new Responses.Message("File removed.");
  }

  /** Permanent, so it asks for a fresh step-up like forgetting a fact. */
  @com.jizza.engine.api.util.StepUpRequired
  @DeleteMapping("/notes/{noteId}")
  public Responses.Message deleteNote(@PathVariable UUID agentId, @PathVariable UUID noteId) {
    memory.deleteNote(CurrentUser.userId(), agentId, noteId);
    return new Responses.Message("Note removed.");
  }
}

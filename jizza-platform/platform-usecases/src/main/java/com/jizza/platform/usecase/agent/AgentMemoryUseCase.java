package com.jizza.platform.usecase.agent;

import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.DataClassification;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.knowledge.KnowledgeEntry;
import com.jizza.engine.api.knowledge.KnowledgeStore;
import com.jizza.engine.api.knowledge.MemoryFile;
import com.jizza.engine.api.knowledge.MemoryFileStore;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * What each agent knows about the user, shown back to them (R22, R48).
 *
 * <p>Every agent has its own memory: notes it saved while working, and files the user gave it.
 * The user can see all of it, add files, and remove anything. Access always goes through the
 * agent, so one agent's memory page can never show another agent's data.
 */
@Service
public class AgentMemoryUseCase {

  private static final int MAX_NOTES = 200;

  /**
   * Types a browser can show safely or that are ordinary documents. SVG is left out on purpose: it
   * can carry script, and memory files are rendered back in the app.
   */
  private static final Set<String> ALLOWED_TYPES = Set.of(
      "image/png", "image/jpeg", "image/gif", "image/webp", "image/heic",
      "application/pdf",
      "text/plain", "text/markdown", "text/csv", "text/tab-separated-values",
      "application/json",
      "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "application/vnd.openxmlformats-officedocument.presentationml.presentation");

  /** Browsers often send these for files they do not recognise; trust the extension instead. */
  private static final Set<String> GENERIC_TYPES = Set.of("", "application/octet-stream");

  private static final java.util.Map<String, String> TYPE_BY_EXTENSION = java.util.Map.ofEntries(
      java.util.Map.entry("png", "image/png"), java.util.Map.entry("jpg", "image/jpeg"),
      java.util.Map.entry("jpeg", "image/jpeg"), java.util.Map.entry("gif", "image/gif"),
      java.util.Map.entry("webp", "image/webp"), java.util.Map.entry("heic", "image/heic"),
      java.util.Map.entry("pdf", "application/pdf"), java.util.Map.entry("txt", "text/plain"),
      java.util.Map.entry("md", "text/markdown"), java.util.Map.entry("csv", "text/csv"),
      java.util.Map.entry("tsv", "text/tab-separated-values"),
      java.util.Map.entry("json", "application/json"),
      java.util.Map.entry("doc", "application/msword"),
      java.util.Map.entry("xls", "application/vnd.ms-excel"),
      java.util.Map.entry("ppt", "application/vnd.ms-powerpoint"),
      java.util.Map.entry("docx",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
      java.util.Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
      java.util.Map.entry("pptx",
          "application/vnd.openxmlformats-officedocument.presentationml.presentation"));

  /** A fact or note the agent saved about the user while working. */
  public record MemoryNote(UUID id, String title, String content,
                           DataClassification classification, Instant createdAt,
                           Instant updatedAt, boolean restricted) {}

  public record AgentMemory(AgentDto agent, List<MemoryNote> notes, List<MemoryFile> files) {}

  private final AgentDirectory directory;
  private final KnowledgeStore knowledge;
  private final MemoryFileStore memoryFiles;

  public AgentMemoryUseCase(AgentDirectory directory, KnowledgeStore knowledge,
                            MemoryFileStore memoryFiles) {
    this.directory = directory;
    this.knowledge = knowledge;
    this.memoryFiles = memoryFiles;
  }

  public AgentMemory view(UUID userId, UUID agentId) {
    AgentDto agent = ownedBy(userId, agentId);
    // Text read out of a file is shown as that file, not repeated as a note.
    List<MemoryNote> notes = knowledge.list(agentId, MAX_NOTES).stream()
        .filter(entry -> !isFromFile(entry))
        // A restricted note's words are shown only through the step-up guarded reveal.
        .map(entry -> new MemoryNote(entry.id(), entry.restricted() ? null : entry.title(),
            entry.restricted() ? null : entry.content(), entry.classification(), entry.createdAt(),
            entry.updatedAt(), entry.restricted()))
        .toList();
    return new AgentMemory(agent, notes, memoryFiles.list(agentId));
  }

  public MemoryFile addFile(UUID userId, UUID agentId, String filename, String contentType,
                            byte[] bytes) {
    ownedBy(userId, agentId);
    return memoryFiles.add(agentId, filename, resolveType(filename, contentType), bytes);
  }

  public MemoryFile.Content fileContent(UUID userId, UUID agentId, UUID fileId) {
    ownedBy(userId, agentId);
    return memoryFiles.content(agentId, fileId)
        .orElseThrow(() -> new NotFoundException("File", fileId));
  }

  public void deleteFile(UUID userId, UUID agentId, UUID fileId) {
    ownedBy(userId, agentId);
    memoryFiles.delete(agentId, fileId);
  }

  public void deleteNote(UUID userId, UUID agentId, UUID noteId) {
    ownedBy(userId, agentId);
    knowledge.delete(agentId, noteId);
  }

  /** The declared type when it is a known one, otherwise the extension's; anything else refused. */
  static String resolveType(String filename, String contentType) {
    String declared = contentType == null ? ""
        : contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
    String type = GENERIC_TYPES.contains(declared) ? TYPE_BY_EXTENSION.get(extensionOf(filename))
        : declared;
    if (type == null || !ALLOWED_TYPES.contains(type)) {
      throw new ValidationException("That file type isn't supported. Upload an image, a PDF, a "
          + "text file or an Office document.");
    }
    return type;
  }

  private static boolean isFromFile(KnowledgeEntry entry) {
    return entry.data() != null && "file".equals(entry.data().path("source").asText());
  }

  private static String extensionOf(String filename) {
    if (filename == null) return "";
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private AgentDto ownedBy(UUID userId, UUID agentId) {
    return directory.byId(agentId)
        .filter(agent -> agent.userId().equals(userId))
        .orElseThrow(() -> new NotFoundException("Agent", agentId));
  }
}

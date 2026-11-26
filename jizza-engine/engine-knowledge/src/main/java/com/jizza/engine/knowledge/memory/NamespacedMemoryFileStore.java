package com.jizza.engine.knowledge.memory;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.DataClassification;
import com.jizza.engine.api.error.IsolationViolationException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.knowledge.KnowledgeEntry;
import com.jizza.engine.api.knowledge.KnowledgeStore;
import com.jizza.engine.api.knowledge.MemoryFile;
import com.jizza.engine.api.knowledge.MemoryFileStore;
import com.jizza.engine.api.storage.StorageProvider;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.FileEntity;
import com.jizza.engine.persistence.entity.KnowledgeEntryEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.FileRepository;
import com.jizza.engine.persistence.repository.KnowledgeEntryRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent memory files, isolated per agent the same way {@code NamespacedKnowledgeStore} isolates
 * knowledge (R22): the agent id is part of every lookup, and a file reached through any other
 * agent is an isolation violation, not a miss.
 *
 * <p>Bytes are envelope-encrypted before they reach storage (R7), because what a user hands their
 * health or money agent is exactly the data that must not sit on disk in the clear.
 */
@Service
public class NamespacedMemoryFileStore implements MemoryFileStore {

  private static final Logger log = LoggerFactory.getLogger(NamespacedMemoryFileStore.class);

  private final FileRepository files;
  private final AgentRepository agents;
  private final KnowledgeEntryRepository entries;
  private final KnowledgeStore knowledge;
  private final StorageProvider storage;
  private final EncryptionService encryption;
  private final AuditLog audit;
  private final long maxBytes;

  public NamespacedMemoryFileStore(FileRepository files, AgentRepository agents,
                                   KnowledgeEntryRepository entries, KnowledgeStore knowledge,
                                   StorageProvider storage, EncryptionService encryption,
                                   AuditLog audit,
                                   @Value("${jizza.platform.files.max-bytes:10485760}") long maxBytes) {
    this.files = files;
    this.agents = agents;
    this.entries = entries;
    this.knowledge = knowledge;
    this.storage = storage;
    this.encryption = encryption;
    this.audit = audit;
    this.maxBytes = maxBytes;
  }

  @Override
  @Transactional(readOnly = true)
  public List<MemoryFile> list(UUID agentId) {
    List<FileEntity> rows = files.findByAgentIdOrderByCreatedAtDesc(agentId);
    Map<UUID, Integer> readable = entries.findAllById(rows.stream()
            .map(FileEntity::getKnowledgeEntryId).filter(Objects::nonNull).toList())
        .stream()
        .collect(Collectors.toMap(KnowledgeEntryEntity::getId,
            e -> e.getContent() == null ? 0 : e.getContent().length()));
    return rows.stream()
        .map(f -> toDto(f, f.getKnowledgeEntryId() == null ? 0
            : readable.getOrDefault(f.getKnowledgeEntryId(), 0)))
        .toList();
  }

  @Override
  @Transactional
  public MemoryFile add(UUID agentId, String filename, String contentType, byte[] bytes) {
    if (bytes == null || bytes.length == 0) throw new ValidationException("The file is empty");
    if (bytes.length > maxBytes) {
      throw new ValidationException("Files are limited to " + (maxBytes / (1024 * 1024)) + " MB");
    }
    AgentEntity agent = agents.findById(agentId)
        .orElseThrow(() -> new NotFoundException("Agent", agentId));
    String name = cleanName(filename);

    FileEntity file = new FileEntity();
    file.setUserId(agent.getUserId());
    file.setAgentId(agentId);
    file.setFilename(name);
    file.setContentType(contentType == null || contentType.isBlank()
        ? "application/octet-stream" : contentType);
    file.setSizeBytes((long) bytes.length);
    file.setSha256(sha256(bytes));
    file.setScanStatus("not_scanned");
    file.setStorageRef(storage.put(encryption.encrypt(bytes), null));

    String text = TextExtractor.extract(name, file.getContentType(), bytes);
    if (!text.isEmpty()) {
      KnowledgeEntry entry = knowledge.upsert(agentId, KnowledgeEntry.create(agent.getUserId(),
          agentId, name, text, Json.obj().put("source", "file"), DataClassification.PII));
      file.setKnowledgeEntryId(entry.id());
    }
    FileEntity saved = files.save(file);

    // Like knowledge, the audit trail says a file was added and how big, never what is in it.
    audit.record(agent.getUserId(), ActorType.USER, agentId, "memory.file_added", name,
        Json.obj().put("fileId", saved.getId().toString()).put("bytes", bytes.length)
            .put("readableChars", text.length()));
    return toDto(saved, text.length());
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<MemoryFile.Content> content(UUID agentId, UUID fileId) {
    return files.findById(fileId)
        .filter(f -> f.getAgentId() != null)
        .map(f -> {
          requireOwnNamespace(agentId, f);
          return new MemoryFile.Content(toDto(f, 0),
              encryption.decrypt(storage.get(f.getStorageRef())));
        });
  }

  @Override
  @Transactional
  public void delete(UUID agentId, UUID fileId) {
    files.findById(fileId).filter(f -> f.getAgentId() != null).ifPresent(f -> {
      requireOwnNamespace(agentId, f);
      if (f.getKnowledgeEntryId() != null) knowledge.delete(agentId, f.getKnowledgeEntryId());
      files.delete(f);
      try {
        storage.delete(f.getStorageRef());
      } catch (RuntimeException e) {
        // The row is gone, so nothing can reach the bytes; an orphaned ciphertext is not a leak.
        log.warn("Could not remove stored bytes for memory file {}", f.getId(), e);
      }
      audit.record(f.getUserId(), ActorType.USER, agentId, "memory.file_deleted", f.getFilename(),
          Json.obj().put("fileId", f.getId().toString()));
    });
  }

  private void requireOwnNamespace(UUID agentId, FileEntity file) {
    if (!agentId.equals(file.getAgentId())) {
      audit.record(file.getUserId(), ActorType.AGENT, agentId, "memory.isolation_violation",
          String.valueOf(file.getAgentId()), null);
      throw new IsolationViolationException(agentId, file.getAgentId());
    }
  }

  /** Keeps only the last path segment and drops control characters; the name is display-only. */
  public static String cleanName(String filename) {
    String name = filename == null ? "" : filename;
    name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    name = name.replaceAll("\\p{Cntrl}", "").strip();
    if (name.isEmpty()) name = "file";
    return name.length() > 200 ? name.substring(name.length() - 200) : name;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static MemoryFile toDto(FileEntity f, int readableChars) {
    return new MemoryFile(f.getId(), f.getAgentId(), f.getFilename(), f.getContentType(),
        f.getSizeBytes() == null ? 0 : f.getSizeBytes(), readableChars, f.getCreatedAt());
  }
}

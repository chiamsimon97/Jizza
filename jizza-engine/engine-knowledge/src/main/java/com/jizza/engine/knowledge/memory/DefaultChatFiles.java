package com.jizza.engine.knowledge.memory;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.chat.ChatFiles;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.storage.StorageProvider;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.FileEntity;
import com.jizza.engine.persistence.repository.FileRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chat attachments, kept in the same {@code files} table as agent memory files but with no agent:
 * they belong to the user, are encrypted at rest like memory files (R7), and go when the account
 * does.
 */
@Service
public class DefaultChatFiles implements ChatFiles {

  /** What a chat message can carry of a file's text; the model reads the file itself as well. */
  static final int MAX_TEXT_CHARS = 12_000;

  private final FileRepository files;
  private final StorageProvider storage;
  private final EncryptionService encryption;
  private final AuditLog audit;
  private final long maxBytes;

  public DefaultChatFiles(FileRepository files, StorageProvider storage,
                          EncryptionService encryption, AuditLog audit,
                          @Value("${jizza.platform.files.max-bytes:10485760}") long maxBytes) {
    this.files = files;
    this.storage = storage;
    this.encryption = encryption;
    this.audit = audit;
    this.maxBytes = maxBytes;
  }

  @Override
  @Transactional
  public Upload add(UUID userId, String filename, String contentType, byte[] bytes) {
    if (bytes == null || bytes.length == 0) throw new ValidationException("The file is empty");
    if (bytes.length > maxBytes) {
      throw new ValidationException("Files are limited to " + (maxBytes / (1024 * 1024)) + " MB");
    }
    String name = NamespacedMemoryFileStore.cleanName(filename);
    FileEntity file = new FileEntity();
    file.setUserId(userId);
    file.setFilename(name);
    file.setContentType(contentType == null || contentType.isBlank()
        ? "application/octet-stream" : contentType);
    file.setSizeBytes((long) bytes.length);
    file.setScanStatus("not_scanned");
    file.setStorageRef(storage.put(encryption.encrypt(bytes), null));
    FileEntity saved = files.save(file);

    String text = TextExtractor.extract(name, saved.getContentType(), bytes);
    if (text.length() > MAX_TEXT_CHARS) text = text.substring(0, MAX_TEXT_CHARS);
    audit.record(userId, ActorType.USER, null, "chat.file_added", name,
        Json.obj().put("fileId", saved.getId().toString()).put("bytes", bytes.length));
    return new Upload(saved.getId(), name, saved.getContentType(), bytes.length, text);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Content> content(UUID userId, UUID fileId) {
    return files.findById(fileId)
        .filter(f -> f.getAgentId() == null && f.getUserId().equals(userId))
        .map(f -> new Content(f.getFilename(), f.getContentType(),
            encryption.decrypt(storage.get(f.getStorageRef()))));
  }
}

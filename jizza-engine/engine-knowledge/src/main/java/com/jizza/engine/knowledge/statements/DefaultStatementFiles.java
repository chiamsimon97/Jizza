package com.jizza.engine.knowledge.statements;

import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.StatementFiles;
import com.jizza.engine.api.storage.StorageProvider;
import com.jizza.engine.knowledge.memory.NamespacedMemoryFileStore;
import com.jizza.engine.persistence.entity.FileEntity;
import com.jizza.engine.persistence.repository.FileRepository;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link StatementFiles} on the {@code files} table and the storage port, encrypted like chat
 * attachments. Nothing here logs a file's name, size or contents.
 */
@Service
public class DefaultStatementFiles implements StatementFiles {

  /** Statements longer than this are not read; a year of a busy account is far below it. */
  static final int MAX_PAGES = 400;

  private final FileRepository files;
  private final StorageProvider storage;
  private final EncryptionService encryption;
  private final long maxBytes;

  public DefaultStatementFiles(FileRepository files, StorageProvider storage, EncryptionService encryption,
                               @Value("${jizza.platform.files.max-bytes:10485760}") long maxBytes) {
    this.files = files;
    this.storage = storage;
    this.encryption = encryption;
    this.maxBytes = maxBytes;
  }

  @Override
  @Transactional
  public UUID store(UUID userId, String filename, String contentType, byte[] bytes) {
    if (bytes == null || bytes.length == 0) throw new ValidationException("The file is empty.");
    if (bytes.length > maxBytes) {
      throw new ValidationException("Files are limited to " + (maxBytes / (1024 * 1024)) + " MB.");
    }
    FileEntity file = new FileEntity();
    file.setUserId(userId);
    file.setFilename(NamespacedMemoryFileStore.cleanName(filename == null ? "statement" : filename));
    file.setContentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType);
    file.setSizeBytes((long) bytes.length);
    file.setScanStatus("not_scanned");
    file.setStorageRef(storage.put(encryption.encrypt(bytes), null));
    return files.saveAndFlush(file).getId();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<byte[]> read(UUID userId, UUID fileId) {
    return mine(userId, fileId).map(f -> encryption.decrypt(storage.get(f.getStorageRef())));
  }

  @Override
  @Transactional
  public void delete(UUID userId, UUID fileId) {
    mine(userId, fileId).ifPresent(f -> {
      files.delete(f);
      try {
        storage.delete(f.getStorageRef());
      } catch (RuntimeException gone) {
        // The row is gone; a missing blob is no reason to fail.
      }
    });
  }

  private Optional<FileEntity> mine(UUID userId, UUID fileId) {
    if (userId == null || fileId == null) return Optional.empty();
    return files.findById(fileId).filter(f -> f.getAgentId() == null && userId.equals(f.getUserId()));
  }

  @Override
  public String pdfText(byte[] bytes, String password) {
    boolean given = password != null && !password.isEmpty();
    try (PDDocument doc = given ? Loader.loadPDF(bytes, password) : Loader.loadPDF(bytes)) {
      if (doc.getNumberOfPages() > MAX_PAGES) {
        throw new ValidationException("This PDF has more than " + MAX_PAGES + " pages.");
      }
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      return stripper.getText(doc);
    } catch (InvalidPasswordException locked) {
      throw new PdfLockedException(given);
    } catch (IOException unreadable) {
      throw new ValidationException("Kai could not read this file as a PDF.");
    }
  }
}

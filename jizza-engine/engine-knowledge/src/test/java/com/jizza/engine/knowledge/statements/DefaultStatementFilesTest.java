package com.jizza.engine.knowledge.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.StatementFiles.PdfLockedException;
import com.jizza.engine.api.storage.StorageProvider;
import com.jizza.engine.persistence.entity.FileEntity;
import com.jizza.engine.persistence.repository.FileRepository;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultStatementFilesTest {

  private final UUID userId = UUID.randomUUID();
  private FileRepository files;
  private StorageProvider storage;
  private DefaultStatementFiles statements;

  @BeforeEach
  void setUp() {
    files = mock(FileRepository.class);
    storage = mock(StorageProvider.class);
    EncryptionService encryption = mock(EncryptionService.class);
    when(files.saveAndFlush(any())).thenAnswer(call -> {
      FileEntity f = call.getArgument(0);
      f.setId(UUID.randomUUID());
      return f;
    });
    when(encryption.encrypt(any())).thenReturn("ciphertext".getBytes(StandardCharsets.UTF_8));
    when(encryption.decrypt(any())).thenReturn("plain".getBytes(StandardCharsets.UTF_8));
    when(storage.put(any(), isNull())).thenReturn("ref-1");
    when(storage.get("ref-1")).thenReturn("ciphertext".getBytes(StandardCharsets.UTF_8));
    statements = new DefaultStatementFiles(files, storage, encryption, 1024 * 1024);
  }

  /** A small PDF with these lines, locked with {@code password} when it is not null. */
  static byte[] pdf(List<String> lines, String password) throws Exception {
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage();
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        cs.beginText();
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8);
        cs.setLeading(11);
        cs.newLineAtOffset(30, 760);
        for (String line : lines) {
          cs.showText(line);
          cs.newLine();
        }
        cs.endText();
      }
      if (password != null) {
        var policy = new StandardProtectionPolicy("owner-" + password, password, new AccessPermission());
        policy.setEncryptionKeyLength(128);
        doc.protect(policy);
      }
      var out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }
  }

  @Test
  void readsALockedPdfOnlyWithItsPassword() throws Exception {
    byte[] locked = pdf(List.of("Folio No: 123", "Closing Unit Balance: 1.000"), "ABCDE1234F");
    assertThat(statements.pdfText(locked, "ABCDE1234F")).contains("Folio No: 123").contains("Closing Unit Balance");
    assertThatThrownBy(() -> statements.pdfText(locked, "wrong"))
        .isInstanceOf(PdfLockedException.class).hasMessageContaining("password did not open");
    assertThatThrownBy(() -> statements.pdfText(locked, null))
        .isInstanceOf(PdfLockedException.class).hasMessageContaining("Enter its password");
    assertThat(statements.pdfText(pdf(List.of("Open file"), null), null)).contains("Open file");
  }

  @Test
  void refusesBytesThatAreNotAPdf() {
    assertThatThrownBy(() -> statements.pdfText("not a pdf".getBytes(StandardCharsets.UTF_8), null))
        .isInstanceOf(ValidationException.class).hasMessageContaining("PDF");
  }

  @Test
  void storesEncryptedAndReadsBackOnlyForTheOwner() {
    UUID id = statements.store(userId, "../bank/april.csv", "text/csv", "a,b".getBytes(StandardCharsets.UTF_8));
    FileEntity f = new FileEntity();
    f.setId(id);
    f.setUserId(userId);
    f.setStorageRef("ref-1");
    when(files.findById(id)).thenReturn(Optional.of(f));
    assertThat(statements.read(userId, id)).isPresent();
    assertThat(statements.read(UUID.randomUUID(), id)).isEmpty();
    statements.delete(UUID.randomUUID(), id);
    verify(files, never()).delete(any());
    statements.delete(userId, id);
    verify(files).delete(f);
    verify(storage).delete("ref-1");
  }
}

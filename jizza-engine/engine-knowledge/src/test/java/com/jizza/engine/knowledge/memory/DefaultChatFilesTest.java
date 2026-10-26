package com.jizza.engine.knowledge.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.chat.ChatFiles;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.storage.StorageProvider;
import com.jizza.engine.persistence.entity.FileEntity;
import com.jizza.engine.persistence.repository.FileRepository;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Chat attachments are encrypted at rest, belong to no agent, and only their owner reads them. */
class DefaultChatFilesTest {

  private final UUID userId = UUID.randomUUID();
  private FileRepository files;
  private StorageProvider storage;
  private EncryptionService encryption;
  private DefaultChatFiles chatFiles;

  @BeforeEach
  void setUp() {
    files = mock(FileRepository.class);
    storage = mock(StorageProvider.class);
    encryption = mock(EncryptionService.class);
    when(files.save(any())).thenAnswer(call -> {
      FileEntity f = call.getArgument(0);
      f.setId(UUID.randomUUID());
      return f;
    });
    when(encryption.encrypt(any())).thenReturn("ciphertext".getBytes(StandardCharsets.UTF_8));
    when(storage.put(any(), isNull())).thenReturn("ref-1");
    chatFiles = new DefaultChatFiles(files, storage, encryption, mock(AuditLog.class), 1024);
  }

  @Test
  void storesCiphertextWithNoAgentAndReturnsTheReadableText() {
    ChatFiles.Upload upload = chatFiles.add(userId, "notes/eye test.txt", "text/plain",
        "Right eye 6/9".getBytes(StandardCharsets.UTF_8));

    verify(storage).put(eq("ciphertext".getBytes(StandardCharsets.UTF_8)), isNull());
    ArgumentCaptor<FileEntity> saved = ArgumentCaptor.forClass(FileEntity.class);
    verify(files).save(saved.capture());
    assertThat(saved.getValue().getAgentId()).isNull();
    assertThat(saved.getValue().getUserId()).isEqualTo(userId);
    assertThat(upload.name()).isEqualTo("eye test.txt");
    assertThat(upload.text()).isEqualTo("Right eye 6/9");
  }

  @Test
  void onlyTheOwnerReadsAChatFileAndNeverAnAgentsMemoryFile() {
    FileEntity file = new FileEntity();
    file.setId(UUID.randomUUID());
    file.setUserId(userId);
    file.setFilename("scan.pdf");
    file.setContentType("application/pdf");
    file.setStorageRef("ref-1");
    when(files.findById(file.getId())).thenReturn(Optional.of(file));
    when(storage.get("ref-1")).thenReturn("ciphertext".getBytes(StandardCharsets.UTF_8));
    when(encryption.decrypt(any())).thenReturn(new byte[] {1, 2});

    assertThat(chatFiles.content(userId, file.getId())).hasValueSatisfying(c -> {
      assertThat(c.contentType()).isEqualTo("application/pdf");
      assertThat(c.bytes()).containsExactly(1, 2);
    });
    assertThat(chatFiles.content(UUID.randomUUID(), file.getId())).isEmpty();
    file.setAgentId(UUID.randomUUID());
    assertThat(chatFiles.content(userId, file.getId())).isEmpty();
  }
}

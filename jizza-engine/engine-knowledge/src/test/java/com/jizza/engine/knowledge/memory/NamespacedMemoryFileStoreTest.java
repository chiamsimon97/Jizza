package com.jizza.engine.knowledge.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.error.IsolationViolationException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.knowledge.KnowledgeEntry;
import com.jizza.engine.api.knowledge.KnowledgeStore;
import com.jizza.engine.api.knowledge.MemoryFile;
import com.jizza.engine.api.storage.StorageProvider;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.FileEntity;
import com.jizza.engine.persistence.repository.AgentRepository;
import com.jizza.engine.persistence.repository.FileRepository;
import com.jizza.engine.persistence.repository.KnowledgeEntryRepository;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Memory files are encrypted at rest and reachable only through the agent that holds them. */
class NamespacedMemoryFileStoreTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID vita = UUID.randomUUID();
  private final UUID kai = UUID.randomUUID();

  private FileRepository files;
  private KnowledgeStore knowledge;
  private StorageProvider storage;
  private EncryptionService encryption;
  private NamespacedMemoryFileStore store;

  @BeforeEach
  void setUp() {
    files = mock(FileRepository.class);
    knowledge = mock(KnowledgeStore.class);
    storage = mock(StorageProvider.class);
    encryption = mock(EncryptionService.class);
    AgentRepository agents = mock(AgentRepository.class);
    AgentEntity agent = mock(AgentEntity.class);
    when(agent.getUserId()).thenReturn(userId);
    when(agents.findById(vita)).thenReturn(Optional.of(agent));
    when(files.save(any())).thenAnswer(call -> {
      FileEntity f = call.getArgument(0);
      f.setId(UUID.randomUUID());
      return f;
    });
    when(encryption.encrypt(any())).thenReturn("ciphertext".getBytes(StandardCharsets.UTF_8));
    when(storage.put(any(), isNull())).thenReturn("ref-1");
    store = new NamespacedMemoryFileStore(files, agents, mock(KnowledgeEntryRepository.class),
        knowledge, storage, encryption, mock(AuditLog.class), 1024);
  }

  @Test
  void storesOnlyCiphertextAndIndexesReadableText() {
    UUID entryId = UUID.randomUUID();
    when(knowledge.upsert(eq(vita), any())).thenAnswer(call -> {
      KnowledgeEntry e = call.getArgument(1);
      return new KnowledgeEntry(entryId, e.userId(), e.agentId(), e.title(), e.content(), e.data(),
          e.classification(), null, null, null);
    });

    MemoryFile file = store.add(vita, "../../labs.txt", "text/plain",
        "Cholesterol 180".getBytes(StandardCharsets.UTF_8));

    verify(storage).put(eq("ciphertext".getBytes(StandardCharsets.UTF_8)), isNull());
    assertThat(file.filename()).isEqualTo("labs.txt");
    assertThat(file.readableChars()).isEqualTo("Cholesterol 180".length());
    ArgumentCaptor<FileEntity> saved = ArgumentCaptor.forClass(FileEntity.class);
    verify(files).save(saved.capture());
    assertThat(saved.getValue().getAgentId()).isEqualTo(vita);
    assertThat(saved.getValue().getKnowledgeEntryId()).isEqualTo(entryId);
  }

  @Test
  void imagesAreKeptWithoutIndexing() {
    MemoryFile file = store.add(vita, "xray.png", "image/png", new byte[] {(byte) 0x89, 'P'});
    assertThat(file.readableChars()).isZero();
    verify(knowledge, never()).upsert(any(), any());
  }

  @Test
  void refusesEmptyAndOversizedFiles() {
    assertThatThrownBy(() -> store.add(vita, "a.txt", "text/plain", new byte[0]))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> store.add(vita, "a.txt", "text/plain", new byte[2048]))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void anotherAgentCannotReadOrDeleteTheFile() {
    FileEntity file = new FileEntity();
    file.setId(UUID.randomUUID());
    file.setUserId(userId);
    file.setAgentId(vita);
    file.setStorageRef("ref-1");
    when(files.findById(file.getId())).thenReturn(Optional.of(file));

    assertThatThrownBy(() -> store.content(kai, file.getId()))
        .isInstanceOf(IsolationViolationException.class);
    assertThatThrownBy(() -> store.delete(kai, file.getId()))
        .isInstanceOf(IsolationViolationException.class);
    verify(storage, never()).get(any());
    verify(files, never()).delete(any());
  }

  @Test
  void cleansDisplayNames() {
    assertThat(NamespacedMemoryFileStore.cleanName("C:\\Users\\me\\scan.pdf")).isEqualTo("scan.pdf");
    assertThat(NamespacedMemoryFileStore.cleanName("  ")).isEqualTo("file");
  }
}

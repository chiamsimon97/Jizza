package com.jizza.platform.usecase.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.DataClassification;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.knowledge.KnowledgeEntry;
import com.jizza.engine.api.knowledge.KnowledgeStore;
import com.jizza.engine.api.knowledge.MemoryFileStore;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.util.Json;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The memory page shows an agent's own notes and files, and only to the agent's owner. */
class AgentMemoryUseCaseTest {

  private final UUID userId = UUID.randomUUID();
  private final AgentDto vita = new AgentDto(UUID.randomUUID(), userId, "health", "Vita", "health",
      "prompt", false, true);
  private KnowledgeStore knowledge;
  private MemoryFileStore files;
  private AgentMemoryUseCase useCase;

  @BeforeEach
  void setUp() {
    AgentDirectory directory = mock(AgentDirectory.class);
    when(directory.byId(vita.id())).thenReturn(Optional.of(vita));
    knowledge = mock(KnowledgeStore.class);
    files = mock(MemoryFileStore.class);
    useCase = new AgentMemoryUseCase(directory, knowledge, files);
  }

  @Test
  void showsSavedNotesButNotTextReadFromFiles() {
    KnowledgeEntry note = new KnowledgeEntry(UUID.randomUUID(), userId, vita.id(), "Allergy",
        "Penicillin", null, DataClassification.HEALTH, null, null, null);
    KnowledgeEntry fromFile = new KnowledgeEntry(UUID.randomUUID(), userId, vita.id(), "labs.pdf",
        "...", Json.obj().put("source", "file"), DataClassification.PII, null, null, null);
    when(knowledge.list(vita.id(), 200)).thenReturn(List.of(note, fromFile));
    when(files.list(vita.id())).thenReturn(List.of());

    var memory = useCase.view(userId, vita.id());

    assertThat(memory.notes()).extracting(AgentMemoryUseCase.MemoryNote::title)
        .containsExactly("Allergy");
  }

  @Test
  void anotherUsersAgentIsNotFound() {
    UUID stranger = UUID.randomUUID();
    assertThatThrownBy(() -> useCase.view(stranger, vita.id()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> useCase.addFile(stranger, vita.id(), "a.txt", "text/plain",
        new byte[] {1})).isInstanceOf(NotFoundException.class);
    verify(files, never()).add(any(), any(), any(), any());
  }

  @Test
  void acceptsDocumentsAndImagesAndRefusesTheRest() {
    assertThat(AgentMemoryUseCase.resolveType("scan.pdf", "application/pdf"))
        .isEqualTo("application/pdf");
    assertThat(AgentMemoryUseCase.resolveType("photo.JPG", "application/octet-stream"))
        .isEqualTo("image/jpeg");
    assertThat(AgentMemoryUseCase.resolveType("notes.txt", "text/plain; charset=utf-8"))
        .isEqualTo("text/plain");
    assertThatThrownBy(() -> AgentMemoryUseCase.resolveType("logo.svg", "image/svg+xml"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> AgentMemoryUseCase.resolveType("run.exe", ""))
        .isInstanceOf(ValidationException.class);
  }
}

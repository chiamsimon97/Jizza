package com.jizza.platform.usecase.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.WorkflowDto;
import com.jizza.engine.api.enums.WorkflowOrigin;
import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workflow.WorkflowRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The builder's own save: what a fresh workflow is, and that a user cannot touch another's. */
class WorkflowUseCaseTest {

  private final UUID userId = UUID.randomUUID();
  private final List<WorkflowDto> rows = new ArrayList<>();
  private WorkflowUseCase useCase;

  /** Stands in for the engine's registry: persists whatever it is handed, defaults nothing. */
  private final WorkflowRegistry registry = new WorkflowRegistry() {
    @Override public Optional<WorkflowDto> find(UUID id) {
      return rows.stream().filter(w -> w.id().equals(id)).findFirst();
    }

    @Override public Optional<WorkflowDto> findVersion(UUID id, int version) {
      throw new UnsupportedOperationException();
    }

    @Override public List<WorkflowDto> versions(UUID id) {
      return rows.stream().filter(w -> w.id().equals(id)).toList();
    }

    @Override public List<WorkflowDto> list(UUID owner) {
      return rows.stream().filter(w -> w.userId().equals(owner)).toList();
    }

    @Override public WorkflowDto save(WorkflowDto dto) {
      WorkflowDto saved = dto.id() == null
          ? new WorkflowDto(UUID.randomUUID(), dto.userId(), dto.name(), dto.description(),
              dto.graph(), dto.inputSchema(), dto.triggerConfig(), dto.origin(), dto.status(),
              dto.owningAgentId(), 1, dto.sourceTaskId(), Instant.now())
          : merge(find(dto.id()).orElseThrow(), dto);
      rows.removeIf(w -> w.id().equals(saved.id()));
      rows.add(saved);
      return saved;
    }

    private WorkflowDto merge(WorkflowDto existing, WorkflowDto edit) {
      return new WorkflowDto(existing.id(), existing.userId(), edit.name(), edit.description(),
          edit.graph(), edit.inputSchema(), edit.triggerConfig(), existing.origin(),
          existing.status(), existing.owningAgentId(), existing.version(),
          existing.sourceTaskId(), existing.createdAt());
    }
  };

  @BeforeEach
  void setUp() {
    rows.clear();
    useCase = new WorkflowUseCase(registry, mock(AuditLog.class));
  }

  private JsonNode graph() {
    return Json.obj().put("nodes", "irrelevant-for-this-test");
  }

  @Test
  void aNewWorkflowFromTheBuilderStartsUserBuiltAndDraft() {
    var saved = useCase.save(userId, null, "My workflow", "desc", graph(), null, null, null);

    assertThat(saved.origin()).isEqualTo(WorkflowOrigin.USER_BUILT);
    assertThat(saved.status()).isEqualTo(WorkflowStatus.DRAFT);
    assertThat(saved.userId()).isEqualTo(userId);
  }

  @Test
  void editingDoesNotDisturbOriginOrStatus() {
    var created = useCase.save(userId, null, "My workflow", "desc", graph(), null, null, null);

    var edited = useCase.save(userId, created.id(), "Renamed", "desc", graph(), null, null, null);

    assertThat(edited.id()).isEqualTo(created.id());
    assertThat(edited.name()).isEqualTo("Renamed");
    assertThat(edited.origin()).isEqualTo(created.origin());
    assertThat(edited.status()).isEqualTo(created.status());
  }

  @Test
  void anotherUsersWorkflowIsReportedMissingRatherThanForbidden() {
    var created = useCase.save(userId, null, "My workflow", "desc", graph(), null, null, null);
    UUID somebodyElse = UUID.randomUUID();

    assertThatThrownBy(() -> useCase.get(somebodyElse, created.id()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() ->
        useCase.save(somebodyElse, created.id(), "Hijacked", null, graph(), null, null, null))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void listingOnlyReturnsTheCallersOwnWorkflows() {
    useCase.save(userId, null, "Mine", "d", graph(), null, null, null);
    useCase.save(UUID.randomUUID(), null, "Somebody else's", "d", graph(), null, null, null);

    assertThat(useCase.list(userId)).hasSize(1);
  }
}

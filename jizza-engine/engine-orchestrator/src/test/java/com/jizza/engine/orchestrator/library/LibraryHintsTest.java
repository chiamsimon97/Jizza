package com.jizza.engine.orchestrator.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.enums.WorkflowStatus;
import com.jizza.engine.api.workflow.library.ReuseProfile;
import com.jizza.engine.api.workflow.library.WorkflowLibrary;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class LibraryHintsTest {

  private final UUID userId = UUID.randomUUID();

  @SuppressWarnings("unchecked")
  private LibraryHints hints(WorkflowLibrary library) {
    ObjectProvider<WorkflowLibrary> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(library);
    return new LibraryHints(provider);
  }

  @Test
  void aRequestWithASavedWorkflowGetsItNamedForThisTurn() {
    WorkflowLibrary library = mock(WorkflowLibrary.class);
    UUID id = UUID.randomUUID();
    when(library.find(any(), anyString(), any(), anyInt())).thenReturn(List.of(
        new WorkflowLibrary.Match(id, "Gold rate checker", null, WorkflowStatus.APPROVED, 1,
            new ReuseProfile("When I ask for the gold rate", List.of(), List.of(), null, null),
            0.7, List.of("gold"))));

    String message = hints(library).annotate(userId, "check the gold rate");

    assertThat(message).startsWith("check the gold rate\n\n[Saved workflows")
        .contains("\"Gold rate checker\" (workflow_id " + id + "): When I ask for the gold rate")
        .contains("run_saved_workflow");
  }

  @Test
  void nothingMatchingOrNoLibraryLeavesTheMessageAlone() {
    WorkflowLibrary library = mock(WorkflowLibrary.class);
    when(library.find(any(), anyString(), any(), anyInt())).thenReturn(List.of());

    assertThat(hints(library).annotate(userId, "hello")).isEqualTo("hello");
    assertThat(hints(null).annotate(userId, "hello")).isEqualTo("hello");
  }

  @Test
  void aFailedLookupNeverCostsTheReply() {
    WorkflowLibrary library = mock(WorkflowLibrary.class);
    when(library.find(any(), anyString(), any(), anyInt())).thenThrow(new IllegalStateException("db"));

    assertThat(hints(library).annotate(userId, "check the gold rate")).isEqualTo("check the gold rate");
  }
}

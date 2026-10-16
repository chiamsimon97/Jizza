package com.jizza.platform.usecase.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.connector.ToolCatalog;
import com.jizza.engine.api.dto.ConnectionDto;
import com.jizza.engine.api.dto.GeneratedToolDto;
import com.jizza.engine.api.error.NotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The ownership check that was missing from the engine's own setEnabled: a tool belonging to
 * somebody else's connection must not be toggled just because its id was guessed.
 */
class GeneratedToolUseCaseTest {

  private final UUID userId = UUID.randomUUID();

  /** Each person acting in their own account; the workspace id doubles as the user id here. */
  private static com.jizza.engine.api.authz.Access access(UUID user) {
    return new com.jizza.engine.api.authz.Access(user, user);
  }
  private final UUID connectionId = UUID.randomUUID();
  private final UUID toolId = UUID.randomUUID();
  private ToolCatalog tools;
  private ConnectionUseCase connections;
  private GeneratedToolUseCase useCase;

  private GeneratedToolDto tool() {
    return new GeneratedToolDto(toolId, connectionId, "listpets", "desc", null, "GET", "/pets",
        null, null, false, true);
  }

  @BeforeEach
  void setUp() {
    tools = mock(ToolCatalog.class);
    when(tools.find(toolId)).thenReturn(Optional.of(tool()));
    connections = mock(ConnectionUseCase.class);
    useCase = new GeneratedToolUseCase(tools, connections);
  }

  @Test
  void enablingAToolOnAnOwnedConnectionDelegatesToTheEngine() {
    when(connections.ownedBy(access(userId), connectionId)).thenReturn(mock(ConnectionDto.class));
    when(tools.setEnabled(toolId, false)).thenReturn(tool());

    useCase.setEnabled(access(userId), toolId, false);

    verify(connections).ownedBy(access(userId), connectionId);
    verify(tools).setEnabled(toolId, false);
  }

  @Test
  void aToolOnAnotherUsersConnectionCannotBeToggled() {
    UUID somebodyElse = UUID.randomUUID();
    when(connections.ownedBy(access(somebodyElse), connectionId))
        .thenThrow(new NotFoundException("Connection", connectionId));

    assertThatThrownBy(() -> useCase.setEnabled(access(somebodyElse), toolId, true))
        .isInstanceOf(NotFoundException.class);

    verify(tools, never()).setEnabled(toolId, true);
  }

  @Test
  void anUnknownToolIsReportedMissing() {
    when(tools.find(toolId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.setEnabled(access(userId), toolId, true))
        .isInstanceOf(NotFoundException.class);
  }
}

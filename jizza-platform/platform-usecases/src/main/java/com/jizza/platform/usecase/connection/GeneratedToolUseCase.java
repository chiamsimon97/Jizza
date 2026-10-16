package com.jizza.platform.usecase.connection;

import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.connector.ToolCatalog;
import com.jizza.engine.api.dto.GeneratedToolDto;
import com.jizza.engine.api.error.NotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enabling and disabling a tool generated from an attached API document (R26).
 *
 * <p>{@code setEnabled} takes a bare tool id, which carries no user of its own -- a tool belongs
 * to a connection, and a connection belongs to a user. Without this check, any authenticated caller
 * who guessed or enumerated a tool id could enable or disable a tool on somebody else's connection;
 * this class resolves the owning connection first and refuses if it is not the caller's.
 */
@Service
public class GeneratedToolUseCase {

  private final ToolCatalog tools;
  private final ConnectionUseCase connections;

  public GeneratedToolUseCase(ToolCatalog tools, ConnectionUseCase connections) {
    this.tools = tools;
    this.connections = connections;
  }

  @Transactional
  public GeneratedToolDto setEnabled(Access access, UUID toolId, boolean enabled) {
    GeneratedToolDto tool = tools.find(toolId)
        .orElseThrow(() -> new NotFoundException("Generated tool", toolId));
    // Throws NotFoundException itself if the connection is not this user's.
    connections.ownedBy(access, tool.connectionId());
    return tools.setEnabled(toolId, enabled);
  }
}

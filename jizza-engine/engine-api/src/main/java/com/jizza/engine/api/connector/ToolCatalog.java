package com.jizza.engine.api.connector;

import com.jizza.engine.api.dto.GeneratedToolDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read access and enable/disable over the tools generated for a connection (R26). */
public interface ToolCatalog {

  List<GeneratedToolDto> list(UUID connectionId);

  Optional<GeneratedToolDto> find(UUID toolId);

  GeneratedToolDto setEnabled(UUID toolId, boolean enabled);
}

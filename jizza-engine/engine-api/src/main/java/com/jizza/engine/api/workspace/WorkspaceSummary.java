package com.jizza.engine.api.workspace;

import com.jizza.engine.api.enums.WorkspaceRole;
import com.jizza.engine.api.enums.WorkspaceType;
import java.util.UUID;

/** An account the caller belongs to, with their role in it. */
public record WorkspaceSummary(UUID id, WorkspaceType type, String name, WorkspaceRole role) {}

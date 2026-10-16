package com.jizza.engine.api.authz;

import com.jizza.engine.api.enums.WorkspaceRole;
import com.jizza.engine.api.enums.WorkspaceType;
import java.util.List;
import java.util.Set;

/** One entry of {@code security/permissions.json}. */
public record PermissionDefinition(
    String name,
    String label,
    String description,
    String category,
    String module,
    List<String> dependencies,
    Set<WorkspaceType> permittedAccountTypes,
    Set<WorkspaceRole> permittedRoles) {

  public boolean grants(WorkspaceRole role, WorkspaceType type) {
    return role != null && type != null && permittedRoles.contains(role)
        && permittedAccountTypes.contains(type);
  }
}

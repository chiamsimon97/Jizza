package com.jizza.engine.api.authz;

import com.jizza.engine.api.enums.WorkspaceRole;
import com.jizza.engine.api.enums.WorkspaceType;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The permission definitions, loaded once at startup and immutable afterwards. */
public interface PermissionCatalog {

  Optional<PermissionDefinition> find(String name);

  List<PermissionDefinition> all();

  /** Names granted to a role in a workspace of the given type. */
  Set<String> granted(WorkspaceRole role, WorkspaceType type);

  /** SHA-256 of the definitions file, so a client can tell when its copy is stale. */
  String version();
}

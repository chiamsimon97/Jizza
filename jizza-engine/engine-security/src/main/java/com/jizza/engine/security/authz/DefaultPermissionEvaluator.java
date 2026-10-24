package com.jizza.engine.security.authz;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.authz.PermissionCatalog;
import com.jizza.engine.api.authz.PermissionDefinition;
import com.jizza.engine.api.authz.PermissionEvaluator;
import com.jizza.engine.api.authz.RoleAssignmentPolicy;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.PlatformRole;
import com.jizza.engine.api.enums.WorkspaceRole;
import com.jizza.engine.api.enums.WorkspaceType;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workspace.AuthorizationSnapshot;
import com.jizza.engine.api.workspace.Membership;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import com.jizza.engine.api.workspace.WorkspaceSummary;
import com.jizza.engine.security.session.CurrentUser;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Role-based access in a workspace. A permission is allowed when the person has an active
 * membership there and the permission's definition grants their role in that type of account. The
 * super admin is the one exception, built into code rather than the definitions file so a bad edit
 * can never lock the product owner out; it needs a session that passed two-step sign-in, and every
 * use that changed the outcome is audited.
 */
@Service
public class DefaultPermissionEvaluator implements PermissionEvaluator {

  private final PermissionCatalog catalog;
  private final WorkspaceDirectory directory;
  private final AuditLog audit;

  public DefaultPermissionEvaluator(PermissionCatalog catalog, WorkspaceDirectory directory,
                                    AuditLog audit) {
    this.catalog = catalog;
    this.directory = directory;
    this.audit = audit;
  }

  @Override
  public boolean isAllowed(UUID userId, UUID workspaceId, String permission) {
    if (userId == null || workspaceId == null || permission == null) return false;
    Optional<PermissionDefinition> def = catalog.find(permission);
    if (def.isEmpty()) return false;

    Optional<Membership> membership = directory.membership(workspaceId, userId)
        .filter(Membership::active);
    if (membership.isPresent()
        && def.get().grants(membership.get().role(), membership.get().workspaceType())) {
      return true;
    }
    if (superAdminInSession(userId) && directory.workspace(workspaceId).isPresent()) {
      audit.record(userId, ActorType.USER, userId, "authz.super_admin_override", permission,
          Json.obj().put("workspaceId", workspaceId.toString()));
      return true;
    }
    return false;
  }

  @Override
  public AuthorizationSnapshot snapshot(UUID userId, UUID workspaceId) {
    String email = directory.emailOf(userId).orElse(null);
    PlatformRole platformRole = directory.platformRoleOf(userId);
    List<Membership> memberships = directory.membershipsOf(userId).stream()
        .filter(Membership::active).toList();

    Optional<Membership> active = memberships.stream()
        .filter(m -> m.workspaceId().equals(workspaceId)).findFirst();
    WorkspaceSummary activeSummary;
    WorkspaceRole role;
    Set<String> permissions;
    if (active.isPresent()) {
      activeSummary = active.get().summary();
      role = active.get().role();
      permissions = new TreeSet<>(catalog.granted(role, active.get().workspaceType()));
    } else {
      var ws = directory.workspace(workspaceId).orElseThrow();
      activeSummary = new WorkspaceSummary(ws.id(), ws.type(), ws.name(), null);
      role = null;
      permissions = new TreeSet<>();
    }
    if (superAdminInSession(userId)) {
      catalog.all().forEach(d -> permissions.add(d.name()));
    }
    Set<WorkspaceRole> assignable = activeSummary.type() == WorkspaceType.PERSONAL
        ? EnumSet.noneOf(WorkspaceRole.class) : RoleAssignmentPolicy.assignableRoles(role);

    Instant now = Instant.now();
    long pending = directory.pendingFor(userId, email).stream()
        .filter(i -> !i.expired(now)).count();
    return new AuthorizationSnapshot(userId, email, platformRole, activeSummary, role, permissions,
        assignable, memberships.stream().map(Membership::summary).collect(Collectors.toList()),
        pending, catalog.version());
  }

  @Override
  public boolean isSuperAdmin(UUID userId) {
    return userId != null && superAdminInSession(userId);
  }

  /** The super admin's override only counts inside a request whose session passed two-step. */
  private boolean superAdminInSession(UUID userId) {
    var principal = CurrentUser.principal();
    if (principal.isEmpty() || !principal.get().userId().equals(userId)) return false;
    if (!principal.get().mfaSatisfied()) return false;
    return directory.platformRoleOf(userId) == PlatformRole.SUPER_ADMIN;
  }
}

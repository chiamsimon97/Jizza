package com.jizza.platform.usecase.listing;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.authz.PermissionEvaluator;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.WorkspaceRole;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.event.MembershipChanged;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workspace.Membership;
import com.jizza.engine.api.workspace.PublicListingStore;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Super Admin's Public roles page: everyone's role in the platform's Public organization.
 * Everyone joins as a User; the Super Admin makes people Developers or Admins, which is what lets
 * them publish there. The Owner stays the Super Admin's account.
 */
@Service
public class PublicRolesUseCase {

  /** The roles the Super Admin can hand out. */
  public static final Set<WorkspaceRole> ASSIGNABLE =
      Set.of(WorkspaceRole.ADMIN, WorkspaceRole.DEVELOPER, WorkspaceRole.USER);

  public record Roles(UUID workspaceId, String workspaceName, List<Membership> members) {}

  private final PublicListingStore store;
  private final WorkspaceDirectory directory;
  private final PermissionEvaluator permissions;
  private final AuditLog audit;
  private final ApplicationEventPublisher events;

  public PublicRolesUseCase(PublicListingStore store, WorkspaceDirectory directory,
                            PermissionEvaluator permissions, AuditLog audit,
                            ApplicationEventPublisher events) {
    this.store = store;
    this.directory = directory;
    this.permissions = permissions;
    this.audit = audit;
    this.events = events;
  }

  @Transactional(readOnly = true)
  public Roles list(UUID userId, String query) {
    requireSuperAdmin(userId);
    UUID org = organization();
    String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    List<Membership> members = directory.members(org).stream()
        .filter(m -> q.isEmpty() || m.email().toLowerCase(Locale.ROOT).contains(q))
        .sorted(Comparator.comparing((Membership m) -> m.role().ordinal())
            .thenComparing(Membership::email))
        .toList();
    String name = directory.workspace(org).map(WorkspaceDirectory.Workspace::name).orElse("");
    return new Roles(org, name, members);
  }

  @Transactional
  public Membership setRole(UUID userId, UUID memberId, WorkspaceRole role) {
    requireSuperAdmin(userId);
    UUID org = organization();
    if (role == null || !ASSIGNABLE.contains(role)) {
      throw new ValidationException("Choose Admin, Developer or User");
    }
    Membership target = directory.member(memberId)
        .filter(m -> m.workspaceId().equals(org))
        .orElseThrow(() -> new NotFoundException("Member", memberId));
    if (target.role() == WorkspaceRole.OWNER) {
      throw new JizzaException(HttpStatus.CONFLICT, "owner-role-fixed", "The owner stays the owner",
          "The Public organization is owned by the Super Admin's account.");
    }
    if (target.role() == role) return target;
    Membership updated = directory.changeRole(memberId, role);
    var detail = Json.obj().put("workspaceId", org.toString())
        .put("before", target.role().name()).put("after", role.name());
    audit.record(userId, ActorType.USER, userId, "member.role_changed", target.email(), detail);
    audit.record(target.userId(), ActorType.USER, userId, "member.role_changed", target.email(),
        detail);
    events.publishEvent(new MembershipChanged(org, target.userId(),
        MembershipChanged.Kind.ROLE_CHANGED));
    return updated;
  }

  private UUID organization() {
    return store.platformOrganization().orElseThrow(() -> new JizzaException(HttpStatus.CONFLICT,
        "no-platform-organization", "No Public organization",
        "The Public organization hasn't been set up yet. Restart Jizza to create it."));
  }

  private void requireSuperAdmin(UUID userId) {
    if (!permissions.isSuperAdmin(userId)) {
      throw new JizzaException(HttpStatus.FORBIDDEN, "super-admin-only", "Super Admin only",
          "Only a Super Admin signed in with two-step verification can change Public roles.");
    }
  }
}

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
import com.jizza.engine.api.workspace.PublicListingStore.RegisteredAccount;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Super Admin's Registered accounts page: every Individual and Team account registered in the
 * Public organization, and each person's role there. Everyone counts as a User without being a
 * member; making someone an Admin or Developer adds them as a member, and setting them back to User
 * removes them. The Owner stays the Super Admin's account.
 */
@Service
public class PublicRolesUseCase {

  /** The roles the Super Admin can hand out. */
  public static final Set<WorkspaceRole> ASSIGNABLE =
      Set.of(WorkspaceRole.ADMIN, WorkspaceRole.DEVELOPER, WorkspaceRole.USER);

  public record Registry(UUID workspaceId, String workspaceName, List<RegisteredAccount> accounts) {}

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
  public Registry list(UUID userId, String query) {
    requireSuperAdmin(userId);
    UUID org = organization();
    String name = directory.workspace(org).map(WorkspaceDirectory.Workspace::name).orElse("");
    return new Registry(org, name, store.registeredAccounts(query));
  }

  /** Sets a person's role in the Public organization. Returns their role afterwards. */
  @Transactional
  public WorkspaceRole setRole(UUID userId, UUID personId, WorkspaceRole role) {
    requireSuperAdmin(userId);
    UUID org = organization();
    if (role == null || !ASSIGNABLE.contains(role)) {
      throw new ValidationException("Choose Admin, Developer or User");
    }
    String email = directory.emailOf(personId)
        .orElseThrow(() -> new NotFoundException("Person", personId));
    Optional<Membership> current = directory.membership(org, personId);
    WorkspaceRole before = current.map(Membership::role).orElse(WorkspaceRole.USER);
    if (before == WorkspaceRole.OWNER) {
      throw new JizzaException(HttpStatus.CONFLICT, "owner-role-fixed", "The owner stays the owner",
          "The Public organization is owned by the Super Admin's account.");
    }
    if (before == role) return role;
    MembershipChanged.Kind kind;
    if (role == WorkspaceRole.USER) {
      directory.removeMember(current.orElseThrow().id());
      kind = MembershipChanged.Kind.REMOVED;
    } else if (current.isPresent()) {
      directory.changeRole(current.get().id(), role);
      kind = MembershipChanged.Kind.ROLE_CHANGED;
    } else {
      directory.addMember(org, personId, role);
      kind = MembershipChanged.Kind.ROLE_CHANGED;
    }
    var detail = Json.obj().put("workspaceId", org.toString())
        .put("before", before.name()).put("after", role.name());
    audit.record(userId, ActorType.USER, userId, "member.role_changed", email, detail);
    audit.record(personId, ActorType.USER, userId, "member.role_changed", email, detail);
    events.publishEvent(new MembershipChanged(org, personId, kind));
    return role;
  }

  private UUID organization() {
    return store.platformOrganization().orElseThrow(() -> new JizzaException(HttpStatus.CONFLICT,
        "no-platform-organization", "No Public organization",
        "The Public organization hasn't been set up yet. Restart Jizza to create it."));
  }

  private void requireSuperAdmin(UUID userId) {
    if (!permissions.isSuperAdmin(userId)) {
      throw new JizzaException(HttpStatus.FORBIDDEN, "super-admin-only", "Super Admin only",
          "Only a Super Admin signed in with two-step verification can see registered accounts.");
    }
  }
}

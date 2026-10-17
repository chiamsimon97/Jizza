package com.jizza.platform.usecase.workspace;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.PlatformRole;
import com.jizza.engine.api.enums.WorkspaceRole;
import com.jizza.engine.api.enums.WorkspaceType;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.event.MembershipChanged;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workspace.Membership;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import com.jizza.engine.api.workspace.WorkspaceSummary;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The accounts a person can act in: creating an organization, choosing which account to act in,
 * and leaving one. Issuing the new session for a switch is the HTTP layer's job; deciding whether
 * the switch is allowed is this class's.
 */
@Service
public class WorkspaceUseCase {

  private final WorkspaceDirectory directory;
  private final AuditLog audit;
  private final ApplicationEventPublisher events;

  public WorkspaceUseCase(WorkspaceDirectory directory, AuditLog audit,
                          ApplicationEventPublisher events) {
    this.directory = directory;
    this.audit = audit;
    this.events = events;
  }

  @Transactional
  public WorkspaceSummary createOrganization(UUID userId, String name) {
    String trimmed = name == null ? "" : name.trim();
    if (trimmed.length() < 2 || trimmed.length() > 100) {
      throw new ValidationException("The organization name must be 2 to 100 characters");
    }
    var ws = directory.createOrganization(userId, trimmed);
    audit.record(userId, ActorType.USER, userId, "workspace.created", trimmed,
        Json.obj().put("workspaceId", ws.id().toString()).put("type", WorkspaceType.ORGANIZATION.name()));
    return new WorkspaceSummary(ws.id(), ws.type(), ws.name(), WorkspaceRole.OWNER);
  }

  /**
   * Confirms the person may act in {@code workspaceId} and remembers it for their next sign-in.
   * Anything they do not belong to is reported as missing. The super admin may enter any account.
   */
  @Transactional
  public void switchTo(UUID userId, UUID workspaceId) {
    boolean member = directory.membership(workspaceId, userId).filter(Membership::active).isPresent();
    boolean superAdmin = directory.platformRoleOf(userId) == PlatformRole.SUPER_ADMIN
        && directory.workspace(workspaceId).isPresent();
    if (!member && !superAdmin) throw new NotFoundException("Account", workspaceId);
    directory.rememberActiveWorkspace(userId, workspaceId);
    audit.record(userId, ActorType.USER, userId, member ? "workspace.switched"
        : "authz.super_admin_override", workspaceId.toString(), null);
  }

  /** Leaves an organization. The owner cannot leave (ownership transfer is not offered yet). */
  @Transactional
  public UUID leave(UUID userId, UUID workspaceId) {
    Membership m = directory.membership(workspaceId, userId)
        .orElseThrow(() -> new NotFoundException("Account", workspaceId));
    if (m.workspaceType() == WorkspaceType.PERSONAL) {
      throw new JizzaException(HttpStatus.CONFLICT, "personal-workspace-no-members",
          "That's your own account", "You can't leave your own Individual account.");
    }
    if (m.role() == WorkspaceRole.OWNER) {
      throw new JizzaException(HttpStatus.CONFLICT, "owner-cannot-leave", "The owner can't leave",
          "The owner can't leave the organization.");
    }
    directory.removeMember(m.id());
    UUID personal = directory.personalWorkspaceOf(userId);
    directory.rememberActiveWorkspace(userId, personal);
    audit.record(userId, ActorType.USER, userId, "member.left", m.workspaceName(),
        Json.obj().put("workspaceId", workspaceId.toString()));
    events.publishEvent(new MembershipChanged(workspaceId, userId, MembershipChanged.Kind.LEFT));
    return personal;
  }
}

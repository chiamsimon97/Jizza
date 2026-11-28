package com.jizza.engine.api.authz;

import com.jizza.engine.api.enums.WorkspaceRole;
import java.util.EnumSet;
import java.util.Set;

/**
 * Who may give which role to whom inside a workspace. Ownership is never handed out here (transfer
 * is a separate, future flow), nobody changes their own role, and an admin cannot touch another
 * admin or the owner.
 */
public final class RoleAssignmentPolicy {

  private RoleAssignmentPolicy() {}

  /** Roles {@code actor} may invite someone as, or change someone to. */
  public static Set<WorkspaceRole> assignableRoles(WorkspaceRole actor) {
    if (actor == null) return EnumSet.noneOf(WorkspaceRole.class);
    return switch (actor) {
      case OWNER -> EnumSet.of(WorkspaceRole.ADMIN, WorkspaceRole.DEVELOPER, WorkspaceRole.USER);
      case ADMIN -> EnumSet.of(WorkspaceRole.DEVELOPER, WorkspaceRole.USER);
      default -> EnumSet.noneOf(WorkspaceRole.class);
    };
  }

  /**
   * Roles {@code actor} may assign in an account of {@code type}: none in an Individual account;
   * in a Family the same as an organization but never Developer (spec 4.4).
   */
  public static Set<WorkspaceRole> assignableRoles(WorkspaceRole actor, com.jizza.engine.api.enums.WorkspaceType type) {
    if (type == null || !type.hasMembers()) return EnumSet.noneOf(WorkspaceRole.class);
    Set<WorkspaceRole> roles = EnumSet.copyOf(assignableRoles(actor).isEmpty()
        ? EnumSet.noneOf(WorkspaceRole.class) : assignableRoles(actor));
    if (type == com.jizza.engine.api.enums.WorkspaceType.FAMILY) roles.remove(WorkspaceRole.DEVELOPER);
    return roles;
  }

  public static boolean canInvite(WorkspaceRole actor, WorkspaceRole role) {
    return assignableRoles(actor).contains(role);
  }

  public static boolean canInvite(WorkspaceRole actor, WorkspaceRole role, com.jizza.engine.api.enums.WorkspaceType type) {
    return assignableRoles(actor, type).contains(role);
  }

  public static boolean canChangeRole(WorkspaceRole actor, WorkspaceRole target, WorkspaceRole newRole, boolean self,
                                      com.jizza.engine.api.enums.WorkspaceType type) {
    return canManage(actor, target, self) && assignableRoles(actor, type).contains(newRole);
  }

  /** Whether {@code actor} may manage (change or remove) a member currently holding {@code target}. */
  public static boolean canManage(WorkspaceRole actor, WorkspaceRole target, boolean self) {
    if (self || actor == null || target == null || target == WorkspaceRole.OWNER) return false;
    return switch (actor) {
      case OWNER -> true;
      case ADMIN -> target == WorkspaceRole.DEVELOPER || target == WorkspaceRole.USER;
      default -> false;
    };
  }

  public static boolean canChangeRole(WorkspaceRole actor, WorkspaceRole target, WorkspaceRole newRole,
                                      boolean self) {
    return canManage(actor, target, self) && assignableRoles(actor).contains(newRole);
  }
}

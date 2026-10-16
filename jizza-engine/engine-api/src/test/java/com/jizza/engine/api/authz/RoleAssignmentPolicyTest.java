package com.jizza.engine.api.authz;

import static com.jizza.engine.api.enums.WorkspaceRole.ADMIN;
import static com.jizza.engine.api.enums.WorkspaceRole.DEVELOPER;
import static com.jizza.engine.api.enums.WorkspaceRole.OWNER;
import static com.jizza.engine.api.enums.WorkspaceRole.USER;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoleAssignmentPolicyTest {

  @Test
  void ownershipIsNeverHandedOut() {
    assertThat(RoleAssignmentPolicy.assignableRoles(OWNER)).containsExactlyInAnyOrder(ADMIN, DEVELOPER, USER);
    assertThat(RoleAssignmentPolicy.canInvite(OWNER, OWNER)).isFalse();
  }

  @Test
  void anAdminCanOnlyBringInDevelopersAndUsers() {
    assertThat(RoleAssignmentPolicy.canInvite(ADMIN, DEVELOPER)).isTrue();
    assertThat(RoleAssignmentPolicy.canInvite(ADMIN, ADMIN)).isFalse();
    assertThat(RoleAssignmentPolicy.canManage(ADMIN, ADMIN, false)).isFalse();
    assertThat(RoleAssignmentPolicy.canManage(ADMIN, USER, false)).isTrue();
  }

  @Test
  void nobodyManagesTheOwnerOrThemselves() {
    assertThat(RoleAssignmentPolicy.canManage(OWNER, OWNER, false)).isFalse();
    assertThat(RoleAssignmentPolicy.canManage(OWNER, ADMIN, true)).isFalse();
    assertThat(RoleAssignmentPolicy.canChangeRole(OWNER, USER, ADMIN, false)).isTrue();
    assertThat(RoleAssignmentPolicy.canChangeRole(ADMIN, USER, ADMIN, false)).isFalse();
  }

  @Test
  void developersAndUsersManageNobody() {
    assertThat(RoleAssignmentPolicy.assignableRoles(DEVELOPER)).isEmpty();
    assertThat(RoleAssignmentPolicy.canManage(USER, USER, false)).isFalse();
  }
}

package com.jizza.engine.security.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.authz.Permissions;
import com.jizza.engine.api.enums.MembershipStatus;
import com.jizza.engine.api.enums.PlatformRole;
import com.jizza.engine.api.enums.WorkspaceRole;
import com.jizza.engine.api.enums.WorkspaceType;
import com.jizza.engine.api.workspace.Membership;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import com.jizza.engine.security.session.JizzaPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Role plus account type decides; the super admin's override needs a two-step session. */
class DefaultPermissionEvaluatorTest {

  private final UUID user = UUID.randomUUID();
  private final UUID org = UUID.randomUUID();
  private WorkspaceDirectory directory;
  private AuditLog audit;
  private DefaultPermissionEvaluator evaluator;

  @BeforeEach
  void setUp() {
    directory = mock(WorkspaceDirectory.class);
    audit = mock(AuditLog.class);
    when(directory.platformRoleOf(any())).thenReturn(PlatformRole.NONE);
    when(directory.workspace(org)).thenReturn(Optional.of(
        new WorkspaceDirectory.Workspace(org, WorkspaceType.ORGANIZATION, "Acme", UUID.randomUUID())));
    evaluator = new DefaultPermissionEvaluator(new JsonPermissionCatalog(), directory, audit,
        mock(com.jizza.engine.api.workspace.PublicListingStore.class));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private void member(WorkspaceRole role, MembershipStatus status) {
    when(directory.membership(org, user)).thenReturn(Optional.of(new Membership(UUID.randomUUID(),
        org, WorkspaceType.ORGANIZATION, "Acme", user, "a@b.c", role, status, Instant.now())));
  }

  private void signedIn(boolean mfa) {
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        new JizzaPrincipal(user, UUID.randomUUID(), null, mfa, org), null, List.of()));
  }

  @Test
  void aDeveloperCanBuildConnectorsButNotInvite() {
    member(WorkspaceRole.DEVELOPER, MembershipStatus.ACTIVE);
    assertThat(evaluator.isAllowed(user, org, Permissions.CONNECTOR_CREATE)).isTrue();
    assertThat(evaluator.isAllowed(user, org, Permissions.MEMBERS_INVITE)).isFalse();
  }

  @Test
  void aUserSeesNoConnectors() {
    member(WorkspaceRole.USER, MembershipStatus.ACTIVE);
    assertThat(evaluator.isAllowed(user, org, Permissions.CONNECTOR_VIEW)).isFalse();
  }

  @Test
  void aSuspendedMemberHasNothing() {
    member(WorkspaceRole.ADMIN, MembershipStatus.SUSPENDED);
    assertThat(evaluator.isAllowed(user, org, Permissions.CONNECTOR_VIEW)).isFalse();
  }

  @Test
  void anOutsiderAndAnUnknownPermissionAreRefused() {
    when(directory.membership(org, user)).thenReturn(Optional.empty());
    assertThat(evaluator.isAllowed(user, org, Permissions.CONNECTOR_VIEW)).isFalse();
    member(WorkspaceRole.OWNER, MembershipStatus.ACTIVE);
    assertThat(evaluator.isAllowed(user, org, "flow_view")).isFalse();
  }

  @Test
  void theSuperAdminOverrideNeedsATwoStepSessionAndIsAudited() {
    when(directory.membership(org, user)).thenReturn(Optional.empty());
    when(directory.platformRoleOf(user)).thenReturn(PlatformRole.SUPER_ADMIN);

    assertThat(evaluator.isAllowed(user, org, Permissions.MEMBERS_VIEW)).isFalse();
    signedIn(false);
    assertThat(evaluator.isAllowed(user, org, Permissions.MEMBERS_VIEW)).isFalse();
    verify(audit, never()).record(any(), any(), any(), any(), any(), any());

    signedIn(true);
    assertThat(evaluator.isAllowed(user, org, Permissions.MEMBERS_VIEW)).isTrue();
    verify(audit).record(eq(user), any(), eq(user), eq("authz.super_admin_override"),
        eq(Permissions.MEMBERS_VIEW), any());
  }
}

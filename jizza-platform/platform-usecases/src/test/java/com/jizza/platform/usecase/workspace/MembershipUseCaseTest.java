package com.jizza.platform.usecase.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.enums.InvitationStatus;
import com.jizza.engine.api.enums.MembershipStatus;
import com.jizza.engine.api.enums.PlatformRole;
import com.jizza.engine.api.enums.WorkspaceRole;
import com.jizza.engine.api.enums.WorkspaceType;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.error.PermissionDeniedException;
import com.jizza.engine.api.mail.MailSender;
import com.jizza.engine.api.util.Hashing;
import com.jizza.engine.api.workspace.Invitation;
import com.jizza.engine.api.workspace.Membership;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/** The rules for membership requests; the HTTP flow is covered end to end in the app's suite. */
class MembershipUseCaseTest {

  private final UUID owner = UUID.randomUUID();
  private final UUID org = UUID.randomUUID();
  private final Access access = new Access(owner, org);
  private WorkspaceDirectory directory;
  private MailSender mail;
  private MembershipUseCase useCase;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    directory = mock(WorkspaceDirectory.class);
    mail = mock(MailSender.class);
    ObjectProvider<MailSender> mailProvider = mock(ObjectProvider.class);
    when(mailProvider.getIfAvailable()).thenReturn(mail);
    useCase = new MembershipUseCase(directory,
        mock(com.jizza.engine.api.workspace.PublicListingStore.class), mock(AuditLog.class),
        mock(ApplicationEventPublisher.class), mock(ObjectProvider.class), mailProvider,
        Duration.ofDays(7), 2, "https://jizza.test/");
    workspace(WorkspaceType.ORGANIZATION);
    role(WorkspaceRole.OWNER);
    when(directory.platformRoleOf(any())).thenReturn(PlatformRole.NONE);
    when(directory.userIdByEmail(anyString())).thenReturn(Optional.empty());
    when(directory.pendingInvitation(any(), anyString())).thenReturn(Optional.empty());
    when(directory.createInvitation(any(), anyString(), any(), any(), any(), any(), any()))
        .thenAnswer(inv -> invitation(Instant.now().plus(Duration.ofDays(7))));
  }

  private void workspace(WorkspaceType type) {
    when(directory.workspace(org)).thenReturn(Optional.of(
        new WorkspaceDirectory.Workspace(org, type, "Acme", owner)));
  }

  private void role(WorkspaceRole role) {
    when(directory.membership(org, owner)).thenReturn(Optional.of(new Membership(UUID.randomUUID(),
        org, WorkspaceType.ORGANIZATION, "Acme", owner, "o@acme.test", role,
        MembershipStatus.ACTIVE, Instant.now())));
  }

  private Invitation invitation(Instant expiresAt) {
    return new Invitation(UUID.randomUUID(), org, "Acme", "new@acme.test", null,
        WorkspaceRole.DEVELOPER, InvitationStatus.PENDING, owner, "o@acme.test", Instant.now(),
        expiresAt, null);
  }

  @Test
  void anIndividualAccountCannotHaveMembers() {
    workspace(WorkspaceType.PERSONAL);
    assertThatThrownBy(() -> useCase.invite(access, "new@acme.test", WorkspaceRole.USER))
        .isInstanceOf(JizzaException.class).hasMessageContaining("members");
  }

  @Test
  void anAdminCannotInviteAnotherAdmin() {
    role(WorkspaceRole.ADMIN);
    assertThatThrownBy(() -> useCase.invite(access, "new@acme.test", WorkspaceRole.ADMIN))
        .isInstanceOf(PermissionDeniedException.class);
  }

  @Test
  void someoneWithoutAnAccountGetsASignUpLinkWhoseTokenIsStoredOnlyAsAHash() {
    useCase.invite(access, "new@acme.test", WorkspaceRole.DEVELOPER);

    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(mail).send(eq("new@acme.test"), anyString(), body.capture());
    Matcher m = Pattern.compile("https://jizza\\.test/register\\?invite=([A-Za-z0-9_-]+)")
        .matcher(body.getValue());
    assertThat(m.find()).as(body.getValue()).isTrue();
    verify(directory).createInvitation(eq(org), eq("new@acme.test"), isNull(),
        eq(WorkspaceRole.DEVELOPER), eq(Hashing.sha256Hex(m.group(1))), eq(owner), any());
  }

  @Test
  void someoneWithAnAccountIsLinkedAndGetsNoToken() {
    UUID invitee = UUID.randomUUID();
    when(directory.userIdByEmail("bob@acme.test")).thenReturn(Optional.of(invitee));
    when(directory.membership(org, invitee)).thenReturn(Optional.empty());
    useCase.invite(access, "bob@acme.test", WorkspaceRole.USER);
    verify(directory).createInvitation(eq(org), eq("bob@acme.test"), eq(invitee),
        eq(WorkspaceRole.USER), isNull(), eq(owner), any());
  }

  @Test
  void sendingIsRateLimitedPerOrganization() {
    useCase.invite(access, "a@acme.test", WorkspaceRole.USER);
    useCase.invite(access, "b@acme.test", WorkspaceRole.USER);
    assertThatThrownBy(() -> useCase.invite(access, "c@acme.test", WorkspaceRole.USER))
        .isInstanceOf(JizzaException.class);
  }

  @Test
  void anExpiredRequestCannotBeAccepted() {
    UUID invitee = UUID.randomUUID();
    Invitation expired = new Invitation(UUID.randomUUID(), org, "Acme", "bob@acme.test", invitee,
        WorkspaceRole.USER, InvitationStatus.PENDING, owner, "o@acme.test",
        Instant.now().minus(Duration.ofDays(8)), Instant.now().minus(Duration.ofDays(1)), null);
    when(directory.emailOf(invitee)).thenReturn(Optional.of("bob@acme.test"));
    when(directory.invitation(expired.id())).thenReturn(Optional.of(expired));

    assertThatThrownBy(() -> useCase.accept(invitee, expired.id()))
        .isInstanceOf(JizzaException.class).hasMessageContaining("expired");
    verify(directory, never()).addMember(any(), any(), any());
    verify(directory).setInvitationStatus(expired.id(), InvitationStatus.EXPIRED);
  }

  @Test
  void someoneElsesRequestIsNotFound() {
    UUID stranger = UUID.randomUUID();
    Invitation forBob = invitation(Instant.now().plus(Duration.ofDays(1)));
    when(directory.emailOf(stranger)).thenReturn(Optional.of("stranger@x.test"));
    when(directory.invitation(forBob.id())).thenReturn(Optional.of(forBob));
    assertThatThrownBy(() -> useCase.accept(stranger, forBob.id()))
        .isInstanceOf(com.jizza.engine.api.error.NotFoundException.class);
  }
}

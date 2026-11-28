package com.jizza.platform.usecase.workspace;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.authz.Access;
import com.jizza.engine.api.authz.RoleAssignmentPolicy;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.InvitationStatus;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.enums.WorkspaceRole;
import com.jizza.engine.api.enums.WorkspaceType;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.PermissionDeniedException;
import com.jizza.engine.api.error.RateLimitedException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.event.MembershipChanged;
import com.jizza.engine.api.mail.MailSender;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.util.Hashing;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workspace.Invitation;
import com.jizza.engine.api.workspace.Membership;
import com.jizza.engine.api.workspace.PublicListingStore;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import com.jizza.engine.api.workspace.WorkspaceSummary;
import com.jizza.platform.usecase.listing.PublicOrganizationInitializer;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organization members and membership requests: the product rules around who may add, change or
 * remove whom, what the invited person sees, and what gets audited and announced.
 *
 * <p>Adding a member answers the same way whether or not the email has an account, so the Members
 * tab cannot be used to discover who uses this Jizza. Each event goes on the actor's audit chain
 * and, when it affects someone else, on theirs too.
 */
@Service
public class MembershipUseCase {

  private static final Logger log = LoggerFactory.getLogger(MembershipUseCase.class);
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

  private final WorkspaceDirectory directory;
  private final PublicListingStore listings;
  private final AuditLog audit;
  private final ApplicationEventPublisher events;
  private final ObjectProvider<NotificationBus> notifications;
  private final ObjectProvider<MailSender> mail;
  private final Duration expiry;
  private final int perWorkspacePerHour;
  private final String clientBaseUrl;
  private final SecureRandom random = new SecureRandom();
  private final Map<UUID, Deque<Instant>> recentInvites = new ConcurrentHashMap<>();

  public MembershipUseCase(WorkspaceDirectory directory, PublicListingStore listings, AuditLog audit,
                           ApplicationEventPublisher events,
                           ObjectProvider<NotificationBus> notifications,
                           ObjectProvider<MailSender> mail,
                           @Value("${jizza.security.invitations.expiry:7d}") Duration expiry,
                           @Value("${jizza.security.invitations.per-workspace-per-hour:20}")
                           int perWorkspacePerHour,
                           @Value("${jizza.security.invitations.client-base-url:http://localhost:5173}")
                           String clientBaseUrl) {
    this.directory = directory;
    this.listings = listings;
    this.audit = audit;
    this.events = events;
    this.notifications = notifications;
    this.mail = mail;
    this.expiry = expiry;
    this.perWorkspacePerHour = perWorkspacePerHour;
    this.clientBaseUrl = clientBaseUrl.replaceAll("/+$", "");
  }

  // ── The organization's side (Members tab) ─────────────────────────────────

  @Transactional(readOnly = true)
  public List<Membership> members(Access access) {
    List<Membership> all = directory.members(access.workspaceId());
    // Everyone on Jizza is in the Public organization, so only its Owner and Admins see the list.
    WorkspaceRole role = roleOf(access);
    if (role != WorkspaceRole.OWNER && role != WorkspaceRole.ADMIN
        && PublicOrganizationInitializer.isPlatform(listings, access.workspaceId())) {
      return all.stream().filter(m -> m.userId().equals(access.userId())).toList();
    }
    return all;
  }

  @Transactional(readOnly = true)
  public List<Invitation> invitations(Access access) {
    Instant now = Instant.now();
    return directory.invitations(access.workspaceId()).stream()
        .map(i -> i.expired(now) ? withStatus(i, InvitationStatus.EXPIRED) : i).toList();
  }

  /** Sends a membership request. Returns nothing the caller could use to tell who has an account. */
  @Transactional
  public void invite(Access access, String rawEmail, WorkspaceRole role) {
    var workspace = organization(access);
    String email = rawEmail == null ? "" : rawEmail.trim();
    if (!EMAIL.matcher(email).matches() || email.length() > 320) {
      throw new ValidationException("Enter a valid email address");
    }
    if (role == null) throw new ValidationException("Choose a role");
    WorkspaceRole actor = roleOf(access);
    if (!RoleAssignmentPolicy.canInvite(actor, role, workspace.type())) {
      throw new PermissionDeniedException("members_invite", "members");
    }
    Optional<UUID> invitee = directory.userIdByEmail(email);
    if (invitee.isPresent()
        && directory.membership(access.workspaceId(), invitee.get()).isPresent()) {
      throw new JizzaException(HttpStatus.CONFLICT, "already-member", "Already a member",
          email + " is already a member of " + workspace.name() + ".");
    }
    Instant now = Instant.now();
    Optional<Invitation> pending = directory.pendingInvitation(access.workspaceId(), email);
    if (pending.isPresent()) {
      if (!pending.get().expired(now)) {
        throw new JizzaException(HttpStatus.CONFLICT, "invitation-pending", "Request already sent",
            "There's already a pending request for " + email + ".");
      }
      directory.setInvitationStatus(pending.get().id(), InvitationStatus.EXPIRED);
    }
    throttle(access.workspaceId(), now);

    String token = invitee.isPresent() ? null : newToken();
    Invitation created = directory.createInvitation(access.workspaceId(), email,
        invitee.orElse(null), role, token == null ? null : Hashing.sha256Hex(token),
        access.userId(), now.plus(expiry));

    var detail = Json.obj().put("workspaceId", access.workspaceId().toString())
        .put("invitationId", created.id().toString()).put("role", role.name());
    audit.record(access.userId(), ActorType.USER, access.userId(), "member.invited", email, detail);
    invitee.ifPresent(id -> {
      audit.record(id, ActorType.USER, access.userId(), "member.invited", email, detail);
      notify(id, "You've been invited to join " + workspace.name() + " as "
          + roleLabel(role) + ". Open Pending requests to accept or decline.");
    });
    sendInvitationEmail(email, workspace.name(), role, token);
  }

  @Transactional
  public void revoke(Access access, UUID invitationId) {
    organization(access);
    Invitation i = directory.invitation(invitationId)
        .filter(x -> x.workspaceId().equals(access.workspaceId()))
        .orElseThrow(() -> new NotFoundException("Request", invitationId));
    if (!i.pending()) throw new ValidationException("Only a pending request can be revoked");
    directory.setInvitationStatus(invitationId, InvitationStatus.REVOKED);
    audit.record(access.userId(), ActorType.USER, access.userId(), "member.invitation_revoked",
        i.email(), Json.obj().put("invitationId", invitationId.toString()));
  }

  @Transactional
  public Membership changeRole(Access access, UUID memberId, WorkspaceRole newRole) {
    var workspace = organization(access);
    Membership target = memberOf(access, memberId);
    WorkspaceRole actor = roleOf(access);
    boolean self = target.userId().equals(access.userId());
    if (newRole == null) throw new ValidationException("Choose a role");
    if (target.role() == newRole) return target;
    if (!RoleAssignmentPolicy.canChangeRole(actor, target.role(), newRole, self, workspace.type())) {
      throw new PermissionDeniedException("members_modify", "members");
    }
    Membership updated = directory.changeRole(memberId, newRole);
    var detail = Json.obj().put("workspaceId", access.workspaceId().toString())
        .put("before", target.role().name()).put("after", newRole.name());
    audit.record(access.userId(), ActorType.USER, access.userId(), "member.role_changed",
        target.email(), detail);
    audit.record(target.userId(), ActorType.USER, access.userId(), "member.role_changed",
        target.email(), detail);
    notify(target.userId(), "Your role in " + target.workspaceName() + " is now "
        + roleLabel(newRole) + ".");
    events.publishEvent(new MembershipChanged(access.workspaceId(), target.userId(),
        MembershipChanged.Kind.ROLE_CHANGED));
    return updated;
  }

  @Transactional
  public void remove(Access access, UUID memberId) {
    organization(access);
    Membership target = memberOf(access, memberId);
    boolean self = target.userId().equals(access.userId());
    if (!RoleAssignmentPolicy.canManage(roleOf(access), target.role(), self)) {
      throw new PermissionDeniedException("members_remove", "members");
    }
    directory.removeMember(memberId);
    var detail = Json.obj().put("workspaceId", access.workspaceId().toString())
        .put("role", target.role().name());
    audit.record(access.userId(), ActorType.USER, access.userId(), "member.removed",
        target.email(), detail);
    audit.record(target.userId(), ActorType.USER, access.userId(), "member.removed",
        target.email(), detail);
    notify(target.userId(), "You were removed from " + target.workspaceName() + ".");
    events.publishEvent(new MembershipChanged(access.workspaceId(), target.userId(),
        MembershipChanged.Kind.REMOVED));
  }

  // ── The invited person's side ─────────────────────────────────────────────

  @Transactional(readOnly = true)
  public List<Invitation> received(UUID userId) {
    String email = directory.emailOf(userId).orElse(null);
    Instant now = Instant.now();
    return directory.pendingFor(userId, email).stream().filter(i -> !i.expired(now)).toList();
  }

  @Transactional
  public WorkspaceSummary accept(UUID userId, UUID invitationId) {
    Invitation i = addressedTo(userId, invitationId);
    if (directory.membership(i.workspaceId(), userId).isPresent()) {
      directory.setInvitationStatus(invitationId, InvitationStatus.ACCEPTED);
      return directory.membership(i.workspaceId(), userId).orElseThrow().summary();
    }
    Membership m = directory.addMember(i.workspaceId(), userId, i.role());
    directory.setInvitationStatus(invitationId, InvitationStatus.ACCEPTED);
    var detail = Json.obj().put("workspaceId", i.workspaceId().toString())
        .put("role", i.role().name());
    audit.record(userId, ActorType.USER, userId, "member.joined", i.workspaceName(), detail);
    audit.record(i.invitedByUserId(), ActorType.USER, userId, "member.joined", i.email(), detail);
    notify(i.invitedByUserId(), i.email() + " accepted your request to join " + i.workspaceName()
        + " as " + roleLabel(i.role()) + ".");
    return m.summary();
  }

  @Transactional
  public void decline(UUID userId, UUID invitationId) {
    Invitation i = addressedTo(userId, invitationId);
    directory.setInvitationStatus(invitationId, InvitationStatus.DECLINED);
    var detail = Json.obj().put("workspaceId", i.workspaceId().toString());
    audit.record(userId, ActorType.USER, userId, "member.invitation_declined", i.workspaceName(),
        detail);
    audit.record(i.invitedByUserId(), ActorType.USER, userId, "member.invitation_declined",
        i.email(), detail);
    notify(i.invitedByUserId(), i.email() + " declined your request to join "
        + i.workspaceName() + ".");
  }

  /** For the sign-up page: which email and organization a sign-up link belongs to. */
  @Transactional(readOnly = true)
  public Optional<Invitation> bySignupToken(String token) {
    if (token == null || token.isBlank()) return Optional.empty();
    return directory.pendingInvitationBySignupToken(Hashing.sha256Hex(token))
        .filter(i -> !i.expired(Instant.now()));
  }

  /** Marks overdue requests expired, so the organization sees them as such. */
  @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
  @Transactional
  public void expireOverdue() {
    int n = directory.expireOverdue(Instant.now());
    if (n > 0) log.info("Marked {} membership requests expired", n);
  }

  // ── Rules ─────────────────────────────────────────────────────────────────

  private com.jizza.engine.api.workspace.WorkspaceDirectory.Workspace organization(Access access) {
    var ws = directory.workspace(access.workspaceId())
        .orElseThrow(() -> new NotFoundException("Account", access.workspaceId()));
    // Members side (spec 4.4): an Organization or a Family.
    if (!ws.type().hasMembers()) {
      throw new JizzaException(HttpStatus.CONFLICT, "personal-workspace-no-members",
          "Individual accounts can't have members", "Individual accounts can't have members.");
    }
    return ws;
  }

  /** The caller's role here. The super admin, who may not be a member, acts with owner rights. */
  private WorkspaceRole roleOf(Access access) {
    return directory.membership(access.workspaceId(), access.userId())
        .filter(Membership::active).map(Membership::role)
        .orElseGet(() -> directory.platformRoleOf(access.userId())
            == com.jizza.engine.api.enums.PlatformRole.SUPER_ADMIN ? WorkspaceRole.OWNER : null);
  }

  private Membership memberOf(Access access, UUID memberId) {
    return directory.member(memberId)
        .filter(m -> m.workspaceId().equals(access.workspaceId()))
        .orElseThrow(() -> new NotFoundException("Member", memberId));
  }

  /** A request belongs to the person it was sent to, by account or by email; anyone else gets 404. */
  private Invitation addressedTo(UUID userId, UUID invitationId) {
    String email = directory.emailOf(userId).orElse("");
    Invitation i = directory.invitation(invitationId)
        .filter(x -> userId.equals(x.inviteeUserId()) || x.email().equalsIgnoreCase(email))
        .orElseThrow(() -> new NotFoundException("Request", invitationId));
    if (i.expired(Instant.now())) {
      directory.setInvitationStatus(invitationId, InvitationStatus.EXPIRED);
      throw new JizzaException(HttpStatus.GONE, "invitation-expired", "Request expired",
          "This request has expired. Ask the organization to send a new one.");
    }
    if (!i.pending()) throw new NotFoundException("Request", invitationId);
    return i;
  }

  private void throttle(UUID workspaceId, Instant now) {
    Deque<Instant> window = recentInvites.computeIfAbsent(workspaceId, k -> new ArrayDeque<>());
    synchronized (window) {
      while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(Duration.ofHours(1)))) {
        window.pollFirst();
      }
      if (window.size() >= perWorkspacePerHour) {
        throw new RateLimitedException("Too many requests sent in the last hour. Try again later.");
      }
      window.addLast(now);
    }
  }

  private String newToken() {
    byte[] raw = new byte[32];
    random.nextBytes(raw);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  private void notify(UUID userId, String body) {
    try {
      notifications.ifAvailable(bus ->
          bus.raise(NotificationKind.INFO, null, NotificationContext.info(userId, body)));
    } catch (RuntimeException e) {
      log.warn("Could not raise a membership notification", e);
    }
  }

  private void sendInvitationEmail(String email, String workspaceName, WorkspaceRole role,
                                   String token) {
    MailSender sender = mail.getIfAvailable();
    if (sender == null) return;
    String body = token == null
        ? "You've been invited to join " + workspaceName + " on Jizza as " + roleLabel(role)
            + ". Sign in to accept or decline."
        : "You've been invited to join " + workspaceName + " on Jizza as " + roleLabel(role)
            + ". Create your account with this link to respond: " + clientBaseUrl
            + "/register?invite=" + token + "\n\nThe link expires in " + expiry.toDays() + " days.";
    try {
      sender.send(email, "Invitation to join " + workspaceName + " on Jizza", body);
    } catch (RuntimeException e) {
      log.warn("Could not send the invitation email", e);
    }
  }

  private static String roleLabel(WorkspaceRole role) {
    String n = role.name().toLowerCase(Locale.ROOT);
    return Character.toUpperCase(n.charAt(0)) + n.substring(1);
  }

  private static Invitation withStatus(Invitation i, InvitationStatus status) {
    return new Invitation(i.id(), i.workspaceId(), i.workspaceName(), i.email(), i.inviteeUserId(),
        i.role(), status, i.invitedByUserId(), i.invitedByEmail(), i.createdAt(), i.expiresAt(),
        i.respondedAt());
  }
}

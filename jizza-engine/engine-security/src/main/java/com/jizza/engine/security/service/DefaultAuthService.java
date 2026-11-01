package com.jizza.engine.security.service;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.dto.LoginResult;
import com.jizza.engine.api.dto.MfaSetup;
import com.jizza.engine.api.dto.SecurityStatus;
import com.jizza.engine.api.dto.SessionDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.UserStatus;
import com.jizza.engine.api.error.AuthException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Hashing;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.PasswordResetTokenEntity;
import com.jizza.engine.persistence.entity.UserEntity;
import com.jizza.engine.persistence.repository.PasswordResetTokenRepository;
import com.jizza.engine.persistence.repository.UserRepository;
import com.jizza.engine.security.alerts.SecurityAlerts;
import com.jizza.engine.security.config.SecurityProperties;
import com.jizza.engine.security.mfa.RecoveryCodes;
import com.jizza.engine.security.mfa.TotpService;
import com.jizza.engine.security.password.PasswordPolicy;
import com.jizza.engine.security.session.IssuedSession;
import com.jizza.engine.security.session.PendingMfaStore;
import com.jizza.engine.security.session.SessionService;
import com.jizza.engine.security.throttle.LoginThrottle;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registration, login, MFA, step-up, session lifecycle, recovery and lockout (R3 to R6).
 *
 * <p>Every outcome that matters -- and every failure -- lands in the audit log, which is what makes
 * the trail useful for spotting abuse rather than only successful actions.
 */
@Service
public class DefaultAuthService implements com.jizza.engine.api.security.AuthService {

  /** Constant-time dummy hash so a missing account costs the same as a wrong password. */
  private static final String DUMMY_HASH =
      "$argon2id$v=19$m=16384,t=2,p=1$YWJjZGVmZ2hpamtsbW5vcA$0zaLTPNBg0kFLTVLtLFhLXFXTHWfnDStPjVBSDJi9wA";

  private final UserRepository users;
  private final PasswordResetTokenRepository resetTokens;
  private final PasswordEncoder passwordEncoder;
  private final PasswordPolicy passwordPolicy;
  private final TotpService totp;
  private final SessionService sessions;
  private final PendingMfaStore pendingMfa;
  private final LoginThrottle throttle;
  private final EncryptionService encryption;
  private final AuditLog audit;
  private final SecurityProperties props;
  private final ApplicationEventPublisher events;
  private final LoginAttempts attempts;
  private final TransactionTemplate transactions;
  private final RecoveryCodes recoveryCodes;
  private final SecurityAlerts alerts;
  private final com.jizza.engine.api.workspace.WorkspaceDirectory workspaces;
  private final SecureRandom random = new SecureRandom();

  public DefaultAuthService(UserRepository users, PasswordResetTokenRepository resetTokens,
                     PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
                     TotpService totp, SessionService sessions, PendingMfaStore pendingMfa,
                     LoginThrottle throttle, EncryptionService encryption, AuditLog audit,
                     SecurityProperties props, ApplicationEventPublisher events,
                     LoginAttempts attempts,
                     org.springframework.transaction.PlatformTransactionManager txManager,
                     RecoveryCodes recoveryCodes, SecurityAlerts alerts,
                     com.jizza.engine.api.workspace.WorkspaceDirectory workspaces) {
    this.users = users;
    this.resetTokens = resetTokens;
    this.passwordEncoder = passwordEncoder;
    this.passwordPolicy = passwordPolicy;
    this.totp = totp;
    this.sessions = sessions;
    this.pendingMfa = pendingMfa;
    this.throttle = throttle;
    this.encryption = encryption;
    this.audit = audit;
    this.props = props;
    this.events = events;
    this.attempts = attempts;
    this.transactions = new TransactionTemplate(txManager);
    this.recoveryCodes = recoveryCodes;
    this.alerts = alerts;
    this.workspaces = workspaces;
  }

  /**
   * Deliberately not annotated: the user row has to be committed before anything can reference it.
   * The audit log and the provisioning listeners both write rows with a foreign key to
   * {@code users}, so they run after the transaction that creates it, not inside it.
   */
  @Override
  public UUID register(com.jizza.engine.api.security.RegistrationRequest request) {
    String email = request.email() == null ? null : request.email().trim();
    String password = request.password();
    var accountType = request.accountType() == null
        ? com.jizza.engine.api.enums.WorkspaceType.PERSONAL : request.accountType();
    if (email == null || !email.contains("@")) throw new ValidationException("A valid email is required");
    String organizationName = null;
    if (accountType == com.jizza.engine.api.enums.WorkspaceType.ORGANIZATION) {
      organizationName = request.organizationName() == null ? "" : request.organizationName().trim();
      if (organizationName.length() < 2 || organizationName.length() > 100) {
        throw new ValidationException("The organization name must be 2 to 100 characters");
      }
    }
    // Jizza is one person's assistant: once the owner exists, the door is shut -- except for an
    // email someone was invited with, arriving through that invitation's link. Checked before the
    // password policy so a closed instance says nothing about what it would have accepted.
    if (!props.getRegistration().isAllowAdditionalUsers() && users.count() > 0
        && !invitedThroughLink(email, request.invitationToken())) {
      throw new com.jizza.engine.api.error.JizzaException(
          org.springframework.http.HttpStatus.FORBIDDEN, "registration-closed",
          "Registration is closed", "This Jizza already has its owner. Sign in instead.");
    }
    passwordPolicy.validate(password);
    if (users.existsByEmailIgnoreCase(email)) {
      throw new com.jizza.engine.api.error.ConflictException("That email is already registered");
    }

    UUID userId = transactions.execute(status -> {
      UserEntity user = new UserEntity();
      user.setEmail(email);
      user.setPasswordHash(passwordEncoder.encode(password));
      user.setStatus(UserStatus.ACTIVE);
      user.setMfaEnabled(false);
      return users.save(user).getId();
    });

    // Every person has their own account; an organization sign-up also creates the organization
    // and starts them in it. Runs after the user row commits, like the audit write below.
    String orgName = organizationName;
    UUID startIn = transactions.execute(status -> {
      UUID personal = workspaces.personalWorkspaceOf(userId);
      UUID start = personal;
      if (orgName != null) start = workspaces.createOrganization(userId, orgName).id();
      workspaces.rememberActiveWorkspace(userId, start);
      workspaces.linkPendingInvitations(email, userId);
      return start;
    });

    audit.record(userId, ActorType.USER, userId, "auth.register", email,
        Json.obj().put("accountType", accountType.name()));
    audit.record(userId, ActorType.USER, userId, "workspace.created",
        orgName == null ? "Personal" : orgName,
        Json.obj().put("workspaceId", startIn.toString()).put("type", accountType.name()));
    // Agent seeding, the kill-switch row and the default autonomy policies hang off this.
    events.publishEvent(new com.jizza.engine.api.event.UserRegistered(userId, email));
    return userId;
  }

  @Override
  @Transactional
  public LoginResult login(String email, String password, String device, InetAddress ip,
                           String clientKey) {
    throttle.check("ip:" + clientKey);
    throttle.check("account:" + String.valueOf(email).toLowerCase(java.util.Locale.ROOT));

    var maybeUser = users.findByEmailIgnoreCase(email == null ? "" : email);
    if (maybeUser.isEmpty()) {
      // Spend the same work as a real verification so timing does not disclose account existence.
      passwordEncoder.matches(password == null ? "" : password, DUMMY_HASH);
      throw new AuthException("Invalid email or password");
    }
    UserEntity user = maybeUser.get();

    if (user.getStatus() == UserStatus.DISABLED) {
      attempts.recordRefusal(user.getId(), "auth.login.disabled", email);
      throw new AuthException("This account is disabled");
    }
    if (isLockedOut(user)) {
      attempts.recordRefusal(user.getId(), "auth.login.locked_out", email);
      throw new AuthException("account-locked",
          "Too many failed attempts. Try again after " + user.getLockedUntil());
    }

    if (!passwordEncoder.matches(password == null ? "" : password, user.getPasswordHash())) {
      attempts.recordFailure(user.getId());
      throw new AuthException("Invalid email or password");
    }

    user.setFailedLogins(0);
    user.setStatus(UserStatus.ACTIVE);
    user.setLockedUntil(null);
    user.setLastLoginAt(Instant.now());
    users.save(user);
    throttle.reset("account:" + email.toLowerCase(java.util.Locale.ROOT));

    if (user.isMfaEnabled()) {
      audit.record(user.getId(), ActorType.USER, user.getId(), "auth.login.password_ok", email, null);
      return new LoginResult(true, pendingMfa.open(user.getId()), null, null, user.getId());
    }

    // Without a second factor on the account, this session has not passed one. When two-step
    // sign-in is required it can only reach the setup endpoints (MfaEnrollmentFilter).
    IssuedSession issued = sessions.create(user.getId(), device, ip, false,
        startingWorkspace(user.getId()));
    audit.record(user.getId(), ActorType.USER, user.getId(), "auth.login.success", email,
        Json.obj().put("sessionId", issued.session().getId().toString()));
    alerts.signedIn(user.getId(), user.getEmail(), device, ip);
    return new LoginResult(false, null, issued.session().getId(), issued.token(), user.getId());
  }

  @Override
  @Transactional
  public LoginResult verifyMfa(String mfaToken, String code, String device, InetAddress ip,
                               String clientKey) {
    throttle.check("mfa:" + clientKey);
    UUID userId = pendingMfa.consume(mfaToken)
        .orElseThrow(() -> new AuthException("That sign-in attempt expired; sign in again"));
    UserEntity user = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));

    if (isLockedOut(user)) {
      attempts.recordRefusal(userId, "auth.login.locked_out", user.getEmail());
      throw new AuthException("account-locked",
          "Too many failed attempts. Try again after " + user.getLockedUntil());
    }
    boolean usedRecoveryCode = RecoveryCodes.looksLikeRecoveryCode(code);
    if (!secondFactorOk(user, code)) {
      attempts.recordFailure(userId);
      attempts.recordRefusal(userId, "auth.mfa.failed", user.getEmail());
      throw new AuthException("That verification code is not valid");
    }
    IssuedSession issued = sessions.create(userId, device, ip, true, startingWorkspace(userId));
    audit.record(userId, ActorType.USER, userId, "auth.mfa.verified", user.getEmail(),
        Json.obj().put("sessionId", issued.session().getId().toString())
            .put("recoveryCode", usedRecoveryCode));
    alerts.signedIn(userId, user.getEmail(), device, ip);
    if (usedRecoveryCode) recoveryCodeSpent(user);
    return new LoginResult(false, null, issued.session().getId(), issued.token(), userId);
  }

  /**
   * Checks a TOTP code -- once only, the same code cannot be replayed inside its window -- or, if
   * the submission looks like one, spends a recovery code.
   */
  private boolean secondFactorOk(UserEntity user, String code) {
    if (RecoveryCodes.looksLikeRecoveryCode(code)) {
      return recoveryCodes.consume(user.getId(), code);
    }
    return totpOk(user, secretOf(user), code);
  }

  private boolean totpOk(UserEntity user, String secret, String code) {
    var step = totp.matchingStep(secret, code, Instant.now().getEpochSecond());
    if (step.isEmpty()) return false;
    Long last = user.getMfaLastStep();
    if (last != null && step.getAsLong() <= last) return false;
    user.setMfaLastStep(step.getAsLong());
    users.save(user);
    return true;
  }

  private void recoveryCodeSpent(UserEntity user) {
    long left = recoveryCodes.remaining(user.getId());
    audit.record(user.getId(), ActorType.USER, user.getId(), "auth.mfa.recovery_code_used",
        user.getEmail(), Json.obj().put("remaining", left));
    alerts.alert(user.getId(), user.getEmail(), "A Jizza recovery code was used",
        "A one-time recovery code was just used to confirm it's you. " + left
            + " remain. If this was not you, sign out everywhere, change your password and "
            + "make new recovery codes.");
  }

  /**
   * Begin TOTP enrolment. The seed is returned once for the authenticator app and stored encrypted;
   * MFA only switches on once the user proves a code from it.
   */
  @Override
  @Transactional
  public MfaSetup setupMfa(UUID userId) {
    UserEntity user = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
    String secret = totp.newSecret();
    // Staged, not swapped in: an authenticator already protecting the account stays in force until
    // a code from the new one is proven.
    user.setMfaPendingSecret(encryption.encryptString(secret));
    users.save(user);
    audit.record(userId, ActorType.USER, userId, "auth.mfa.setup_started", user.getEmail(), null);
    return new MfaSetup(secret, totp.provisioningUri(secret, user.getEmail()));
  }

  @Override
  @Transactional
  public List<String> confirmMfa(UUID userId, UUID sessionId, String code) {
    UserEntity user = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
    if (user.getMfaPendingSecret() == null) throw new ValidationException("Start MFA setup first");
    String pending = encryption.decryptString(user.getMfaPendingSecret());
    // The step counter belongs to the old secret; the new one starts fresh.
    user.setMfaLastStep(null);
    if (!totpOk(user, pending, code)) {
      throw new AuthException("That verification code is not valid");
    }
    boolean replacing = user.isMfaEnabled();
    user.setMfaSecret(user.getMfaPendingSecret());
    user.setMfaPendingSecret(null);
    user.setMfaEnabled(true);
    user.setMfaEnabledAt(Instant.now());
    users.save(user);
    List<String> codes = recoveryCodes.issue(userId);

    // This session just proved the new factor. Every other one signed in without it (or with the
    // authenticator being replaced), so they are signed out.
    if (sessionId != null) {
      sessions.markMfaSatisfied(sessionId);
      sessions.revokeAllExcept(userId, sessionId);
    }
    audit.record(userId, ActorType.USER, userId,
        replacing ? "auth.mfa.replaced" : "auth.mfa.enabled", user.getEmail(), null);
    alerts.alert(userId, user.getEmail(), "Two-step sign-in is on",
        (replacing ? "Your Jizza authenticator was replaced." : "Two-step sign-in is now on for Jizza.")
            + " Other devices were signed out. If this was not you, contact whoever runs "
            + "this Jizza and reset your password.");
    return codes;
  }

  @Override
  @Transactional
  public List<String> regenerateRecoveryCodes(UUID userId, String code) {
    UserEntity user = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
    if (!user.isMfaEnabled()) throw new ValidationException("Turn on two-step sign-in first");
    if (!totpOk(user, secretOf(user), code)) {
      attempts.recordRefusal(userId, "auth.mfa.recovery_codes.refused", user.getEmail());
      throw new AuthException("That verification code is not valid");
    }
    List<String> codes = recoveryCodes.issue(userId);
    audit.record(userId, ActorType.USER, userId, "auth.mfa.recovery_codes.regenerated",
        user.getEmail(), null);
    alerts.alert(userId, user.getEmail(), "New Jizza recovery codes",
        "Your Jizza recovery codes were replaced; the old ones no longer work.");
    return codes;
  }

  @Override
  @Transactional
  public void disableMfa(UUID userId, String code) {
    if (props.getMfa().isRequired()) {
      throw new com.jizza.engine.api.error.JizzaException(
          org.springframework.http.HttpStatus.FORBIDDEN, "mfa-required",
          "Two-step sign-in is required", "This Jizza requires two-step sign-in for every account.");
    }
    UserEntity user = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
    if (!user.isMfaEnabled()) return;
    if (!secondFactorOk(user, code)) {
      attempts.recordRefusal(userId, "auth.mfa.disable.refused", user.getEmail());
      throw new AuthException("That verification code is not valid");
    }
    user.setMfaEnabled(false);
    user.setMfaSecret(null);
    user.setMfaPendingSecret(null);
    user.setMfaLastStep(null);
    user.setMfaEnabledAt(null);
    users.save(user);
    recoveryCodes.revokeAll(userId);
    audit.record(userId, ActorType.USER, userId, "auth.mfa.disabled", user.getEmail(), null);
    alerts.alert(userId, user.getEmail(), "Two-step sign-in was turned off",
        "Two-step sign-in was just turned off for your Jizza account. If this was not you, "
            + "sign out everywhere and change your password now.");
  }

  @Override
  @Transactional(readOnly = true)
  public SecurityStatus status(UUID userId, UUID sessionId) {
    UserEntity user = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
    boolean required = props.getMfa().isRequired();
    return new SecurityStatus(userId, user.getEmail(), user.isMfaEnabled(), user.getMfaEnabledAt(),
        required, required && !sessions.mfaSatisfied(sessionId),
        user.isMfaEnabled() ? recoveryCodes.remaining(userId) : 0);
  }

  /**
   * Re-authenticate inside an existing session to unlock a high-risk route (R4). The password alone
   * is asked for: the session was already opened with the second factor, so asking for the
   * authenticator code again on every sensitive action added friction without much more safety.
   */
  @Override
  @Transactional
  public void stepUp(UUID userId, UUID sessionId, String password) {
    throttle.check("step-up:" + sessionId);
    UserEntity user = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
    if (!passwordEncoder.matches(password == null ? "" : password, user.getPasswordHash())) {
      stepUpFailed(user, sessionId);
      throw new AuthException("Password is not correct");
    }
    sessions.markStepUp(sessionId);
    audit.record(userId, ActorType.USER, userId, "auth.step_up.ok", user.getEmail(), null);
  }

  /**
   * A wrong step-up answer. Too many in a row and the session itself is revoked: whoever holds it
   * evidently does not know the password behind it.
   */
  private void stepUpFailed(UserEntity user, UUID sessionId) {
    attempts.recordRefusal(user.getId(), "auth.step_up.failed", user.getEmail());
    if (sessions.recordStepUpFailure(sessionId)) {
      attempts.recordRefusal(user.getId(), "auth.session.revoked_after_step_up_failures",
          sessionId.toString());
      alerts.alert(user.getId(), user.getEmail(), "A Jizza session was signed out",
          "A signed-in Jizza session failed to confirm your password several times in a row, so it "
              + "was signed out. If this was not you, change your password now.");
    }
  }

  @Override
  @Transactional
  public void logout(UUID userId, UUID sessionId) {
    sessions.revoke(sessionId);
    audit.record(userId, ActorType.USER, userId, "auth.logout", sessionId.toString(), null);
  }

  @Override
  @Transactional
  public void logoutEverywhere(UUID userId) {
    int revoked = sessions.revokeAll(userId);
    audit.record(userId, ActorType.USER, userId, "auth.logout_everywhere", null,
        Json.obj().put("sessionsRevoked", revoked));
    users.findById(userId).ifPresent(user -> alerts.alert(userId, user.getEmail(),
        "Signed out of Jizza everywhere",
        "Every Jizza session on every device was just signed out."));
  }

  @Override
  @Transactional
  public void revokeSession(UUID userId, UUID sessionId) {
    if (!sessions.revoke(userId, sessionId)) throw new NotFoundException("Session", sessionId);
    audit.record(userId, ActorType.USER, userId, "auth.session.revoked", sessionId.toString(), null);
  }

  @Override
  @Transactional(readOnly = true)
  public List<SessionDto> sessions(UUID userId, UUID currentSessionId) {
    return sessions.list(userId, currentSessionId);
  }

  /**
   * Issue a single-use reset token. Returns empty for an unknown address so the endpoint cannot be
   * used to enumerate accounts; the caller responds identically either way.
   */
  @Override
  @Transactional
  public java.util.Optional<String> requestPasswordReset(String email, String clientKey) {
    throttle.check("reset:" + clientKey);
    var maybeUser = users.findByEmailIgnoreCase(email == null ? "" : email);
    if (maybeUser.isEmpty()) return java.util.Optional.empty();

    byte[] raw = new byte[32];
    random.nextBytes(raw);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

    PasswordResetTokenEntity entity = new PasswordResetTokenEntity();
    entity.setUserId(maybeUser.get().getId());
    entity.setTokenHash(Hashing.sha256Hex(token));
    entity.setExpiresAt(Instant.now().plus(java.time.Duration.ofMinutes(30)));
    entity.setUsed(false);
    resetTokens.save(entity);

    audit.record(maybeUser.get().getId(), ActorType.SYSTEM, null, "auth.password_reset.requested",
        email, null);
    return java.util.Optional.of(token);
  }

  @Override
  @Transactional
  public void resetPassword(String token, String newPassword) {
    passwordPolicy.validate(newPassword);
    var entity = resetTokens.findByTokenHash(Hashing.sha256Hex(token == null ? "" : token))
        .filter(t -> !t.isUsed())
        .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
        .orElseThrow(() -> new AuthException("That reset link is no longer valid"));

    UserEntity user = users.findById(entity.getUserId())
        .orElseThrow(() -> new NotFoundException("User", entity.getUserId()));
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    user.setFailedLogins(0);
    user.setStatus(UserStatus.ACTIVE);
    user.setLockedUntil(null);
    users.save(user);

    entity.setUsed(true);
    resetTokens.save(entity);

    // A password reset invalidates every existing session: the old credential may be compromised.
    sessions.revokeAll(user.getId());
    audit.record(user.getId(), ActorType.USER, user.getId(), "auth.password_reset.completed",
        user.getEmail(), null);
    alerts.alert(user.getId(), user.getEmail(), "Your Jizza password was reset",
        "Your Jizza password was reset and every session was signed out. If this was not you, "
            + "reset it again right away.");
  }

  @Override
  @Transactional
  public void changePassword(UUID userId, UUID sessionId, String currentPassword,
                             String newPassword) {
    UserEntity user = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
    if (!passwordEncoder.matches(currentPassword == null ? "" : currentPassword,
        user.getPasswordHash())) {
      throw new AuthException("Password is not correct");
    }
    passwordPolicy.validate(newPassword);
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    users.save(user);
    // Anyone who knew the old password and signed in with it is signed out with it.
    int revoked = sessionId == null ? sessions.revokeAll(userId)
        : sessions.revokeAllExcept(userId, sessionId);
    audit.record(userId, ActorType.USER, userId, "auth.password.changed", user.getEmail(),
        Json.obj().put("otherSessionsRevoked", revoked));
    alerts.alert(userId, user.getEmail(), "Your Jizza password was changed",
        "Your Jizza password was changed and other devices were signed out. If this was not you, "
            + "reset your password right away.");
  }

  /**
   * Whether an email may register while registration is closed: only with the sign-up token of a
   * pending, unexpired membership request sent to that same address.
   */
  private boolean invitedThroughLink(String email, String token) {
    if (token == null || token.isBlank()) return false;
    throttle.check("invite-token:" + email.toLowerCase(java.util.Locale.ROOT));
    return workspaces.pendingInvitationBySignupToken(Hashing.sha256Hex(token))
        .filter(i -> i.email().equalsIgnoreCase(email))
        .filter(i -> !i.expired(Instant.now()))
        .isPresent();
  }

  /** The account a new session starts in: the last one used, if still a member, else personal. */
  private UUID startingWorkspace(UUID userId) {
    return workspaces.lastActiveWorkspace(userId)
        .filter(ws -> workspaces.membership(ws, userId).filter(m -> m.active()).isPresent())
        .orElseGet(() -> workspaces.personalWorkspaceOf(userId));
  }

  private boolean isLockedOut(UserEntity user) {
    if (user.getStatus() != UserStatus.LOCKED) return false;
    Instant until = user.getLockedUntil();
    if (until != null && Instant.now().isAfter(until)) {
      user.setStatus(UserStatus.ACTIVE);
      user.setFailedLogins(0);
      user.setLockedUntil(null);
      users.save(user);
      return false;
    }
    return true;
  }

  private String secretOf(UserEntity user) {
    return user.getMfaSecret() == null ? null : encryption.decryptString(user.getMfaSecret());
  }
}

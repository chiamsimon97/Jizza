package com.jizza.security.service;

import com.jizza.common.audit.AuditLog;
import com.jizza.common.crypto.EncryptionService;
import com.jizza.common.dto.SessionDto;
import com.jizza.common.enums.ActorType;
import com.jizza.common.enums.UserStatus;
import com.jizza.common.error.AuthException;
import com.jizza.common.error.NotFoundException;
import com.jizza.common.error.ValidationException;
import com.jizza.common.util.Hashing;
import com.jizza.common.util.Json;
import com.jizza.persistence.entity.PasswordResetTokenEntity;
import com.jizza.persistence.entity.UserEntity;
import com.jizza.persistence.repository.PasswordResetTokenRepository;
import com.jizza.persistence.repository.UserRepository;
import com.jizza.security.config.SecurityProperties;
import com.jizza.security.mfa.TotpService;
import com.jizza.security.password.PasswordPolicy;
import com.jizza.security.session.PendingMfaStore;
import com.jizza.security.session.SessionService;
import com.jizza.security.throttle.LoginThrottle;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login, MFA, step-up, session lifecycle, recovery and lockout (R3 to R6).
 *
 * <p>Every outcome that matters -- and every failure -- lands in the audit log, which is what makes
 * the trail useful for spotting abuse rather than only successful actions.
 */
@Service
public class AuthService {

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
  private final SecureRandom random = new SecureRandom();

  public AuthService(UserRepository users, PasswordResetTokenRepository resetTokens,
                     PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
                     TotpService totp, SessionService sessions, PendingMfaStore pendingMfa,
                     LoginThrottle throttle, EncryptionService encryption, AuditLog audit,
                     SecurityProperties props) {
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
  }

  /** @param mfaToken non-null only when a second factor is still outstanding. */
  public record LoginResult(boolean mfaRequired, String mfaToken, UUID sessionId, UUID userId) {}

  public record MfaSetup(String secret, String provisioningUri) {}

  @Transactional
  public UUID register(String email, String password) {
    if (email == null || !email.contains("@")) throw new ValidationException("A valid email is required");
    passwordPolicy.validate(password);
    if (users.existsByEmailIgnoreCase(email)) {
      throw new com.jizza.common.error.ConflictException("That email is already registered");
    }
    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPasswordHash(passwordEncoder.encode(password));
    user.setStatus(UserStatus.ACTIVE);
    user.setMfaEnabled(false);
    users.save(user);
    audit.record(user.getId(), ActorType.USER, user.getId(), "auth.register", email, null);
    return user.getId();
  }

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
      audit.record(user.getId(), ActorType.SYSTEM, null, "auth.login.disabled", email, null);
      throw new AuthException("This account is disabled");
    }
    if (isLockedOut(user)) {
      audit.record(user.getId(), ActorType.SYSTEM, null, "auth.login.locked_out", email, null);
      throw new AuthException("account-locked",
          "Too many failed attempts. Try again after " + user.getLockedUntil());
    }

    if (!passwordEncoder.matches(password == null ? "" : password, user.getPasswordHash())) {
      registerFailure(user);
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
      return new LoginResult(true, pendingMfa.open(user.getId()), null, user.getId());
    }

    var session = sessions.create(user.getId(), device, ip);
    audit.record(user.getId(), ActorType.USER, user.getId(), "auth.login.success", email,
        Json.obj().put("sessionId", session.getId().toString()));
    return new LoginResult(false, null, session.getId(), user.getId());
  }

  @Transactional
  public LoginResult verifyMfa(String mfaToken, String code, String device, InetAddress ip,
                               String clientKey) {
    throttle.check("mfa:" + clientKey);
    UUID userId = pendingMfa.consume(mfaToken)
        .orElseThrow(() -> new AuthException("That sign-in attempt expired; sign in again"));
    UserEntity user = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));

    if (!totp.verify(secretOf(user), code, Instant.now().getEpochSecond())) {
      registerFailure(user);
      audit.record(userId, ActorType.SYSTEM, null, "auth.mfa.failed", user.getEmail(), null);
      throw new AuthException("That verification code is not valid");
    }
    var session = sessions.create(userId, device, ip);
    audit.record(userId, ActorType.USER, userId, "auth.mfa.verified", user.getEmail(),
        Json.obj().put("sessionId", session.getId().toString()));
    return new LoginResult(false, null, session.getId(), userId);
  }

  /**
   * Begin TOTP enrolment. The seed is returned once for the authenticator app and stored encrypted;
   * MFA only switches on once the user proves a code from it.
   */
  @Transactional
  public MfaSetup setupMfa(UUID userId) {
    UserEntity user = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
    String secret = totp.newSecret();
    user.setMfaSecret(encryption.encryptString(secret));
    user.setMfaEnabled(false);
    users.save(user);
    audit.record(userId, ActorType.USER, userId, "auth.mfa.setup_started", user.getEmail(), null);
    return new MfaSetup(secret, totp.provisioningUri(secret, user.getEmail()));
  }

  @Transactional
  public List<String> confirmMfa(UUID userId, String code) {
    UserEntity user = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
    if (user.getMfaSecret() == null) throw new ValidationException("Start MFA setup first");
    if (!totp.verify(secretOf(user), code, Instant.now().getEpochSecond())) {
      throw new AuthException("That verification code is not valid");
    }
    List<String> recoveryCodes = newRecoveryCodes();
    user.setMfaEnabled(true);
    user.setRecoveryHash(Hashing.sha256Hex(String.join(",", recoveryCodes)));
    users.save(user);
    audit.record(userId, ActorType.USER, userId, "auth.mfa.enabled", user.getEmail(), null);
    return recoveryCodes;
  }

  /** Re-authenticate inside an existing session to unlock a high-risk route (R4). */
  @Transactional
  public void stepUp(UUID userId, UUID sessionId, String password, String code) {
    UserEntity user = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
    if (!passwordEncoder.matches(password == null ? "" : password, user.getPasswordHash())) {
      audit.record(userId, ActorType.SYSTEM, null, "auth.step_up.failed", user.getEmail(), null);
      throw new AuthException("Password is not correct");
    }
    if (user.isMfaEnabled() && !totp.verify(secretOf(user), code, Instant.now().getEpochSecond())) {
      audit.record(userId, ActorType.SYSTEM, null, "auth.step_up.failed", user.getEmail(), null);
      throw new AuthException("That verification code is not valid");
    }
    sessions.markStepUp(sessionId);
    audit.record(userId, ActorType.USER, userId, "auth.step_up.ok", user.getEmail(), null);
  }

  @Transactional
  public void logout(UUID userId, UUID sessionId) {
    sessions.revoke(sessionId);
    audit.record(userId, ActorType.USER, userId, "auth.logout", sessionId.toString(), null);
  }

  @Transactional
  public void logoutEverywhere(UUID userId) {
    int revoked = sessions.revokeAll(userId);
    audit.record(userId, ActorType.USER, userId, "auth.logout_everywhere", null,
        Json.obj().put("sessionsRevoked", revoked));
  }

  @Transactional
  public void revokeSession(UUID userId, UUID sessionId) {
    sessions.revoke(sessionId);
    audit.record(userId, ActorType.USER, userId, "auth.session.revoked", sessionId.toString(), null);
  }

  @Transactional(readOnly = true)
  public List<SessionDto> sessions(UUID userId, UUID currentSessionId) {
    return sessions.list(userId, currentSessionId);
  }

  /**
   * Issue a single-use reset token. Returns empty for an unknown address so the endpoint cannot be
   * used to enumerate accounts; the caller responds identically either way.
   */
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
  }

  @Transactional
  public void changePassword(UUID userId, String currentPassword, String newPassword) {
    UserEntity user = users.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
    if (!passwordEncoder.matches(currentPassword == null ? "" : currentPassword,
        user.getPasswordHash())) {
      throw new AuthException("Password is not correct");
    }
    passwordPolicy.validate(newPassword);
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    users.save(user);
    audit.record(userId, ActorType.USER, userId, "auth.password.changed", user.getEmail(), null);
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

  private void registerFailure(UserEntity user) {
    int failures = user.getFailedLogins() + 1;
    user.setFailedLogins(failures);
    if (failures >= props.getLockout().getMaxFailedAttempts()) {
      user.setStatus(UserStatus.LOCKED);
      user.setLockedUntil(Instant.now().plus(props.getLockout().getLockoutDuration()));
      users.save(user);
      audit.record(user.getId(), ActorType.SYSTEM, null, "auth.lockout", user.getEmail(),
          Json.obj().put("failedLogins", failures));
      return;
    }
    users.save(user);
    audit.record(user.getId(), ActorType.SYSTEM, null, "auth.login.failure", user.getEmail(),
        Json.obj().put("failedLogins", failures));
  }

  private String secretOf(UserEntity user) {
    return user.getMfaSecret() == null ? null : encryption.decryptString(user.getMfaSecret());
  }

  private List<String> newRecoveryCodes() {
    return java.util.stream.IntStream.range(0, 8)
        .mapToObj(i -> {
          byte[] bytes = new byte[8];
          random.nextBytes(bytes);
          return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        })
        .toList();
  }
}

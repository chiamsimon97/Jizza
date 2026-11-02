package com.jizza.engine.security.smartsignin;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.dto.LoginResult;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.enums.UserStatus;
import com.jizza.engine.api.error.AuthException;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.error.RateLimitedException;
import com.jizza.engine.api.util.Hashing;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import com.jizza.engine.persistence.entity.UserEntity;
import com.jizza.engine.persistence.repository.UserRepository;
import com.jizza.engine.security.alerts.SecurityAlerts;
import com.jizza.engine.security.config.SecurityProperties;
import com.jizza.engine.security.session.IssuedSession;
import com.jizza.engine.security.session.SessionService;
import com.jizza.engine.security.throttle.LoginThrottle;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Smart sign-in: a browser on the login page shows a QR code, a phone that is already signed in to
 * Jizza scans it and approves, and the browser is signed in without typing a password.
 *
 * <p>Two secrets are made for each attempt. The <b>code</b> goes into the QR and lets a signed-in
 * person look at the attempt and approve or deny it. The <b>poll token</b> stays in the browser that
 * asked, and only it can collect the session once the attempt is approved. So a photo of the QR is
 * worth nothing to whoever took it unless they are also signed in, and then it only signs that
 * browser in to <em>their</em> account.
 *
 * <p>Attempts live in memory, like {@link com.jizza.engine.security.session.PendingMfaStore}: they
 * last two minutes, are single-use, and a restart simply asks the person for a new code. Only
 * hashes of the two secrets are kept.
 */
@Service
public class SmartSignInService {

  /** Who approved, which decides the new session's second-factor state. */
  public enum Via { BROWSER, DEVICE }

  public enum State { PENDING, SCANNED, APPROVED, DENIED, EXPIRED }

  /** What the browser that showed the QR gets back when it starts. */
  public record Started(UUID id, String code, String pollToken, Instant expiresAt) {}

  /** What the approving phone is shown. */
  public record Details(UUID id, String browser, String ip, Instant createdAt, Instant expiresAt,
                        boolean sameNetwork, State state) {}

  private static final class Attempt {
    final UUID id;
    final String codeHash;
    final String pollHash;
    final String device;
    final InetAddress ip;
    final Instant createdAt;
    final Instant expiresAt;
    State state = State.PENDING;
    UUID scannedBy;
    UUID approvedBy;
    boolean mfaSatisfied;
    Via via;
    String approverLabel;

    Attempt(UUID id, String codeHash, String pollHash, String device, InetAddress ip,
            Instant createdAt, Instant expiresAt) {
      this.id = id;
      this.codeHash = codeHash;
      this.pollHash = pollHash;
      this.device = device;
      this.ip = ip;
      this.createdAt = createdAt;
      this.expiresAt = expiresAt;
    }
  }

  private final Map<UUID, Attempt> attempts = new ConcurrentHashMap<>();
  private final Map<String, UUID> byCode = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();

  private final SecurityProperties props;
  private final SessionService sessions;
  private final UserRepository users;
  private final WorkspaceDirectory workspaces;
  private final LoginThrottle throttle;
  private final AuditLog audit;
  private final SecurityAlerts alerts;
  private final Clock clock;

  @Autowired
  public SmartSignInService(SecurityProperties props, SessionService sessions, UserRepository users,
                            WorkspaceDirectory workspaces, LoginThrottle throttle, AuditLog audit,
                            SecurityAlerts alerts) {
    this(props, sessions, users, workspaces, throttle, audit, alerts, Clock.systemUTC());
  }

  public SmartSignInService(SecurityProperties props, SessionService sessions, UserRepository users,
                            WorkspaceDirectory workspaces, LoginThrottle throttle, AuditLog audit,
                            SecurityAlerts alerts, Clock clock) {
    this.props = props;
    this.sessions = sessions;
    this.users = users;
    this.workspaces = workspaces;
    this.throttle = throttle;
    this.audit = audit;
    this.alerts = alerts;
    this.clock = clock;
  }

  public boolean enabled() {
    return props.getSmartSignIn().isEnabled();
  }

  /** The login page asks for a QR code. Public, so it is rate limited per address. */
  public Started start(String device, InetAddress ip, String clientKey) {
    requireEnabled();
    throttle.check("smart-sign-in:" + clientKey);
    sweep();
    if (attempts.size() >= props.getSmartSignIn().getMaxPending()) {
      throw new RateLimitedException("Too many sign-in codes are waiting; try again in a minute");
    }
    String code = secret();
    String pollToken = secret();
    Instant now = clock.instant();
    Attempt attempt = new Attempt(UUID.randomUUID(), Hashing.sha256Hex(code),
        Hashing.sha256Hex(pollToken), device, ip, now,
        now.plus(props.getSmartSignIn().getLifetime()));
    attempts.put(attempt.id, attempt);
    byCode.put(attempt.codeHash, attempt.id);
    return new Started(attempt.id, code, pollToken, attempt.expiresAt);
  }

  /**
   * Where the attempt has got to, for the browser that started it. A wrong poll token is treated
   * the same as an unknown attempt.
   */
  public State status(UUID id, String pollToken) {
    Attempt attempt = owned(id, pollToken);
    synchronized (attempt) {
      return current(attempt);
    }
  }

  /**
   * A signed-in person scanned the code. Claims the attempt for them, so a second phone scanning
   * the same QR cannot approve it, and returns what the phone should show.
   */
  public Details scan(String code, UUID userId, InetAddress approverIp) {
    requireEnabled();
    Attempt attempt = byCodeOrFail(code);
    synchronized (attempt) {
      State state = current(attempt);
      if (state == State.EXPIRED) throw expired();
      if (state == State.APPROVED || state == State.DENIED) throw used();
      if (attempt.scannedBy != null && !attempt.scannedBy.equals(userId)) throw used();
      attempt.scannedBy = userId;
      attempt.state = State.SCANNED;
      audit.record(userId, ActorType.USER, userId, "auth.smart_sign_in.scanned",
          SecurityAlerts.describe(attempt.device), Json.obj().put("attemptId", attempt.id.toString())
              .put("ip", addressOf(attempt.ip)));
      return details(attempt, approverIp);
    }
  }

  /**
   * Approves or denies. When the account uses two-step sign-in (or the server requires it), the
   * approving session must have passed it: a phone that only knows the password cannot hand out a
   * fully signed-in session. A paired Jizza Agent device counts as the second factor, since it
   * proves possession of a key that only that phone holds.
   *
   * @param approverMfaSatisfied the approving browser session passed two-step; ignored for a device
   * @param approverLabel what the audit log calls the approver, such as a device's name
   */
  @Transactional
  public State decide(String code, UUID userId, boolean approve, Via via,
                      boolean approverMfaSatisfied, String approverLabel) {
    requireEnabled();
    Attempt attempt = byCodeOrFail(code);
    synchronized (attempt) {
      State state = current(attempt);
      if (state == State.EXPIRED) throw expired();
      if (state == State.APPROVED || state == State.DENIED) throw used();
      if (attempt.scannedBy != null && !attempt.scannedBy.equals(userId)) throw used();
      UserEntity user = users.findById(userId)
          .orElseThrow(() -> new AuthException("Not authenticated"));
      if (!approve) {
        attempt.state = State.DENIED;
        attempt.scannedBy = userId;
        audit.record(userId, ActorType.USER, userId, "auth.smart_sign_in.denied", user.getEmail(),
            Json.obj().put("attemptId", attempt.id.toString()).put("via", via.name())
                .put("browser", SecurityAlerts.describe(attempt.device)));
        return attempt.state;
      }
      boolean secondFactor = via == Via.DEVICE || approverMfaSatisfied;
      if (!secondFactor && (user.isMfaEnabled() || props.getMfa().isRequired())) {
        throw new JizzaException(HttpStatus.FORBIDDEN, "smart-sign-in-needs-two-step",
            "Two-step sign-in needed",
            "Sign in on this phone with your authenticator code first, then scan again.");
      }
      attempt.state = State.APPROVED;
      attempt.scannedBy = userId;
      attempt.approvedBy = userId;
      attempt.mfaSatisfied = secondFactor;
      attempt.via = via;
      attempt.approverLabel = approverLabel;
      audit.record(userId, ActorType.USER, userId, "auth.smart_sign_in.approved", user.getEmail(),
          Json.obj().put("attemptId", attempt.id.toString()).put("via", via.name())
              .put("approver", approverLabel)
              .put("browser", SecurityAlerts.describe(attempt.device))
              .put("ip", addressOf(attempt.ip)));
      return attempt.state;
    }
  }

  /**
   * The browser that showed the QR collects its session. Works once: the attempt is gone after it.
   * The account is checked again here, so one disabled or locked in the meantime stays out.
   */
  @Transactional
  public LoginResult complete(UUID id, String pollToken, String device, InetAddress ip) {
    Attempt attempt = owned(id, pollToken);
    UUID userId;
    boolean mfaSatisfied;
    synchronized (attempt) {
      State state = current(attempt);
      if (state == State.EXPIRED) throw expired();
      if (state != State.APPROVED) {
        throw new AuthException("smart-sign-in-not-approved", "That sign-in has not been approved");
      }
      userId = attempt.approvedBy;
      mfaSatisfied = attempt.mfaSatisfied;
      forget(attempt);
    }
    UserEntity user = users.findById(userId)
        .orElseThrow(() -> new AuthException("Not authenticated"));
    if (user.getStatus() == UserStatus.DISABLED) throw new AuthException("This account is disabled");
    if (user.getStatus() == UserStatus.LOCKED && user.getLockedUntil() != null
        && clock.instant().isBefore(user.getLockedUntil())) {
      throw new AuthException("account-locked",
          "Too many failed attempts. Try again after " + user.getLockedUntil());
    }
    user.setLastLoginAt(clock.instant());
    users.save(user);
    UUID workspace = workspaces.lastActiveWorkspace(userId)
        .filter(ws -> workspaces.membership(ws, userId).filter(m -> m.active()).isPresent())
        .orElseGet(() -> workspaces.personalWorkspaceOf(userId));
    IssuedSession issued = sessions.create(userId, device, ip, mfaSatisfied, workspace);
    audit.record(userId, ActorType.USER, userId, "auth.login.success", user.getEmail(),
        Json.obj().put("sessionId", issued.session().getId().toString())
            .put("method", "smart_sign_in").put("via", attempt.via.name())
            .put("approver", attempt.approverLabel));
    alerts.signedIn(userId, user.getEmail(), device, ip);
    return new LoginResult(false, null, issued.session().getId(), issued.token(), userId);
  }

  /** The browser closed the QR or asked for a new one. */
  public void cancel(UUID id, String pollToken) {
    Attempt attempt = owned(id, pollToken);
    synchronized (attempt) {
      forget(attempt);
    }
  }

  // ---------------------------------------------------------------------------------------------

  private Details details(Attempt attempt, InetAddress approverIp) {
    return new Details(attempt.id, SecurityAlerts.describe(attempt.device), addressOf(attempt.ip),
        attempt.createdAt, attempt.expiresAt,
        attempt.ip != null && approverIp != null && attempt.ip.equals(approverIp), attempt.state);
  }

  private State current(Attempt attempt) {
    if ((attempt.state == State.PENDING || attempt.state == State.SCANNED
        || attempt.state == State.APPROVED) && !clock.instant().isBefore(attempt.expiresAt)) {
      attempt.state = State.EXPIRED;
    }
    return attempt.state;
  }

  private Attempt owned(UUID id, String pollToken) {
    Attempt attempt = id == null ? null : attempts.get(id);
    if (attempt == null || pollToken == null
        || !MessageDigest.isEqual(attempt.pollHash.getBytes(), Hashing.sha256Hex(pollToken).getBytes())) {
      throw notFound();
    }
    return attempt;
  }

  private Attempt byCodeOrFail(String code) {
    if (code == null || code.isBlank() || code.length() > 100) throw notFound();
    UUID id = byCode.get(Hashing.sha256Hex(code.trim()));
    Attempt attempt = id == null ? null : attempts.get(id);
    if (attempt == null) throw notFound();
    return attempt;
  }

  private void forget(Attempt attempt) {
    attempts.remove(attempt.id);
    byCode.remove(attempt.codeHash);
  }

  /** Drops attempts a while after they expire; the grace lets a late poll still see "expired". */
  void sweep() {
    Instant cutoff = clock.instant().minus(props.getSmartSignIn().getLifetime());
    attempts.values().removeIf(a -> {
      boolean stale = a.expiresAt.isBefore(cutoff);
      if (stale) byCode.remove(a.codeHash);
      return stale;
    });
  }

  private void requireEnabled() {
    if (!enabled()) {
      throw new JizzaException(HttpStatus.NOT_FOUND, "smart-sign-in-off", "Smart sign-in is off",
          "Smart sign-in is turned off on this server.");
    }
  }

  private String secret() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String addressOf(InetAddress ip) {
    return ip == null ? null : ip.getHostAddress();
  }

  private static JizzaException notFound() {
    return new JizzaException(HttpStatus.NOT_FOUND, "smart-sign-in-unknown", "Code not found",
        "That sign-in code is not valid. Ask the login page for a new one.");
  }

  private static JizzaException expired() {
    return new JizzaException(HttpStatus.GONE, "smart-sign-in-expired", "Code expired",
        "That sign-in code has expired. Ask the login page for a new one.");
  }

  private static JizzaException used() {
    return new JizzaException(HttpStatus.CONFLICT, "smart-sign-in-used", "Code already used",
        "That sign-in code was already used. Ask the login page for a new one.");
  }
}

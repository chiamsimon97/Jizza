package com.jizza.platform.api.rest;

import com.jizza.engine.api.dto.LoginResult;
import com.jizza.engine.api.dto.SecurityStatus;
import com.jizza.engine.api.dto.SessionDto;
import com.jizza.engine.api.security.AuthService;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.engine.security.session.SessionCookies;
import com.jizza.platform.api.rest.dto.Requests;
import com.jizza.platform.api.rest.dto.Responses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registration, sign-in, MFA, step-up, sessions and recovery (R3-R6).
 *
 * <p>The session cookie is set here and nowhere else. Note what the responses deliberately omit:
 * the recovery endpoint answers identically whether or not the address exists, and the reset token
 * is emailed rather than returned.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);

  private final AuthService auth;
  private final SessionCookies cookies;
  private final ObjectProvider<com.jizza.engine.api.mail.MailSender> mail;

  public AuthController(AuthService auth, SessionCookies cookies,
                        ObjectProvider<com.jizza.engine.api.mail.MailSender> mail) {
    this.auth = auth;
    this.cookies = cookies;
    this.mail = mail;
  }

  /**
   * Issues the XSRF-TOKEN cookie to a browser that has none yet. CsrfCookieFilter writes the cookie
   * on any response; this endpoint just gives a fresh page something public to call first.
   */
  @GetMapping("/csrf")
  public ResponseEntity<Void> csrf() {
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/register")
  public Responses.Message register(@Valid @RequestBody Requests.Register request) {
    auth.register(request.email(), request.password());
    return new Responses.Message("Account created. Sign in to continue.");
  }

  @PostMapping("/login")
  public ResponseEntity<Responses.LoginResult> login(@Valid @RequestBody Requests.Login request,
                                                     HttpServletRequest http) {
    var result = auth.login(request.email(), request.password(), deviceOf(http), ipOf(http),
        clientKey(http));
    return withSession(result);
  }

  @PostMapping("/mfa/verify")
  public ResponseEntity<Responses.LoginResult> verifyMfa(@Valid @RequestBody Requests.MfaVerify request,
                                                         HttpServletRequest http) {
    var result = auth.verifyMfa(request.mfaToken(), request.code(), deviceOf(http), ipOf(http),
        clientKey(http));
    return withSession(result);
  }

  private ResponseEntity<Responses.LoginResult> withSession(LoginResult result) {
    if (result.sessionToken() == null) {
      return ResponseEntity.ok(new Responses.LoginResult(result.mfaRequired(), result.mfaToken(),
          result.userId(), false));
    }
    boolean enroll = auth.status(result.userId(), result.sessionId()).enrollmentRequired();
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookies.issue(result.sessionToken()).toString())
        .body(new Responses.LoginResult(false, null, result.userId(), enroll));
  }

  /** The signed-in account's protection: two-step status, whether setup is still owed, codes left. */
  @GetMapping("/me")
  public SecurityStatus me() {
    var principal = CurrentUser.require();
    return auth.status(principal.userId(), principal.sessionId());
  }

  /** Enrolment is a security change, so it needs a fresh re-authentication first. */
  @PostMapping("/mfa/setup")
  @com.jizza.engine.api.util.StepUpRequired
  public Responses.MfaSetup setupMfa() {
    var setup = auth.setupMfa(CurrentUser.userId());
    return new Responses.MfaSetup(setup.secret(), setup.provisioningUri());
  }

  @PostMapping("/mfa/confirm")
  public Responses.RecoveryCodes confirmMfa(@Valid @RequestBody Requests.MfaConfirm request) {
    var principal = CurrentUser.require();
    return new Responses.RecoveryCodes(
        auth.confirmMfa(principal.userId(), principal.sessionId(), request.code()));
  }

  /** New recovery codes replace the old ones. Needs a fresh step-up and a current code. */
  @PostMapping("/mfa/recovery-codes")
  @com.jizza.engine.api.util.StepUpRequired
  public Responses.RecoveryCodes regenerateRecoveryCodes(
      @Valid @RequestBody Requests.MfaCode request) {
    return new Responses.RecoveryCodes(
        auth.regenerateRecoveryCodes(CurrentUser.userId(), request.code()));
  }

  @PostMapping("/mfa/disable")
  @com.jizza.engine.api.util.StepUpRequired
  public Responses.Message disableMfa(@Valid @RequestBody Requests.MfaCode request) {
    auth.disableMfa(CurrentUser.userId(), request.code());
    return new Responses.Message("Two-step sign-in is off.");
  }

  @PostMapping("/step-up")
  public Responses.Message stepUp(@Valid @RequestBody Requests.StepUp request) {
    var principal = CurrentUser.require();
    auth.stepUp(principal.userId(), principal.sessionId(), request.password(), request.code());
    return new Responses.Message("Re-authenticated. High-risk actions are unlocked briefly.");
  }

  @PostMapping("/logout")
  public ResponseEntity<Responses.Message> logout() {
    var principal = CurrentUser.require();
    auth.logout(principal.userId(), principal.sessionId());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
        .body(new Responses.Message("Signed out."));
  }

  @PostMapping("/logout-everywhere")
  public ResponseEntity<Responses.Message> logoutEverywhere() {
    auth.logoutEverywhere(CurrentUser.userId());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
        .body(new Responses.Message("Signed out on every device."));
  }

  @GetMapping("/sessions")
  public List<SessionDto> sessions() {
    var principal = CurrentUser.require();
    return auth.sessions(principal.userId(), principal.sessionId());
  }

  @DeleteMapping("/sessions/{id}")
  public Responses.Message revokeSession(@PathVariable UUID id) {
    auth.revokeSession(CurrentUser.userId(), id);
    return new Responses.Message("Session revoked.");
  }

  /**
   * Always answers the same way, whether or not the address is registered: a differing response
   * would turn this endpoint into an account-enumeration oracle.
   */
  @PostMapping("/recover")
  public Responses.Message requestRecovery(@Valid @RequestBody Requests.RecoverRequest request,
                                           HttpServletRequest http) {
    auth.requestPasswordReset(request.email(), clientKey(http)).ifPresent(token -> {
      var sender = mail.getIfAvailable();
      if (sender == null) {
        log.warn("A password reset was requested but no mail sender is configured");
        return;
      }
      sender.send(request.email(), "Reset your Jizza password",
          "Use this code to set a new password. It is valid for 30 minutes.\n\n" + token);
    });
    return new Responses.Message(
        "If that address has an account, a reset link is on its way.");
  }

  @PostMapping("/recover/complete")
  public Responses.Message completeRecovery(@Valid @RequestBody Requests.RecoverComplete request) {
    auth.resetPassword(request.token(), request.newPassword());
    return new Responses.Message("Password changed. Sign in again.");
  }

  @PostMapping("/password")
  @com.jizza.engine.api.util.StepUpRequired
  public Responses.Message changePassword(@Valid @RequestBody Requests.ChangePassword request) {
    var principal = CurrentUser.require();
    auth.changePassword(principal.userId(), principal.sessionId(), request.currentPassword(),
        request.newPassword());
    return new Responses.Message("Password changed. Other devices were signed out.");
  }

  private String deviceOf(HttpServletRequest request) {
    String agent = request.getHeader(HttpHeaders.USER_AGENT);
    if (agent == null) return null;
    return agent.length() <= 250 ? agent : agent.substring(0, 250);
  }

  private InetAddress ipOf(HttpServletRequest request) {
    try {
      return InetAddress.getByName(request.getRemoteAddr());
    } catch (UnknownHostException | SecurityException e) {
      return null;
    }
  }

  private String clientKey(HttpServletRequest request) {
    return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
  }
}

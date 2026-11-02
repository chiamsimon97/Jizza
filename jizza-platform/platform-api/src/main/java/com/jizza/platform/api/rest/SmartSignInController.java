package com.jizza.platform.api.rest;

import com.jizza.engine.api.device.DeviceAgentGateway;
import com.jizza.engine.api.error.AuthException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.PublicEndpoint;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.engine.security.session.SessionCookies;
import com.jizza.engine.security.smartsignin.SmartSignInService;
import com.jizza.engine.security.smartsignin.SmartSignInService.State;
import com.jizza.engine.security.smartsignin.SmartSignInService.Via;
import com.jizza.platform.api.rest.dto.Responses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Smart sign-in: the login page shows a QR code, a phone already signed in to Jizza scans it and
 * approves, and the browser is signed in. See {@link SmartSignInService} for the rules.
 *
 * <p>The QR holds {@code <origin>/smart-sign-in#<code>}. The code sits after the {@code #}, so a
 * browser never sends it in a request line and it stays out of access logs and referrers. The
 * phone's browser reads it from the page and posts it here; the Jizza Agent app reads it from the
 * scanned URL and posts it to the {@code /agent/v1} endpoints, signed with its device key.
 */
@RestController
public class SmartSignInController {

  /** The header the login page sends its poll token in. */
  public static final String POLL_HEADER = "X-Smart-Sign-In-Token";

  /** What a device signs to approve or deny: this prefix, the code, then {@code :approve} or {@code :deny}. */
  public static final String SIGNED_PREFIX = "jizza-smart-sign-in:v1:";

  public record Config(boolean enabled) {}

  public record Started(UUID id, String code, String pollToken, Instant expiresAt) {}

  public record Status(State state) {}

  public record Complete(@NotBlank @Size(max = 100) String pollToken) {}

  public record Scan(@NotBlank @Size(max = 100) String code) {}

  public record Decide(@NotBlank @Size(max = 100) String code, @NotNull Boolean approve) {}

  public record DeviceDecide(String code, Boolean approve, String signature) {}

  public record Decided(State state) {}

  private final SmartSignInService smartSignIn;
  private final SessionCookies cookies;
  private final DeviceAgentGateway devices;
  private final com.jizza.engine.api.security.AuthService auth;

  public SmartSignInController(SmartSignInService smartSignIn, SessionCookies cookies,
                               DeviceAgentGateway devices,
                               com.jizza.engine.api.security.AuthService auth) {
    this.smartSignIn = smartSignIn;
    this.cookies = cookies;
    this.devices = devices;
    this.auth = auth;
  }

  // ---- The browser on the login page ------------------------------------------------------------

  /** Whether the login page should offer the QR at all. */
  @PublicEndpoint
  @GetMapping("/api/auth/smart-sign-in/config")
  public Config config() {
    return new Config(smartSignIn.enabled());
  }

  @PublicEndpoint
  @PostMapping("/api/auth/smart-sign-in/start")
  public Started start(HttpServletRequest http) {
    var s = smartSignIn.start(deviceOf(http), ipOf(http), clientKey(http));
    return new Started(s.id(), s.code(), s.pollToken(), s.expiresAt());
  }

  @PublicEndpoint
  @GetMapping("/api/auth/smart-sign-in/attempts/{id}")
  public Status status(@PathVariable UUID id,
                       @RequestHeader(value = POLL_HEADER, required = false) String pollToken) {
    return new Status(smartSignIn.status(id, pollToken));
  }

  /** Once approved, the browser that showed the QR trades its poll token for a session cookie. */
  @PublicEndpoint
  @PostMapping("/api/auth/smart-sign-in/attempts/{id}/complete")
  public ResponseEntity<Responses.LoginResult> complete(@PathVariable UUID id,
                                                        @Valid @RequestBody Complete body,
                                                        HttpServletRequest http) {
    var result = smartSignIn.complete(id, body.pollToken(), deviceOf(http), ipOf(http));
    boolean enroll = auth.status(result.userId(), result.sessionId()).enrollmentRequired();
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookies.issue(result.sessionToken()).toString())
        .body(new Responses.LoginResult(false, null, result.userId(), enroll));
  }

  @PublicEndpoint
  @DeleteMapping("/api/auth/smart-sign-in/attempts/{id}")
  public ResponseEntity<Void> cancel(@PathVariable UUID id,
                                     @RequestHeader(value = POLL_HEADER, required = false) String pollToken) {
    smartSignIn.cancel(id, pollToken);
    return ResponseEntity.noContent().build();
  }

  // ---- A phone signed in to Jizza in its browser -------------------------------------------------

  @AuthenticatedOnly
  @PostMapping("/api/auth/smart-sign-in/scan")
  public SmartSignInService.Details scan(@Valid @RequestBody Scan body, HttpServletRequest http) {
    return smartSignIn.scan(body.code(), CurrentUser.userId(), ipOf(http));
  }

  @AuthenticatedOnly
  @PostMapping("/api/auth/smart-sign-in/decide")
  public Decided decide(@Valid @RequestBody Decide body, HttpServletRequest http) {
    var principal = CurrentUser.require();
    String label = "Browser: " + com.jizza.engine.security.alerts.SecurityAlerts.describe(deviceOf(http));
    return new Decided(smartSignIn.decide(body.code(), principal.userId(), body.approve(),
        Via.BROWSER, principal.mfaSatisfied(), label));
  }

  // ---- The Jizza Agent app on a paired phone -----------------------------------------------------

  @PublicEndpoint
  @PostMapping("/agent/v1/smart-sign-in/scan")
  public SmartSignInService.Details deviceScan(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody Scan body, HttpServletRequest http) {
    var caller = caller(authorization);
    var owner = personalOwner(caller);
    if (body == null || body.code() == null) throw new ValidationException("code is required");
    return smartSignIn.scan(body.code(), owner.userId(), ipOf(http));
  }

  /**
   * The device signs {@code jizza-smart-sign-in:v1:<code>:approve} (or {@code :deny}) with its key,
   * so the answer is bound to this code and could only have come from the paired phone.
   */
  @PublicEndpoint
  @PostMapping("/agent/v1/smart-sign-in/decide")
  public Decided deviceDecide(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody DeviceDecide body) {
    var caller = caller(authorization);
    var owner = personalOwner(caller);
    if (body == null || body.code() == null || body.approve() == null) {
      throw new ValidationException("code and approve are required");
    }
    String signed = SIGNED_PREFIX + body.code().trim() + (body.approve() ? ":approve" : ":deny");
    if (!devices.verifySignature(caller, signed, body.signature())) {
      throw new AuthException("bad_signature", "The device signature does not match");
    }
    return new Decided(smartSignIn.decide(body.code(), owner.userId(), body.approve(), Via.DEVICE,
        false, "Device: " + owner.name()));
  }

  private DeviceAgentGateway.Caller caller(String authorization) {
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      throw new AuthException("device_token", "A valid device token is required");
    }
    return devices.authenticate(authorization.substring(7).trim());
  }

  private DeviceAgentGateway.Owned personalOwner(DeviceAgentGateway.Caller caller) {
    return devices.personalOwner(caller).orElseThrow(() -> new AuthException("device_not_personal",
        "Only a phone paired by a person can approve a sign-in"));
  }

  private static String deviceOf(HttpServletRequest request) {
    String agent = request.getHeader(HttpHeaders.USER_AGENT);
    if (agent == null) return null;
    return agent.length() <= 250 ? agent : agent.substring(0, 250);
  }

  private static InetAddress ipOf(HttpServletRequest request) {
    try {
      return InetAddress.getByName(request.getRemoteAddr());
    } catch (UnknownHostException | SecurityException e) {
      return null;
    }
  }

  private static String clientKey(HttpServletRequest request) {
    return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
  }
}

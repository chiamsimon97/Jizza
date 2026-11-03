package com.jizza.platform.api.rest;

import com.jizza.engine.api.device.Devices;
import com.jizza.engine.api.dto.LoginResult;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.security.AuthService;
import com.jizza.engine.api.util.PublicEndpoint;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Signing in on the phone itself, for people who open the Jizza app nowhere near a computer.
 *
 * <p>The phone never gets a browser session or keeps a password. The person proves who they are
 * exactly as on the web (password, then the two-step code), and the answer is a one-time pairing
 * code the app enrolls with straight away through {@code POST /agent/v1/enroll}. The session the
 * login opened is closed again at once. From then on the phone signs in with its own key.
 *
 * <p>Two-step sign-in must be on. A paired phone can approve a browser sign-in (smart sign-in)
 * and that approval counts as the second factor, so pairing a phone with a password alone would
 * turn one factor into two. People without two-step sign-in pair from Settings › Devices instead.
 */
@RestController
public class PhoneSignInController {

  public record Login(String email, String password) {}

  public record Mfa(String mfaToken, String code) {}

  /**
   * Either the second step is still needed ({@code mfaRequired} and {@code mfaToken}), or the
   * person is through and gets the pairing code to enroll with.
   */
  public record Result(boolean mfaRequired, String mfaToken, String pairingCode,
                       Instant expiresAt, String pairUrl) {}

  private final AuthService auth;
  private final Devices devices;
  private final WorkspaceDirectory workspaces;
  private final String publicUrl;

  public PhoneSignInController(AuthService auth, Devices devices, WorkspaceDirectory workspaces,
                               @Value("${jizza.devices.public-url:}") String publicUrl) {
    this.auth = auth;
    this.devices = devices;
    this.workspaces = workspaces;
    this.publicUrl = publicUrl;
  }

  @PublicEndpoint
  @PostMapping("/agent/v1/login")
  public Result login(@RequestBody(required = false) Login body, HttpServletRequest http) {
    if (body == null || blank(body.email()) || blank(body.password())) {
      throw new ValidationException("email and password are required");
    }
    if (body.email().length() > 320 || body.password().length() > 1024) {
      throw new ValidationException("email or password is too long");
    }
    LoginResult result = auth.login(body.email().trim(), body.password(), deviceOf(http),
        ipOf(http), clientKey(http));
    if (result.mfaRequired()) {
      return new Result(true, result.mfaToken(), null, null, null);
    }
    // Signed in with a password alone: close that session and say why the phone can't pair.
    if (result.sessionId() != null) auth.logout(result.userId(), result.sessionId());
    throw new TwoStepRequired();
  }

  @PublicEndpoint
  @PostMapping("/agent/v1/login/mfa")
  public Result mfa(@RequestBody(required = false) Mfa body, HttpServletRequest http) {
    if (body == null || blank(body.mfaToken()) || blank(body.code())) {
      throw new ValidationException("mfaToken and code are required");
    }
    LoginResult result = auth.verifyMfa(body.mfaToken(), body.code().trim(), deviceOf(http),
        ipOf(http), clientKey(http));
    if (result.sessionId() == null || result.userId() == null) {
      throw new ValidationException("That code did not finish signing in");
    }
    try {
      var pairing = devices.startPairing(result.userId(),
          workspaces.personalWorkspaceOf(result.userId()), serverUrl());
      return new Result(false, null, pairing.code(), pairing.expiresAt(), pairing.qrPayload());
    } finally {
      auth.logout(result.userId(), result.sessionId());
    }
  }

  /** The account has no two-step sign-in, so a phone can't be paired with a password alone. */
  public static class TwoStepRequired extends JizzaException {
    public TwoStepRequired() {
      super(HttpStatus.FORBIDDEN, "two-step-required", "Two-step sign-in needed",
          "Turn on two-step sign-in in Jizza on the web first, or pair this phone from "
              + "Settings › Devices");
    }
  }

  private String serverUrl() {
    if (publicUrl != null && !publicUrl.isBlank()) return publicUrl.replaceAll("/+$", "");
    return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static String deviceOf(HttpServletRequest request) {
    String agent = request.getHeader(HttpHeaders.USER_AGENT);
    String label = agent == null ? "Jizza app" : agent;
    return label.length() <= 250 ? label : label.substring(0, 250);
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

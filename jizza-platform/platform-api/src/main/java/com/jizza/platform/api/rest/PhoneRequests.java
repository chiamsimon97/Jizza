package com.jizza.platform.api.rest;

import com.jizza.engine.api.device.DeviceAgentGateway;
import com.jizza.engine.api.device.DeviceAgentGateway.Companion;
import com.jizza.engine.api.device.PhoneAccess;
import com.jizza.engine.api.error.AuthException;
import com.jizza.engine.api.error.JizzaException;
import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Who is calling the phone API under {@code /agent/v1/me}, and whether a change they ask for really
 * came from their phone.
 *
 * <p>Every call carries the device token, as every {@code /agent/v1} call does, and must come from a
 * phone a person paired: a shared machine enrolled with a token has nobody's account to show. A call
 * that changes something (answer an approval, run a workflow, cancel a run, Pause all) must also be
 * signed with the phone's device key, which the app only unlocks with the person's fingerprint, face
 * or PIN. The signed text binds the method, the path, the exact body and a fresh nonce:
 *
 * <pre>jizza-mobile:v1:&lt;METHOD&gt;:&lt;path&gt;:&lt;signedAt epoch ms&gt;:&lt;nonce&gt;:&lt;base64url SHA-256 of the body&gt;</pre>
 *
 * sent as {@link #SIGNATURE}, {@link #SIGNED_AT} and {@link #NONCE}. A signature older than two
 * minutes, or a nonce this server has already seen from that phone, is refused.
 */
@Component
public class PhoneRequests {

  public static final String SIGNATURE = "X-Jizza-Signature";
  public static final String SIGNED_AT = "X-Jizza-Signed-At";
  public static final String NONCE = "X-Jizza-Nonce";
  public static final String PREFIX = "jizza-mobile:v1:";

  static final Duration WINDOW = Duration.ofMinutes(2);
  private static final int MAX_REMEMBERED = 50_000;

  private final DeviceAgentGateway devices;
  private final Clock clock;
  /** deviceId:nonce, until when it is remembered. */
  private final Map<String, Instant> seen = new ConcurrentHashMap<>();

  @org.springframework.beans.factory.annotation.Autowired
  public PhoneRequests(DeviceAgentGateway devices) {
    this(devices, Clock.systemUTC());
  }

  PhoneRequests(DeviceAgentGateway devices, Clock clock) {
    this.devices = devices;
    this.clock = clock;
  }

  /** The phone's owner, for a call that only reads. */
  public Companion read(HttpServletRequest request) {
    return companion(request);
  }

  /**
   * The phone's owner, for a call that changes something: the phone must be allowed {@code needed}
   * and the request must carry a valid signature over exactly {@code body}.
   */
  public Companion signed(HttpServletRequest request, byte[] body, PhoneAccess needed) {
    DeviceAgentGateway.Caller caller = caller(request);
    Companion phone = devices.companion(caller).orElseThrow(PhoneRequests::notPersonal);
    if (!phone.access().allows(needed)) {
      throw new PhoneAccessDenied(needed);
    }
    String signature = request.getHeader(SIGNATURE);
    String signedAt = request.getHeader(SIGNED_AT);
    String nonce = request.getHeader(NONCE);
    if (signature == null || signedAt == null || nonce == null || nonce.isBlank()
        || nonce.length() > 100) {
      throw new AuthException("bad_signature",
          "Sign this request with the phone's key (" + SIGNATURE + ", " + SIGNED_AT + ", "
              + NONCE + ")");
    }
    Instant at;
    try {
      at = Instant.ofEpochMilli(Long.parseLong(signedAt.trim()));
    } catch (NumberFormatException e) {
      throw new AuthException("bad_signature", SIGNED_AT + " must be milliseconds since 1970");
    }
    Instant now = clock.instant();
    if (at.isBefore(now.minus(WINDOW)) || at.isAfter(now.plus(WINDOW))) {
      throw new AuthException("signature_expired",
          "The signature is too old or the phone's clock is wrong; sign it again");
    }
    String text = signedText(request.getMethod(), request.getRequestURI(), signedAt.trim(),
        nonce, body);
    if (!devices.verifySignature(caller, text, signature)) {
      throw new AuthException("bad_signature", "The device signature does not match");
    }
    remember(phone.deviceId() + ":" + nonce, now);
    return phone;
  }

  /** What the phone signs, as {@link PhoneRequests} describes. */
  public static String signedText(String method, String path, String signedAt, String nonce,
                                  byte[] body) {
    return PREFIX + method.toUpperCase(java.util.Locale.ROOT) + ":" + path + ":" + signedAt + ":"
        + nonce + ":" + bodyHash(body);
  }

  static String bodyHash(byte[] body) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(body == null ? new byte[0] : body);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void remember(String key, Instant now) {
    if (seen.size() > MAX_REMEMBERED) seen.values().removeIf(until -> until.isBefore(now));
    Instant until = now.plus(WINDOW.multipliedBy(2));
    Instant previous = seen.putIfAbsent(key, until);
    if (previous != null && previous.isAfter(now)) {
      throw new AuthException("replayed", "This signed request was already used");
    }
    if (previous != null) seen.put(key, until);
  }

  private Companion companion(HttpServletRequest request) {
    return devices.companion(caller(request)).orElseThrow(PhoneRequests::notPersonal);
  }

  private DeviceAgentGateway.Caller caller(HttpServletRequest request) {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      throw new AuthException("device_token", "A valid device token is required");
    }
    return devices.authenticate(authorization.substring(7).trim());
  }

  private static AuthException notPersonal() {
    return new AuthException("device_not_personal",
        "Only a phone paired by a person can see that person's Jizza");
  }

  /** The phone is paired, but its owner has not let it do this. */
  public static class PhoneAccessDenied extends JizzaException {
    public PhoneAccessDenied(PhoneAccess needed) {
      super(HttpStatus.FORBIDDEN, "phone-access", "Not allowed from this phone",
          switch (needed) {
            case READ -> "This phone may not read your Jizza";
            case APPROVE -> "This phone may only read. Allow approvals for it in Settings › Devices";
            case FULL -> "This phone may not run or stop work. Give it full access in "
                + "Settings › Devices";
          });
    }
  }
}

package com.jizza.security.mfa;

import com.jizza.security.config.SecurityProperties;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** RFC 6238 TOTP over HMAC-SHA1 — what Google Authenticator, Aegis and 1Password all speak. */
@Component
public class TotpService {

  private final SecurityProperties props;
  private final SecureRandom random = new SecureRandom();

  public TotpService(SecurityProperties props) {
    this.props = props;
  }

  /** A fresh 160-bit seed, base32-encoded for enrolment. */
  public String newSecret() {
    byte[] seed = new byte[20];
    random.nextBytes(seed);
    return Base32.encode(seed);
  }

  public String provisioningUri(String secret, String accountName) {
    String issuer = props.getTotp().getIssuer();
    return "otpauth://totp/" + enc(issuer) + ":" + enc(accountName)
        + "?secret=" + secret
        + "&issuer=" + enc(issuer)
        + "&algorithm=SHA1"
        + "&digits=" + props.getTotp().getDigits()
        + "&period=" + props.getTotp().getPeriodSeconds();
  }

  public String code(String base32Secret, long epochSeconds) {
    return code(base32Secret, epochSeconds, 0);
  }

  public String code(String base32Secret, long epochSeconds, int stepOffset) {
    long step = epochSeconds / props.getTotp().getPeriodSeconds() + stepOffset;
    byte[] key = Base32.decode(base32Secret);
    byte[] counter = ByteBuffer.allocate(8).putLong(step).array();
    byte[] hash = hmacSha1(key, counter);
    int offset = hash[hash.length - 1] & 0x0F;
    int binary = ((hash[offset] & 0x7F) << 24)
        | ((hash[offset + 1] & 0xFF) << 16)
        | ((hash[offset + 2] & 0xFF) << 8)
        | (hash[offset + 3] & 0xFF);
    int digits = props.getTotp().getDigits();
    int otp = binary % (int) Math.pow(10, digits);
    return String.format("%0" + digits + "d", otp);
  }

  /** Constant-time comparison across the configured drift window. */
  public boolean verify(String base32Secret, String submitted, long epochSeconds) {
    if (base32Secret == null || submitted == null) return false;
    String candidate = submitted.trim();
    boolean match = false;
    int skew = props.getTotp().getSkewSteps();
    for (int offset = -skew; offset <= skew; offset++) {
      match |= constantTimeEquals(code(base32Secret, epochSeconds, offset), candidate);
    }
    return match;
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a.length() != b.length()) return false;
    int diff = 0;
    for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
    return diff == 0;
  }

  private static byte[] hmacSha1(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(key, "HmacSHA1"));
      return mac.doFinal(data);
    } catch (Exception e) {
      throw new IllegalStateException("HMAC-SHA1 unavailable", e);
    }
  }

  private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}

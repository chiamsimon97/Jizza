package com.jizza.engine.devices.crypto;

import com.jizza.engine.api.error.ValidationException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Device keys as the protocol writes them: {@code ed25519:<base64 of the X.509 public key>}. Every
 * platform's crypto library can export a key in that form.
 */
public final class Ed25519Keys {

  public static final String PREFIX = "ed25519:";

  private Ed25519Keys() {}

  /** @throws ValidationException when it is not an Ed25519 public key */
  public static PublicKey parse(String encoded) {
    if (encoded == null || !encoded.startsWith(PREFIX)) {
      throw new ValidationException("The device key must be written as ed25519:<base64>");
    }
    try {
      byte[] der = Base64.getDecoder().decode(encoded.substring(PREFIX.length()).trim());
      PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
      if (!"EdDSA".equals(key.getAlgorithm()) && !"Ed25519".equals(key.getAlgorithm())) {
        throw new ValidationException("The device key is not an Ed25519 key");
      }
      return key;
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      throw new ValidationException("The device key could not be read");
    }
  }

  public static String encode(PublicKey key) {
    return PREFIX + Base64.getEncoder().encodeToString(key.getEncoded());
  }

  /** Whether {@code signature} (base64, an optional {@code ed25519:} prefix) signs {@code text}. */
  public static boolean verify(PublicKey key, String text, String signature) {
    if (signature == null || text == null) return false;
    String raw = signature.startsWith(PREFIX) ? signature.substring(PREFIX.length()) : signature;
    try {
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(key);
      verifier.update(text.getBytes(StandardCharsets.UTF_8));
      return verifier.verify(Base64.getDecoder().decode(raw.trim()));
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      return false;
    }
  }
}

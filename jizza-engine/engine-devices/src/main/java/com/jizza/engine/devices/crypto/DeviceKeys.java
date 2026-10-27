package com.jizza.engine.devices.crypto;

import com.jizza.engine.api.error.ValidationException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Device keys as the protocol writes them: {@code ed25519:<base64 X.509 public key>}, or
 * {@code p256:<base64 X.509 public key>} for ECDSA on NIST P-256, the only kind an iPhone's Secure
 * Enclave makes and the usual kind in Android hardware. Every platform's crypto library can export
 * a key in that form.
 *
 * <p>A P-256 signature is ECDSA with SHA-256, either DER (what most libraries give) or the raw
 * 64-byte r‖s form (what CryptoKit's {@code rawRepresentation} gives); both are accepted.
 */
public final class DeviceKeys {

  public static final String ED25519 = "ed25519:";
  public static final String P256 = "p256:";

  private static final BigInteger P256_ORDER = p256Order();

  private DeviceKeys() {}

  /** @throws ValidationException when it is not an Ed25519 or P-256 public key */
  public static PublicKey parse(String encoded) {
    if (encoded == null || (!encoded.startsWith(ED25519) && !encoded.startsWith(P256))) {
      throw new ValidationException("The device key must be written as ed25519:<base64> or p256:<base64>");
    }
    boolean ec = encoded.startsWith(P256);
    try {
      byte[] der = Base64.getDecoder().decode(encoded.substring(ec ? P256.length() : ED25519.length()).trim());
      PublicKey key = KeyFactory.getInstance(ec ? "EC" : "Ed25519").generatePublic(new X509EncodedKeySpec(der));
      if (ec) {
        if (!(key instanceof ECPublicKey ecKey) || !ecKey.getParams().getOrder().equals(P256_ORDER)) {
          throw new ValidationException("The device key is not a P-256 key");
        }
      } else if (!"EdDSA".equals(key.getAlgorithm()) && !"Ed25519".equals(key.getAlgorithm())) {
        throw new ValidationException("The device key is not an Ed25519 key");
      }
      return key;
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      throw new ValidationException("The device key could not be read");
    }
  }

  public static String encode(PublicKey key) {
    return (key instanceof ECPublicKey ? P256 : ED25519)
        + Base64.getEncoder().encodeToString(key.getEncoded());
  }

  /** Whether {@code signature} (base64, an optional {@code ed25519:} or {@code p256:} prefix) signs {@code text}. */
  public static boolean verify(PublicKey key, String text, String signature) {
    if (signature == null || text == null) return false;
    String raw = signature.startsWith(ED25519) ? signature.substring(ED25519.length())
        : signature.startsWith(P256) ? signature.substring(P256.length()) : signature;
    try {
      byte[] sig = Base64.getDecoder().decode(raw.trim());
      Signature verifier;
      if (key instanceof ECPublicKey) {
        verifier = Signature.getInstance(sig.length == 64 ? "SHA256withECDSAinP1363Format" : "SHA256withECDSA");
      } else {
        verifier = Signature.getInstance("Ed25519");
      }
      verifier.initVerify(key);
      verifier.update(text.getBytes(StandardCharsets.UTF_8));
      return verifier.verify(sig);
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      return false;
    }
  }

  private static BigInteger p256Order() {
    try {
      AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
      params.init(new ECGenParameterSpec("secp256r1"));
      return params.getParameterSpec(ECParameterSpec.class).getOrder();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("P-256 is not available in this Java runtime", e);
    }
  }
}

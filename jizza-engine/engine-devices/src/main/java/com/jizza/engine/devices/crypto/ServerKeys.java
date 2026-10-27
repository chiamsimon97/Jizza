package com.jizza.engine.devices.crypto;

import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.persistence.entity.DeviceServerKeyEntity;
import com.jizza.engine.persistence.repository.DeviceServerKeyRepository;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The server's own protocol keys: an Ed25519 key that signs everything sent to a device, and a
 * secret that signs access tokens. Both are made on first use and kept encrypted with the master
 * key, so every process of one Jizza signs with the same keys and a restart changes nothing.
 */
@Component
public class ServerKeys {

  static final String SIGNING_KEY = "command-signing";
  static final String TOKEN_SECRET = "token-secret";

  private final DeviceServerKeyRepository repository;
  private final EncryptionService encryption;
  private final TransactionTemplate transactions;
  private volatile PrivateKey signingKey;
  private volatile String publicKey;
  private volatile byte[] tokenSecret;

  public ServerKeys(DeviceServerKeyRepository repository, EncryptionService encryption,
                    TransactionTemplate transactions) {
    this.repository = repository;
    this.encryption = encryption;
    this.transactions = transactions;
  }

  /** {@code ed25519:<base64>}, handed to each device at enrollment to check what it is sent. */
  public String publicKey() {
    load();
    return publicKey;
  }

  /**
   * Base64url SHA-256 of the signing key's X.509 bytes, without padding. It rides in the pairing QR
   * code so the app can check the key the enroll answer gives it belongs to this server.
   */
  public String fingerprint() {
    String key = publicKey();
    try {
      byte[] der = Base64.getDecoder().decode(key.substring(DeviceKeys.ED25519.length()));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(
          java.security.MessageDigest.getInstance("SHA-256").digest(der));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Base64 Ed25519 signature over {@code text}, prefixed {@code ed25519:}. */
  public String sign(String text) {
    load();
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(signingKey);
      signer.update(text.getBytes(StandardCharsets.UTF_8));
      return DeviceKeys.ED25519 + Base64.getEncoder().encodeToString(signer.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Could not sign a device message", e);
    }
  }

  public byte[] hmac(byte[] data) {
    load();
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(tokenSecret, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Could not sign a device token", e);
    }
  }

  public static boolean constantTimeEquals(byte[] a, byte[] b) {
    return MessageDigest.isEqual(a, b);
  }

  private void load() {
    if (signingKey != null && tokenSecret != null) return;
    synchronized (this) {
      if (signingKey != null && tokenSecret != null) return;
      transactions.executeWithoutResult(status -> {
        DeviceServerKeyEntity signing = repository.findById(SIGNING_KEY)
            .orElseGet(() -> create(SIGNING_KEY, true));
        DeviceServerKeyEntity secret = repository.findById(TOKEN_SECRET)
            .orElseGet(() -> create(TOKEN_SECRET, false));
        try {
          signingKey = KeyFactory.getInstance("Ed25519").generatePrivate(
              new PKCS8EncodedKeySpec(encryption.decrypt(signing.getPrivateKey())));
        } catch (GeneralSecurityException e) {
          throw new IllegalStateException("The device signing key could not be read", e);
        }
        publicKey = signing.getPublicKey();
        tokenSecret = encryption.decrypt(secret.getPrivateKey());
      });
    }
  }

  private DeviceServerKeyEntity create(String id, boolean signing) {
    DeviceServerKeyEntity entity = new DeviceServerKeyEntity();
    entity.setId(id);
    if (signing) {
      try {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        entity.setPrivateKey(encryption.encrypt(pair.getPrivate().getEncoded()));
        PublicKey pub = pair.getPublic();
        entity.setPublicKey(DeviceKeys.encode(pub));
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException("Ed25519 is not available in this Java runtime", e);
      }
    } else {
      byte[] secret = new byte[32];
      new SecureRandom().nextBytes(secret);
      entity.setPrivateKey(encryption.encrypt(secret));
    }
    try {
      return repository.saveAndFlush(entity);
    } catch (DataIntegrityViolationException e) {
      // Another process made it first; use theirs.
      return repository.findById(id).orElseThrow(() -> e);
    }
  }
}

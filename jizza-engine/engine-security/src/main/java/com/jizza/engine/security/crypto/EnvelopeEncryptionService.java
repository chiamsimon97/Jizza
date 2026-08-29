package com.jizza.engine.security.crypto;

import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.crypto.KeyProvider;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Envelope encryption (R7): every value gets a fresh AES-256-GCM data key, and that data key is
 * wrapped by the master key. The ciphertext records which master key wrapped it, so rotating the
 * master key does not require rewriting existing rows.
 *
 * <p>Layout: {@code version | keyIdLen | keyId | wrapIv | wrappedKeyLen | wrappedKey | dataIv |
 * ciphertext}.
 */
@Component
public class EnvelopeEncryptionService implements EncryptionService {

  private static final byte VERSION = 1;
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final String TRANSFORM = "AES/GCM/NoPadding";

  private final KeyProvider keyProvider;
  private final SecureRandom random = new SecureRandom();

  public EnvelopeEncryptionService(KeyProvider keyProvider) {
    this.keyProvider = keyProvider;
  }

  @Override
  public byte[] encrypt(byte[] plain) {
    if (plain == null) return null;
    try {
      KeyGenerator gen = KeyGenerator.getInstance("AES");
      gen.init(256, random);
      SecretKey dataKey = gen.generateKey();

      byte[] keyId = keyProvider.activeKeyId().getBytes(StandardCharsets.UTF_8);
      byte[] wrapIv = randomIv();
      byte[] wrappedKey = gcm(Cipher.ENCRYPT_MODE, keyProvider.masterKey(), wrapIv, dataKey.getEncoded());

      byte[] dataIv = randomIv();
      byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, dataKey, dataIv, plain);

      ByteBuffer buf = ByteBuffer.allocate(
          1 + 2 + keyId.length + IV_BYTES + 2 + wrappedKey.length + IV_BYTES + ciphertext.length);
      buf.put(VERSION);
      buf.putShort((short) keyId.length).put(keyId);
      buf.put(wrapIv);
      buf.putShort((short) wrappedKey.length).put(wrappedKey);
      buf.put(dataIv);
      buf.put(ciphertext);
      return buf.array();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Encryption failed", e);
    }
  }

  @Override
  public byte[] decrypt(byte[] ciphertext) {
    if (ciphertext == null) return null;
    try {
      ByteBuffer buf = ByteBuffer.wrap(ciphertext);
      byte version = buf.get();
      if (version != VERSION) {
        throw new IllegalStateException("Unsupported ciphertext version " + version);
      }
      byte[] keyId = new byte[buf.getShort()];
      buf.get(keyId);
      byte[] wrapIv = new byte[IV_BYTES];
      buf.get(wrapIv);
      byte[] wrappedKey = new byte[buf.getShort()];
      buf.get(wrappedKey);
      byte[] dataIv = new byte[IV_BYTES];
      buf.get(dataIv);
      byte[] payload = new byte[buf.remaining()];
      buf.get(payload);

      SecretKey master = keyProvider.keyById(new String(keyId, StandardCharsets.UTF_8));
      byte[] dataKeyBytes = gcm(Cipher.DECRYPT_MODE, master, wrapIv, wrappedKey);
      SecretKey dataKey = new SecretKeySpec(dataKeyBytes, "AES");
      return gcm(Cipher.DECRYPT_MODE, dataKey, dataIv, payload);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Decryption failed: the ciphertext is corrupt or was written "
          + "under a master key that is no longer available", e);
    }
  }

  private byte[] randomIv() {
    byte[] iv = new byte[IV_BYTES];
    random.nextBytes(iv);
    return iv;
  }

  private byte[] gcm(int mode, SecretKey key, byte[] iv, byte[] input) throws Exception {
    Cipher cipher = Cipher.getInstance(TRANSFORM);
    cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
    return cipher.doFinal(input);
  }
}

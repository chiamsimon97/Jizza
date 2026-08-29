package com.jizza.engine.api.crypto;

/**
 * Envelope encryption (R7): a fresh AES-GCM data key per value, wrapped by the master key from
 * {@link KeyProvider}. Ciphertext is self-describing so rotation does not break old rows.
 */
public interface EncryptionService {

  byte[] encrypt(byte[] plain);

  byte[] decrypt(byte[] ciphertext);

  default byte[] encryptString(String s) {
    return s == null ? null : encrypt(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  default String decryptString(byte[] ct) {
    return ct == null ? null : new String(decrypt(ct), java.nio.charset.StandardCharsets.UTF_8);
  }
}

package com.jizza.security.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.common.crypto.KeyProvider;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EnvelopeEncryptionServiceTest {

  /** In-memory stand-in for the keystore, supporting more than one key so rotation is testable. */
  private static final class TestKeyProvider implements KeyProvider {
    private final Map<String, SecretKey> keys = new HashMap<>();
    private String active;

    TestKeyProvider(String id) { rotateTo(id); }

    void rotateTo(String id) {
      try {
        KeyGenerator gen = KeyGenerator.getInstance("AES");
        gen.init(256, new SecureRandom());
        keys.put(id, gen.generateKey());
        active = id;
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    void forget(String id) { keys.remove(id); }

    @Override public SecretKey masterKey() { return keys.get(active); }
    @Override public String activeKeyId() { return active; }
    @Override public SecretKey keyById(String keyId) {
      SecretKey k = keys.get(keyId);
      if (k == null) throw new IllegalStateException("no key " + keyId);
      return k;
    }
  }

  private TestKeyProvider keys;
  private EnvelopeEncryptionService encryption;

  @BeforeEach
  void setUp() {
    keys = new TestKeyProvider("k1");
    encryption = new EnvelopeEncryptionService(keys);
  }

  @Test
  void roundTripsASecret() {
    String secret = "ya29.a0AfH6SMB-an-access-token";
    byte[] ciphertext = encryption.encryptString(secret);
    assertThat(new String(ciphertext, StandardCharsets.UTF_8)).doesNotContain(secret);
    assertThat(encryption.decryptString(ciphertext)).isEqualTo(secret);
  }

  @Test
  void usesAFreshDataKeyPerValueSoIdenticalPlaintextsDifferOnDisk() {
    byte[] a = encryption.encryptString("same value");
    byte[] b = encryption.encryptString("same value");
    assertThat(a).isNotEqualTo(b);
    assertThat(encryption.decryptString(a)).isEqualTo(encryption.decryptString(b));
  }

  @Test
  void survivesMasterKeyRotationForCiphertextWrittenUnderTheOldKey() {
    byte[] underK1 = encryption.encryptString("written before rotation");
    keys.rotateTo("k2");
    byte[] underK2 = encryption.encryptString("written after rotation");

    assertThat(encryption.decryptString(underK1)).isEqualTo("written before rotation");
    assertThat(encryption.decryptString(underK2)).isEqualTo("written after rotation");
  }

  @Test
  void refusesToDecryptWhenTheWrappingKeyIsGone() {
    byte[] ciphertext = encryption.encryptString("value");
    keys.rotateTo("k2");
    keys.forget("k1");
    assertThatThrownBy(() -> encryption.decryptString(ciphertext))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void detectsTamperedCiphertext() {
    byte[] ciphertext = encryption.encryptString("value");
    ciphertext[ciphertext.length - 1] ^= 0x01;
    assertThatThrownBy(() -> encryption.decryptString(ciphertext))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Decryption failed");
  }

  @Test
  void passesNullThrough() {
    assertThat(encryption.encrypt(null)).isNull();
    assertThat(encryption.decrypt(null)).isNull();
  }
}

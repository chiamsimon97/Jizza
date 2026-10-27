package com.jizza.engine.devices.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.error.ValidationException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class DeviceKeysTest {

  @Test
  void ed25519KeysRoundTripAndVerify() throws Exception {
    KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    String encoded = DeviceKeys.encode(pair.getPublic());
    assertThat(encoded).startsWith("ed25519:");
    assertThat(DeviceKeys.verify(DeviceKeys.parse(encoded), "nonce", sign("Ed25519", pair, "nonce"))).isTrue();
    assertThat(DeviceKeys.verify(DeviceKeys.parse(encoded), "other", sign("Ed25519", pair, "nonce"))).isFalse();
  }

  @Test
  void p256KeysAcceptDerAndRawSignatures() throws Exception {
    KeyPair pair = ec("secp256r1");
    String encoded = DeviceKeys.encode(pair.getPublic());
    assertThat(encoded).startsWith("p256:");
    var key = DeviceKeys.parse(encoded);
    // DER, as most libraries write it; raw r||s, as CryptoKit's rawRepresentation does.
    assertThat(DeviceKeys.verify(key, "nonce", sign("SHA256withECDSA", pair, "nonce"))).isTrue();
    assertThat(DeviceKeys.verify(key, "nonce", sign("SHA256withECDSAinP1363Format", pair, "nonce"))).isTrue();
    assertThat(DeviceKeys.verify(key, "nonce", "p256:" + sign("SHA256withECDSA", pair, "nonce"))).isTrue();
    assertThat(DeviceKeys.verify(key, "tampered", sign("SHA256withECDSA", pair, "nonce"))).isFalse();
  }

  @Test
  void otherCurvesAndMislabelledKeysAreRefused() throws Exception {
    String p384 = Base64.getEncoder().encodeToString(ec("secp384r1").getPublic().getEncoded());
    assertThatThrownBy(() -> DeviceKeys.parse("p256:" + p384)).isInstanceOf(ValidationException.class);
    String p256AsEd = Base64.getEncoder().encodeToString(ec("secp256r1").getPublic().getEncoded());
    assertThatThrownBy(() -> DeviceKeys.parse("ed25519:" + p256AsEd)).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> DeviceKeys.parse("rsa:abc")).isInstanceOf(ValidationException.class);
  }

  private static KeyPair ec(String curve) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec(curve));
    return generator.generateKeyPair();
  }

  private static String sign(String algorithm, KeyPair pair, String text) throws Exception {
    Signature signer = Signature.getInstance(algorithm);
    signer.initSign(pair.getPrivate());
    signer.update(text.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(signer.sign());
  }
}

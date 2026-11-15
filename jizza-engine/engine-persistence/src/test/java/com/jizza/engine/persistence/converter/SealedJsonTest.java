package com.jizza.engine.persistence.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jizza.engine.api.crypto.EncryptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SealedJsonTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Reversible stand-in: real ciphertext comes from EnvelopeEncryptionService in engine-security. */
  private static final EncryptionService XOR = new EncryptionService() {
    @Override public byte[] encrypt(byte[] plain) { return flip(plain); }
    @Override public byte[] decrypt(byte[] ciphertext) { return flip(ciphertext); }
    private byte[] flip(byte[] in) {
      byte[] out = in.clone();
      for (int i = 0; i < out.length; i++) out[i] ^= 0x5a;
      return out;
    }
  };

  @AfterEach
  void reset() {
    SealedJson.initialize(null, true);
  }

  @Test
  void sealsAndUnsealsRoundTrip() throws Exception {
    SealedJson.initialize(XOR, true);
    JsonNode plain = MAPPER.readTree("{\"inputs\":{\"name\":\"A Person\",\"phone\":\"9876543210\"}}");
    JsonNode sealed = SealedJson.seal(plain);
    assertThat(SealedJson.isSealed(sealed)).isTrue();
    assertThat(sealed.toString()).doesNotContain("A Person").doesNotContain("9876543210");
    assertThat(SealedJson.unseal(sealed)).isEqualTo(plain);
  }

  @Test
  void legacyPlaintextRowsPassThrough() throws Exception {
    SealedJson.initialize(XOR, true);
    JsonNode legacy = MAPPER.readTree("{\"variables\":{\"x\":1},\"_browser\":{\"deviceId\":\"d\"}}");
    assertThat(SealedJson.unseal(legacy)).isSameAs(legacy);
  }

  @Test
  void neverSealsTwice() throws Exception {
    SealedJson.initialize(XOR, true);
    JsonNode sealed = SealedJson.seal(MAPPER.readTree("{\"a\":1}"));
    assertThat(SealedJson.seal(sealed)).isSameAs(sealed);
  }

  @Test
  void sealOffStillReadsSealedRows() throws Exception {
    SealedJson.initialize(XOR, true);
    JsonNode sealed = SealedJson.seal(MAPPER.readTree("{\"a\":1}"));
    SealedJson.initialize(XOR, false);
    JsonNode plain = MAPPER.readTree("{\"b\":2}");
    assertThat(SealedJson.seal(plain)).isSameAs(plain);
    assertThat(SealedJson.unseal(sealed).path("a").asInt()).isEqualTo(1);
  }

  @Test
  void readingSealedWithoutEncryptionFailsLoudly() throws Exception {
    SealedJson.initialize(XOR, true);
    JsonNode sealed = SealedJson.seal(MAPPER.readTree("{\"a\":1}"));
    SealedJson.initialize(null, true);
    assertThatThrownBy(() -> SealedJson.unseal(sealed)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void nullAndMissingValuesPassThrough() {
    SealedJson.initialize(XOR, true);
    assertThat(SealedJson.seal(null)).isNull();
    assertThat(SealedJson.unseal(null)).isNull();
  }
}

package com.jizza.security.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Base32Test {

  @Test
  void matchesRfc4648TestVectors() {
    assertThat(Base32.encodeUtf8("f")).isEqualTo("MY");
    assertThat(Base32.encodeUtf8("fo")).isEqualTo("MZXQ");
    assertThat(Base32.encodeUtf8("foo")).isEqualTo("MZXW6");
    assertThat(Base32.encodeUtf8("foobar")).isEqualTo("MZXW6YTBOI");
  }

  @Test
  void decodesWhatItEncodes() {
    byte[] original = "12345678901234567890".getBytes(StandardCharsets.UTF_8);
    assertThat(Base32.decode(Base32.encode(original))).isEqualTo(original);
  }
}

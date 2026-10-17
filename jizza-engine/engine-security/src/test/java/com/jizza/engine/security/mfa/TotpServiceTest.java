package com.jizza.engine.security.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.security.config.SecurityProperties;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TotpServiceTest {

  /** RFC 6238 appendix B uses the ASCII seed "12345678901234567890" with HMAC-SHA1. */
  private static final String RFC_SECRET =
      Base32.encode("12345678901234567890".getBytes(StandardCharsets.UTF_8));

  private TotpService service(int digits, int skew) {
    SecurityProperties props = new SecurityProperties();
    props.getTotp().setDigits(digits);
    props.getTotp().setSkewSteps(skew);
    return new TotpService(props);
  }

  @Test
  void matchesRfc6238Vectors() {
    TotpService totp = service(8, 0);
    assertThat(totp.code(RFC_SECRET, 59L)).isEqualTo("94287082");
    assertThat(totp.code(RFC_SECRET, 1111111109L)).isEqualTo("07081804");
    assertThat(totp.code(RFC_SECRET, 1234567890L)).isEqualTo("89005924");
  }

  @Test
  void acceptsACodeFromTheCurrentStep() {
    TotpService totp = service(6, 1);
    long now = 1_700_000_000L;
    assertThat(totp.verify(RFC_SECRET, totp.code(RFC_SECRET, now), now)).isTrue();
  }

  @Test
  void absorbsOneStepOfClockDriftButNoMore() {
    TotpService totp = service(6, 1);
    long now = 1_700_000_000L;
    assertThat(totp.verify(RFC_SECRET, totp.code(RFC_SECRET, now, -1), now)).isTrue();
    assertThat(totp.verify(RFC_SECRET, totp.code(RFC_SECRET, now, 1), now)).isTrue();
    assertThat(totp.verify(RFC_SECRET, totp.code(RFC_SECRET, now, 3), now)).isFalse();
  }

  @Test
  void rejectsWrongAndMissingCodes() {
    TotpService totp = service(6, 1);
    assertThat(totp.verify(RFC_SECRET, "000000", 1_700_000_000L)).isFalse();
    assertThat(totp.verify(RFC_SECRET, null, 1_700_000_000L)).isFalse();
    assertThat(totp.verify(null, "123456", 1_700_000_000L)).isFalse();
  }

  @Test
  void generatesADistinctSecretAndAProvisioningUri() {
    TotpService totp = service(6, 1);
    String secret = totp.newSecret();
    assertThat(secret).isNotEqualTo(totp.newSecret()).hasSizeGreaterThanOrEqualTo(32);
    assertThat(totp.provisioningUri(secret, "user@example.com"))
        .startsWith("otpauth://totp/Jizza:user%40example.com")
        .contains("secret=" + secret)
        .contains("digits=6");
  }
}

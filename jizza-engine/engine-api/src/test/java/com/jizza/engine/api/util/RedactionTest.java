package com.jizza.engine.api.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RedactionTest {

  @Test
  void scrubsBearerTokens() {
    String out = Redaction.scrub("calling with Authorization: Bearer ya29.A0ARrdaM-longtokenvalue");
    assertThat(out).doesNotContain("ya29.A0ARrdaM-longtokenvalue").contains(Redaction.MASK);
  }

  @Test
  void scrubsJsonCredentialFields() {
    String out = Redaction.scrub("{\"access_token\":\"abc123\",\"client_secret\":\"shhh\",\"ok\":true}");
    assertThat(out).doesNotContain("abc123").doesNotContain("shhh").contains("\"ok\":true");
  }

  @Test
  void scrubsQueryStringCredentials() {
    assertThat(Redaction.scrub("https://x/y?api_key=SUPERSECRET&a=1")).doesNotContain("SUPERSECRET");
  }

  @Test
  void detectsSecretLikeContent() {
    assertThat(Redaction.containsSecretLike("sk-abcdefghijklmnopqrstuvwx")).isTrue();
    assertThat(Redaction.containsSecretLike("a perfectly ordinary sentence")).isFalse();
    assertThat(Redaction.containsSecretLike(Redaction.scrub("Bearer aaaaaaaaaaaaaaaaaaaa"))).isFalse();
  }
}

package com.jizza.connectors.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PkceTest {

  @Test
  void matchesTheRfc7636S256TestVector() {
    String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    assertThat(Pkce.challenge(verifier)).isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
  }

  @Test
  void mintsHighEntropyVerifiersAndStates() {
    assertThat(Pkce.newVerifier()).isNotEqualTo(Pkce.newVerifier()).hasSizeGreaterThanOrEqualTo(43);
    assertThat(Pkce.newState()).isNotEqualTo(Pkce.newState()).hasSizeGreaterThanOrEqualTo(32);
  }

  @Test
  void verifiersAndStatesAreUrlSafe() {
    assertThat(Pkce.newVerifier()).matches("[A-Za-z0-9_-]+");
    assertThat(Pkce.newState()).matches("[A-Za-z0-9_-]+");
  }
}

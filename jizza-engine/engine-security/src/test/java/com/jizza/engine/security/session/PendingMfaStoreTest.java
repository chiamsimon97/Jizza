package com.jizza.engine.security.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PendingMfaStoreTest {

  private final PendingMfaStore store = new PendingMfaStore();

  @Test
  void aTokenIsSingleUse() {
    UUID userId = UUID.randomUUID();
    String token = store.open(userId);
    assertThat(store.consume(token)).contains(userId);
    assertThat(store.consume(token)).isEmpty();
  }

  @Test
  void unknownTokensAreRejected() {
    assertThat(store.consume("not-a-token")).isEmpty();
  }
}

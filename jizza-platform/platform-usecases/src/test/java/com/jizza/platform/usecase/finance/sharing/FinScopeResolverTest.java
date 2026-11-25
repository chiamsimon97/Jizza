package com.jizza.platform.usecase.finance.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.error.NotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Until Family sharing exists (Phase 5), nobody reaches anyone else's finance rows. */
class FinScopeResolverTest {

  private final FinScopeResolver resolver = new FinScopeResolver();

  @Test
  void theCallersOwnScopeIsTheirs() {
    UUID me = UUID.randomUUID();
    var scope = resolver.own(me);
    assertThat(scope.ownerUserId()).isEqualTo(me);
    assertThat(scope.own()).isTrue();
    assertThat(resolver.forOwner(me, me).ownerUserId()).isEqualTo(me);
  }

  @Test
  void anotherOwnersRowsReadAsMissing() {
    assertThatThrownBy(() -> resolver.forOwner(UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> resolver.own(null)).isInstanceOf(NotFoundException.class);
  }
}

package com.jizza.platform.usecase.vita;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.engine.api.finance.FinanceStore.PersonRow;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Until sharing exists, the resolver gives a caller their own household's people and nobody else's. */
class VitaScopeResolverTest {

  private final UUID me = UUID.randomUUID();
  private final UUID stranger = UUID.randomUUID();
  private final FinanceStore people = mock(FinanceStore.class);
  private final VitaScopeResolver scopes = new VitaScopeResolver(people, org.mockito.Mockito.mock(com.jizza.engine.api.vita.VitaShares.class), org.mockito.Mockito.mock(com.jizza.engine.api.workspace.WorkspaceDirectory.class));

  private PersonRow person(UUID owner, String kind, String status) {
    return new PersonRow(UUID.randomUUID(), owner, "Someone", kind, "huf".equals(kind) ? "huf" : "individual",
        null, false, false, status, 1, null, "active".equals(status) ? null : LocalDate.of(2026, 11, 1));
  }

  @Test
  void ownPersonIsReachedAndIsTheCallersOwnScope() {
    PersonRow amma = person(me, "parent", "active");
    when(people.person(me, amma.id())).thenReturn(Optional.of(amma));
    var p = scopes.person(me, amma.id());
    assertThat(p.owner()).isEqualTo(me);
    assertThat(p.scope().own()).isTrue();
    assertThat(p.requireOpen()).isSameAs(p);
  }

  @Test
  void neverReturnsAnotherUsersRows() {
    PersonRow theirs = person(stranger, "parent", "active");
    when(people.person(stranger, theirs.id())).thenReturn(Optional.of(theirs));
    when(people.person(me, theirs.id())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> scopes.person(me, theirs.id())).isInstanceOf(NotFoundException.class);
    assertThat(scopes.own(me).ownerUserId()).isEqualTo(me);
    assertThatThrownBy(() -> scopes.own(null)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void anHufHasNoHealthRecord() {
    PersonRow huf = person(me, "huf", "active");
    when(people.person(me, huf.id())).thenReturn(Optional.of(huf));
    assertThatThrownBy(() -> scopes.person(me, huf.id())).isInstanceOf(NotFoundException.class);
  }

  @Test
  void aClosedRecordCanBeReadButNotChanged() {
    PersonRow appa = person(me, "parent", "deceased");
    when(people.person(me, appa.id())).thenReturn(Optional.of(appa));
    var p = scopes.person(me, appa.id());
    assertThat(p.open()).isFalse();
    assertThatThrownBy(p::requireOpen).isInstanceOf(ValidationException.class).hasMessageContaining("2026-11-01");
  }
}

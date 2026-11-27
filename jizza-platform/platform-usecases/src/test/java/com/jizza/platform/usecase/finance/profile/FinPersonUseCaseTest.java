package com.jizza.platform.usecase.finance.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.engine.api.finance.FinanceStore.PersonFields;
import com.jizza.engine.api.finance.FinanceStore.PersonRow;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinPersonUseCaseTest {

  private final UUID me = UUID.randomUUID();
  private final FinanceStore store = mock(FinanceStore.class);
  private final AuditLog audit = mock(AuditLog.class);
  private final FinScopeResolver scopes = new FinScopeResolver();
  private final FinPersonUseCase people = new FinPersonUseCase(store, scopes, audit);
  private final ConsentService consents = new ConsentService(store, scopes, audit);

  @Test
  void addsAChildWithADateOfBirth() {
    LocalDate dob = LocalDate.now().minusYears(8);
    when(store.createPerson(eq(me), any())).thenAnswer(i -> row(UUID.randomUUID(), i.getArgument(1)));

    PersonRow child = people.create(me,
        new FinPersonUseCase.PersonInput("  Riya ", "Child", "minor", dob, null, true));

    assertThat(child.displayName()).isEqualTo("Riya");
    assertThat(child.kind()).isEqualTo("child");
    assertThat(child.dependent()).isTrue();
    verify(store).ensureSelf(me, "Me");
    verify(store, org.mockito.Mockito.never()).ensureProfile(any(), any());
  }

  @Test
  void refusesWhatTheFormCannotMean() {
    assertThatThrownBy(() -> people.create(me, input("Me again", "self", "individual", null)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> people.create(me, input("", "child", "minor", LocalDate.now().minusYears(2))))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> people.create(me, input("Riya", "child", "minor", null)))
        .hasMessageContaining("date of birth");
    assertThatThrownBy(() -> people.create(me, input("Dad", "parent", "minor", LocalDate.of(1960, 1, 1))))
        .hasMessageContaining("18 or older");
    assertThatThrownBy(() -> people.create(me, input("Family HUF", "huf", "individual", null)))
        .hasMessageContaining("HUF");
    assertThatThrownBy(() -> people.create(me, input("Later", "child", "minor", LocalDate.now().plusDays(3))))
        .hasMessageContaining("future");
    verify(store, never()).createPerson(any(), any());
  }

  @Test
  void youCannotArchiveYourself() {
    UUID selfId = UUID.randomUUID();
    when(store.person(me, selfId)).thenReturn(Optional.of(
        row(selfId, new PersonFields("Me", "self", "individual", null, true, false))));

    assertThatThrownBy(() -> people.archive(me, selfId)).isInstanceOf(ValidationException.class);
  }

  @Test
  void someoneElsesPersonIsNotFound() {
    UUID theirs = UUID.randomUUID();
    when(store.person(me, theirs)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> people.get(me, theirs)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> consents.list(me, theirs)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void consentNotesAreOnlyForManagedAdults() {
    UUID child = UUID.randomUUID();
    UUID mother = UUID.randomUUID();
    when(store.person(me, child)).thenReturn(Optional.of(
        row(child, new PersonFields("Riya", "child", "minor", LocalDate.now().minusYears(5), false, true))));
    when(store.person(me, mother)).thenReturn(Optional.of(
        row(mother, new PersonFields("Amma", "parent", "individual", LocalDate.of(1958, 6, 1), false, true))));
    when(store.createConsent(eq(me), eq(mother), eq("manage"), any(), any())).thenReturn(
        new FinanceStore.ConsentRow(UUID.randomUUID(), mother, "manage", null, null, "in person", null, null));

    assertThatThrownBy(() -> consents.record(me, child, "view", null, null))
        .isInstanceOf(ValidationException.class);
    assertThat(consents.record(me, mother, "MANAGE", "in person", null).scope()).isEqualTo("manage");
  }

  @Test
  void evidenceMustBeTheOwnersFile() {
    UUID mother = UUID.randomUUID();
    UUID file = UUID.randomUUID();
    when(store.person(me, mother)).thenReturn(Optional.of(
        row(mother, new PersonFields("Amma", "parent", "individual", null, false, true))));
    when(store.ownsFile(me, file)).thenReturn(false);

    assertThatThrownBy(() -> consents.record(me, mother, "view", null, file))
        .isInstanceOf(NotFoundException.class);
  }

  private static FinPersonUseCase.PersonInput input(String name, String kind, String type, LocalDate dob) {
    return new FinPersonUseCase.PersonInput(name, kind, type, dob, false, false);
  }

  private PersonRow row(UUID id, PersonFields f) {
    return new PersonRow(id, me, f.displayName(), f.kind(), f.type(), f.dob(), f.earning(),
        f.dependent(), "active", 1, null, null);
  }
}

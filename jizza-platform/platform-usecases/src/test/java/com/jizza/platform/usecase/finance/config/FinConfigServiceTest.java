package com.jizza.platform.usecase.finance.config;

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
import com.jizza.engine.api.finance.FinanceStore.ConfigRow;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FinConfigServiceTest {

  private final UUID me = UUID.randomUUID();
  private final FinanceStore store = mock(FinanceStore.class);
  private final AuditLog audit = mock(AuditLog.class);
  private final FinConfigService service = new FinConfigService(store, new FinScopeResolver(), audit);
  private final List<ConfigRow> rows = new ArrayList<>();

  private static final LocalDate EPOCH = LocalDate.of(2000, 1, 1);

  @BeforeEach
  void setUp() {
    rows.add(system("returns.expected", "{\"equity\":10,\"debt\":6.5}", EPOCH, null));
    rows.add(system("tax.new_regime", "{\"standardDeduction\":50000}", LocalDate.of(2025, 4, 1), null));
    rows.add(system("tax.new_regime", "{\"standardDeduction\":75000}", LocalDate.of(2026, 4, 1), null));
    rows.add(system("regulatory.sip_failure", "{\"monthly\":3}", EPOCH, LocalDate.of(2026, 5, 1)));
    rows.add(system("allocation.tiers", "[{\"upTo\":250000,\"capPct\":85},{\"upTo\":null,\"capPct\":75}]", EPOCH, null));
    when(store.config(me)).thenAnswer(i -> rows);
  }

  @Test
  void showsKaiDefaultsWithTheVersionInForceToday() {
    var items = service.list(me, LocalDate.of(2026, 11, 25));

    var tax = items.stream().filter(i -> i.key().equals("tax.new_regime")).findFirst().orElseThrow();
    assertThat(tax.value().path("standardDeduction").asInt()).isEqualTo(75000);
    assertThat(tax.source()).isEqualTo("kai_default");
    assertThat(tax.area()).isEqualTo("tax");
    assertThat(items).extracting(FinConfigService.ConfigItem::key).doesNotHaveDuplicates();
  }

  @Test
  void onlyTaxAndRegulatoryFiguresNeedVerification() {
    var items = service.list(me, LocalDate.of(2026, 11, 25));

    assertThat(items).filteredOn(FinConfigService.ConfigItem::needsVerification)
        .extracting(FinConfigService.ConfigItem::key)
        .containsExactlyInAnyOrder("tax.new_regime", "regulatory.sip_failure");
  }

  @Test
  void aVerificationFromBeforeTheFinancialYearDoesNotCount() {
    var stale = new ConfigRow(UUID.randomUUID(), null, "tax.x", Json.obj(), LocalDate.of(2026, 4, 1),
        null, null, LocalDate.of(2026, 3, 31), "CA");
    var fresh = new ConfigRow(UUID.randomUUID(), null, "tax.x", Json.obj(), LocalDate.of(2026, 4, 1),
        null, null, LocalDate.of(2026, 4, 2), "CA");

    assertThat(FinConfigService.needsVerification(stale)).isTrue();
    assertThat(FinConfigService.needsVerification(fresh)).isFalse();
    assertThat(FinConfigService.financialYearStart(LocalDate.of(2027, 3, 31)))
        .isEqualTo(LocalDate.of(2026, 4, 1));
  }

  @Test
  void anOverrideShowsAsYoursAndKeepsKaisValueForReset() {
    rows.add(new ConfigRow(UUID.randomUUID(), me, "returns.expected",
        Json.read("{\"equity\":9,\"debt\":6.5}"), EPOCH, null, null, null, null));

    var item = service.list(me, LocalDate.of(2026, 11, 25)).stream()
        .filter(i -> i.key().equals("returns.expected")).findFirst().orElseThrow();

    assertThat(item.source()).isEqualTo("you");
    assertThat(item.isOverride()).isTrue();
    assertThat(item.value().path("equity").asInt()).isEqualTo(9);
    assertThat(item.defaultValue().path("equity").asInt()).isEqualTo(10);
  }

  @Test
  void anOverrideMustKeepTheShapeOfKaisDefault() {
    assertThatThrownBy(() -> service.put(me, "returns.expected", Json.read("{\"equity\":\"lots\",\"debt\":6.5}")))
        .isInstanceOf(ValidationException.class).hasMessageContaining("equity");
    assertThatThrownBy(() -> service.put(me, "returns.expected", Json.read("{\"equity\":9}")))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> service.put(me, "returns.expected", Json.read("[1]")))
        .isInstanceOf(ValidationException.class);
    verify(store, never()).putOverride(any(), any(), any(), any());
  }

  @Test
  void anOpenEndedLastTierCanStayEmpty() {
    service.put(me, "allocation.tiers",
        Json.read("[{\"upTo\":200000,\"capPct\":85},{\"upTo\":500000,\"capPct\":80},{\"upTo\":null,\"capPct\":75}]"));

    verify(store).putOverride(eq(me), eq("allocation.tiers"), eq(EPOCH), any());
  }

  @Test
  void anUnknownKeyIsNotFound() {
    assertThatThrownBy(() -> service.put(me, "made.up", Json.obj()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.reset(me, "made.up")).isInstanceOf(NotFoundException.class);
  }

  private static ConfigRow system(String key, String json, LocalDate from, LocalDate to) {
    return new ConfigRow(UUID.randomUUID(), null, key, Json.read(json), from, to, null, null, null);
  }
}

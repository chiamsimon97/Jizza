package com.jizza.platform.usecase.vita;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaStore;
import com.jizza.engine.api.vita.VitaStore.ConfigRow;
import com.jizza.platform.usecase.vita.config.VitaConfigService;
import com.jizza.platform.usecase.vita.config.VitaConfigService.ConfigItem;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Vita's figures in their three states: verified, needs verification and not set. */
class VitaConfigServiceTest {

  private final UUID me = UUID.randomUUID();
  private final VitaStore store = mock(VitaStore.class);
  private final VitaConfigService config = new VitaConfigService(store,
      new VitaScopeResolver(mock(FinanceStore.class)), mock(AuditLog.class));
  private final List<ConfigRow> rows = new ArrayList<>();

  private void row(UUID user, String key, String json, String source, LocalDate verified) {
    rows.add(new ConfigRow(UUID.randomUUID(), user, key, Json.read(json), LocalDate.of(2000, 1, 1), null,
        source, verified, verified == null ? null : "Dr Rao"));
    when(store.config(me)).thenReturn(rows);
  }

  private ConfigItem item(String key) {
    return config.list(me).stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow();
  }

  @Test
  void aSeededFigureWithoutVerifiedOnNeedsVerification() {
    row(null, "clinical.redflags.bp", "{\"systolicAbove\":180,\"diastolicAbove\":120}", "AHA", null);
    ConfigItem bp = item("clinical.redflags.bp");
    assertThat(bp.state()).isEqualTo("needs_verification");
    assertThat(bp.needsVerification()).isTrue();
    assertThat(bp.label()).contains("needs verification");
    assertThat(bp.source()).isEqualTo("vita_default");
  }

  @Test
  void aVerifiedFigureIsVerified() {
    row(null, "clinical.ranges.general.bp_home", "{\"systolic\":{\"below\":135}}", "ISH 2020", LocalDate.of(2026, 12, 1));
    ConfigItem home = item("clinical.ranges.general.bp_home");
    assertThat(home.state()).isEqualTo("verified");
    assertThat(home.needsVerification()).isFalse();
    assertThat(home.verifiedOn()).isEqualTo(LocalDate.of(2026, 12, 1));
  }

  @Test
  void aFigureWithNoSourceIsNotSetAndHasNoNumber() {
    row(null, "display.units", "{\"weight\":\"kg\"}", null, null);
    ConfigItem high = item("clinical.redflags.high_sugar");
    assertThat(high.state()).isEqualTo("not_set");
    assertThat(high.value()).isNull();
    assertThat(high.defaultValue()).isNull();
    assertThat(high.label()).isEqualTo("Not set: ask your doctor");
    assertThat(config.value(me, "clinical.ranges.general.spo2")).isEmpty();
    assertThatThrownBy(() -> config.put(me, "clinical.redflags.high_sugar", Json.read("{\"above\":300}")))
        .isInstanceOf(ValidationException.class);
    verify(store, never()).putOverride(any(), any(), any(), any());
    assertThat(VitaConfigService.NOT_SET).allMatch(k -> k.startsWith("clinical."));
  }

  @Test
  void productDefaultsAreNotFiguresToVerify() {
    row(null, "display.units", "{\"weight\":\"kg\",\"height\":\"cm\",\"temperature\":\"C\"}", null, null);
    assertThat(item("display.units").state()).isEqualTo("product_default");
    assertThat(item("display.units").needsVerification()).isFalse();
  }

  @Test
  void theEmergencyNumberIsCheckedLikeAClinicalFigure() {
    row(null, "emergency.numbers", "{\"emergency\":\"112\",\"ambulanceWhereTheyLive\":\"\"}", "MHA", null);
    assertThat(item("emergency.numbers").needsVerification()).isTrue();
  }

  @Test
  void anOverrideMustKeepTheDefaultsShapeAndWins() {
    row(null, "emergency.numbers", "{\"emergency\":\"112\",\"ambulanceWhereTheyLive\":\"\"}", "MHA", null);
    assertThatThrownBy(() -> config.put(me, "emergency.numbers", Json.read("{\"emergency\":112}")))
        .isInstanceOf(ValidationException.class);
    config.put(me, "emergency.numbers", Json.read("{\"emergency\":\"112\",\"ambulanceWhereTheyLive\":\"108\"}"));
    verify(store).putOverride(eq(me), eq("emergency.numbers"), eq(LocalDate.of(2000, 1, 1)), any());

    row(me, "emergency.numbers", "{\"emergency\":\"112\",\"ambulanceWhereTheyLive\":\"108\"}", null, null);
    ConfigItem mine = item("emergency.numbers");
    assertThat(mine.source()).isEqualTo("you");
    assertThat(mine.value().path("ambulanceWhereTheyLive").asText()).isEqualTo("108");
    assertThat(mine.defaultValue().path("ambulanceWhereTheyLive").asText()).isEmpty();
  }
}

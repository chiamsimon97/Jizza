package com.jizza.platform.usecase.vita.medicine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.scheduler.RecurrenceDays;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Fixture G of spec section 10, the pure part: Father's telmisartan, 1 tablet at 08:00 from 1 Dec
 * 2026, changed on 15 Dec from 40 mg to 80 mg from 16 Dec. The first schedule ends on 15 Dec, the
 * second starts on 16 Dec, no day has two doses, and the adherence figures are the fixture's. The
 * stored close, removal and event are checked end to end in {@code VitaMedicinesE2eTest}.
 */
class FixtureGTest {

  static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
  static final UUID MED = UUID.randomUUID();
  static final UUID PERSON = UUID.randomUUID();
  static final DoseExpander.Plan OLD = new DoseExpander.Plan(UUID.randomUUID(), MED, PERSON,
      List.of(LocalTime.of(8, 0)), "FREQ=DAILY", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 15));
  static final DoseExpander.Plan NEW = new DoseExpander.Plan(UUID.randomUUID(), MED, PERSON,
      List.of(LocalTime.of(8, 0)), "FREQ=DAILY", LocalDate.of(2026, 12, 16), null);

  static Instant day(int d) {
    return LocalDate.of(2026, 12, d).atStartOfDay(INDIA).toInstant();
  }

  @Test
  void theOldScheduleEndsOn15DecemberAndTheNewOneStartsOn16() {
    Instant from = day(1);
    Instant to = LocalDate.of(2027, 1, 1).atStartOfDay(INDIA).toInstant();
    var old = DoseExpander.dueTimes(OLD, INDIA, from, to, RecurrenceDays.DAILY);
    var neu = DoseExpander.dueTimes(NEW, INDIA, from, to, RecurrenceDays.DAILY);
    assertThat(old).hasSize(15);
    assertThat(old.get(14)).isEqualTo(LocalDate.of(2026, 12, 15).atTime(8, 0).atZone(INDIA).toInstant());
    assertThat(neu).hasSize(16);
    assertThat(neu.get(0)).isEqualTo(LocalDate.of(2026, 12, 16).atTime(8, 0).atZone(INDIA).toInstant());
    List<Instant> all = new ArrayList<>(old);
    all.addAll(neu);
    assertThat(all).doesNotHaveDuplicates().hasSize(31);
  }

  @Test
  void theEventReadsFrom40MgTo80Mg() {
    assertThat(VitaMedicineUseCase.describe("40 mg", null)).isEqualTo("40 mg");
    var schedule = com.jizza.engine.api.util.Json.obj().put("doseUnits", 1).put("doseUnit", "tablet")
        .put("recurrence", "FREQ=DAILY");
    schedule.putArray("times").add("08:00");
    assertThat(VitaMedicineUseCase.describe(null, schedule)).isEqualTo("1 tablet at 08:00");
    assertThat(VitaMedicineUseCase.describe("80 mg", schedule)).isEqualTo("80 mg, 1 tablet at 08:00");
  }

  @Test
  void adherenceIs93Then100And97ForDecember() {
    List<AdherenceCalculator.Dose> first = new ArrayList<>();
    for (int i = 0; i < 14; i++) first.add(new AdherenceCalculator.Dose("taken", false));
    first.add(new AdherenceCalculator.Dose("missed", false));
    List<AdherenceCalculator.Dose> second = new ArrayList<>();
    for (int i = 0; i < 16; i++) second.add(new AdherenceCalculator.Dose("taken", false));

    var a = AdherenceCalculator.of(first);
    assertThat(a.exact()).isEqualByComparingTo("93.33");
    assertThat(a.percent()).isEqualTo(93);
    var b = AdherenceCalculator.of(second);
    assertThat(b.exact()).isEqualByComparingTo("100.00");
    assertThat(b.percent()).isEqualTo(100);
    List<AdherenceCalculator.Dose> december = new ArrayList<>(first);
    december.addAll(second);
    var c = AdherenceCalculator.of(december);
    assertThat(c.due()).isEqualTo(31);
    assertThat(c.taken()).isEqualTo(30);
    assertThat(c.exact()).isEqualByComparingTo("96.77");
    assertThat(c.percent()).isEqualTo(97);
  }
}

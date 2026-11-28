package com.jizza.platform.usecase.vita.documents;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.util.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The safety rules of spec 5.3 that Vita's own code applies to what the model copied, one each. */
class ExtractionRulesTest {

  static final JsonNode SHORTHAND = Json.read("""
      {"codes": {
        "OD": {"meaning": "once a day", "slots": ["morning"]},
        "BD": {"meaning": "twice a day", "slots": ["morning", "night"]},
        "TDS": {"meaning": "three times a day", "slots": ["morning", "afternoon", "night"]},
        "HS": {"meaning": "at bedtime", "slots": ["bedtime"]},
        "SOS": {"meaning": "only when needed", "asNeeded": true},
        "AC": {"meaning": "before food", "withFood": "before"},
        "PC": {"meaning": "after food", "withFood": "after"}},
       "pattern": {"slots": ["morning", "afternoon", "night"]}}""");
  static final Map<String, String> TIMES = Map.of("morning", "08:00", "afternoon", "14:00", "evening", "18:00",
      "night", "21:00", "bedtime", "22:00");
  static final RxShorthand RX = new RxShorthand(SHORTHAND, TIMES);

  // ── Numbers ──────────────────────────────────────────────────────────────

  @Test
  void digitGroupingIsNotADecimalComma() {
    assertThat(PrintedNumber.parse("7,500")).isEqualTo(new PrintedNumber.Parsed(new BigDecimal("7500"), false));
    assertThat(PrintedNumber.parse("2,50,000")).isEqualTo(new PrintedNumber.Parsed(new BigDecimal("250000"), false));
    assertThat(PrintedNumber.parse("1,50,000").value()).isEqualByComparingTo("150000");
    assertThat(PrintedNumber.parse("60,000").value()).isEqualByComparingTo("60000");
    assertThat(PrintedNumber.parse("1,234,567").value()).isEqualByComparingTo("1234567");
    assertThat(PrintedNumber.parse("151.00").value()).isEqualByComparingTo("151");
  }

  @Test
  void aCommaBeforeOneOrTwoDigitsIsReadAsADecimalAndMarkedUnsure() {
    assertThat(PrintedNumber.parse("0,9")).isEqualTo(new PrintedNumber.Parsed(new BigDecimal("0.9"), true));
    assertThat(PrintedNumber.parse("12,45")).isEqualTo(new PrintedNumber.Parsed(new BigDecimal("12.45"), true));
    assertThat(PrintedNumber.parse("Negative")).isNull();
    assertThat(PrintedNumber.parse("7,5000")).isNull();
  }

  // ── Ranges ───────────────────────────────────────────────────────────────

  @Test
  void printedRangesAreReadAndTextBandsAreKeptAsText() {
    var r = PrintedRange.parse("<200.00");
    assertThat(r.high()).isEqualByComparingTo("200");
    assertThat(r.highInclusive()).isFalse();
    assertThat(r.flagFor(new BigDecimal("200"))).isEqualTo("high");
    assertThat(r.flagFor(new BigDecimal("199.9"))).isEqualTo("normal");

    var above = PrintedRange.parse(">40.00");
    assertThat(above.low()).isEqualByComparingTo("40");
    assertThat(above.flagFor(new BigDecimal("40"))).isEqualTo("low");

    var upTo = PrintedRange.parse("Up to 1.2");
    assertThat(upTo.high()).isEqualByComparingTo("1.2");
    assertThat(upTo.highInclusive()).isTrue();
    assertThat(upTo.flagFor(new BigDecimal("1.2"))).isEqualTo("normal");

    var between = PrintedRange.parse("4.0 - 5.6");
    assertThat(between.low()).isEqualByComparingTo("4.0");
    assertThat(between.high()).isEqualByComparingTo("5.6");
    assertThat(between.flagFor(new BigDecimal("7.1"))).isEqualTo("high");

    var counts = PrintedRange.parse("1,50,000-4,10,000");
    assertThat(counts.low()).isEqualByComparingTo("150000");
    assertThat(counts.high()).isEqualByComparingTo("410000");

    var bands = PrintedRange.parse("Desirable: < 200; Borderline: 200-240; High: > 240");
    assertThat(bands.bounded()).isFalse();
    assertThat(bands.text()).isEqualTo("Desirable: < 200; Borderline: 200-240; High: > 240");
    assertThat(bands.flagFor(new BigDecimal("191.9"))).isEqualTo("unknown");
  }

  // ── Dates ────────────────────────────────────────────────────────────────

  @Test
  void indianDatesAreDayFirst() {
    assertThat(PrintedDate.parse("05/11/2026")).isEqualTo(LocalDate.of(2026, 11, 5));
    assertThat(PrintedDate.parse("12-10-2026")).isEqualTo(LocalDate.of(2026, 10, 12));
    assertThat(PrintedDate.parse("15.09.26")).isEqualTo(LocalDate.of(2026, 9, 15));
    assertThat(PrintedDate.parse("15 Sep 2026")).isEqualTo(LocalDate.of(2026, 9, 15));
    assertThat(PrintedDate.parse("15-Sep-2026 08:30 AM")).isEqualTo(LocalDate.of(2026, 9, 15));
    assertThat(PrintedDate.parse("2026-09-15")).isEqualTo(LocalDate.of(2026, 9, 15));
    assertThat(PrintedDate.parse("13/13/2026")).isNull();
    assertThat(PrintedDate.parse("next week")).isNull();
  }

  // ── Shorthand ────────────────────────────────────────────────────────────

  @Test
  void shorthandOnlyDraftsASchedule() {
    var d = RX.read("1-0-1", null, "After food", "tablet");
    assertThat(d.schedule().path("times").toString()).isEqualTo("[\"08:00\",\"21:00\"]");
    assertThat(d.schedule().path("withFood").asText()).isEqualTo("after");
    assertThat(d.schedule().path("doseUnits").decimalValue()).isEqualByComparingTo("1");

    assertThat(RX.read(null, "BD", "AC", "tablet").schedule().path("times").toString()).isEqualTo("[\"08:00\",\"21:00\"]");
    assertThat(RX.read(null, "BD", "AC", "tablet").schedule().path("withFood").asText()).isEqualTo("before");
    assertThat(RX.read(null, "TDS", null, "capsule").schedule().path("times")).hasSize(3);
    assertThat(RX.read(null, "HS", null, "tablet").schedule().path("times").toString()).isEqualTo("[\"22:00\"]");
    assertThat(RX.read("½-0-½", null, null, "tablet").schedule().path("doseUnits").decimalValue()).isEqualByComparingTo("0.5");
    assertThat(RX.read(null, "SOS", null, "tablet").asNeeded()).isTrue();
    assertThat(RX.read(null, "SOS", null, "tablet").schedule()).isNull();

    // Not one plain thing: the user sets it.
    assertThat(RX.read("1-0-2", null, null, "tablet").schedule()).isNull();
    assertThat(RX.read(null, "OD BD", null, "tablet").schedule()).isNull();
    assertThat(RX.read("1 capsule", "Once a week", "After food", "capsule").schedule()).isNull();
    assertThat(RX.read(null, "12/10/2026", null, "tablet").schedule()).isNull();
  }

  @Test
  void theUsersOwnReminderTimesAreUsed() {
    var mine = new RxShorthand(SHORTHAND, Map.of("morning", "06:30", "night", "22:15"));
    assertThat(mine.read("1-0-1", null, null, "tablet").schedule().path("times").toString())
        .isEqualTo("[\"06:30\",\"22:15\"]");
  }

  @Test
  void aDurationGivesTheLastDay() {
    assertThat(RxShorthand.endDate("30 days", LocalDate.of(2026, 10, 12))).isEqualTo(LocalDate.of(2026, 11, 10));
    assertThat(RxShorthand.endDate("8 weeks", LocalDate.of(2026, 11, 5))).isEqualTo(LocalDate.of(2026, 12, 30));
    assertThat(RxShorthand.endDate("1 month", LocalDate.of(2026, 1, 31))).isEqualTo(LocalDate.of(2026, 2, 27));
    assertThat(RxShorthand.endDate("continue", LocalDate.of(2026, 1, 1))).isNull();
  }

  // ── The patient check ───────────────────────────────────────────────────

  @Test
  void thePrintedAgeAndSexAreComparedWithTheRecord() {
    LocalDate on = LocalDate.of(2026, 9, 15);
    LocalDate mother = LocalDate.of(1964, 3, 2);
    LocalDate father = LocalDate.of(1960, 6, 1);
    assertThat(PatientCheck.check(null, "62", "F", "Amma", mother, "female", on).path("status").asText()).isEqualTo("match");
    var wrong = PatientCheck.check(null, "62", "F", "Appa", father, "male", on);
    assertThat(wrong.path("status").asText()).isEqualTo("mismatch");
    assertThat(wrong.path("reasons")).hasSize(2);
    assertThat(PatientCheck.check(null, null, null, "Amma", mother, "female", on).path("status").asText()).isEqualTo("unknown");
    // A name that shares no word is a note to check, not a mismatch by itself.
    var named = PatientCheck.check("Mrs Lakshmi Rao", "62 Y", "Female", "Amma", mother, "female", on);
    assertThat(named.path("status").asText()).isEqualTo("match");
    assertThat(named.path("notes")).hasSize(1);
    assertThat(PatientCheck.check("Lakshmi Rao", null, null, "Lakshmi", null, null, on).path("notes")).isEmpty();
  }

  @Test
  void theLabsOwnFlagIsKeptAndCriticalOnlyWhenPrinted() {
    assertThat(VitaExtractionService.flag("H")).isEqualTo("high");
    assertThat(VitaExtractionService.flag("Low")).isEqualTo("low");
    assertThat(VitaExtractionService.flag("CRITICAL")).isEqualTo("critical");
    assertThat(VitaExtractionService.flag("")).isNull();
    assertThat(VitaExtractionService.flag("see note")).isNull();
  }
}

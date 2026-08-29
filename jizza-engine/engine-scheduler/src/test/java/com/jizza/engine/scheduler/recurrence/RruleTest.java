package com.jizza.engine.scheduler.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.error.ValidationException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class RruleTest {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private ZonedDateTime at(String iso) { return ZonedDateTime.parse(iso).withZoneSameInstant(IST); }

  private ZonedDateTime next(String rule, String anchor, String after) {
    return Rrule.parse(rule).next(at(anchor), at(after), 0).orElseThrow();
  }

  @Test
  void recognisesRrulesButNotCron() {
    assertThat(Rrule.looksLikeRrule("FREQ=DAILY")).isTrue();
    assertThat(Rrule.looksLikeRrule("0 0 9 * * MON-FRI")).isFalse();
    assertThat(Rrule.looksLikeRrule(null)).isFalse();
  }

  @Test
  void dailyAtAFixedTime() {
    var result = next("FREQ=DAILY;BYHOUR=9;BYMINUTE=0",
        "2026-03-01T00:00:00+05:30", "2026-03-01T10:00:00+05:30");
    assertThat(result.toString()).startsWith("2026-03-02T09:00");
  }

  @Test
  void everyOtherDayKeepsItsPhaseFromTheAnchor() {
    String rule = "FREQ=DAILY;INTERVAL=2;BYHOUR=6;BYMINUTE=30";
    assertThat(next(rule, "2026-03-01T00:00:00+05:30", "2026-03-01T00:00:00+05:30")
        .toLocalDate().toString()).isEqualTo("2026-03-01");
    assertThat(next(rule, "2026-03-01T00:00:00+05:30", "2026-03-01T07:00:00+05:30")
        .toLocalDate().toString()).isEqualTo("2026-03-03");
  }

  @Test
  void weeklyOnNamedDays() {
    String rule = "FREQ=WEEKLY;BYDAY=MO,WE,FR;BYHOUR=8;BYMINUTE=15";
    // 2026-03-02 is a Monday.
    var fromTuesday = next(rule, "2026-03-01T00:00:00+05:30", "2026-03-03T09:00:00+05:30");
    assertThat(fromTuesday.getDayOfWeek().toString()).isEqualTo("WEDNESDAY");
    assertThat(fromTuesday.toLocalTime().toString()).isEqualTo("08:15");
  }

  @Test
  void monthlyOnADayOfMonth() {
    var result = next("FREQ=MONTHLY;BYMONTHDAY=15;BYHOUR=10;BYMINUTE=0",
        "2026-01-01T00:00:00+05:30", "2026-03-16T00:00:00+05:30");
    assertThat(result.toLocalDate().toString()).isEqualTo("2026-04-15");
  }

  @Test
  void hourlyStepsFromTheAnchor() {
    var result = next("FREQ=HOURLY;INTERVAL=6",
        "2026-03-01T00:00:00+05:30", "2026-03-01T07:00:00+05:30");
    assertThat(result.toString()).startsWith("2026-03-01T12:00");
  }

  @Test
  void countStopsTheSeries() {
    var rule = Rrule.parse("FREQ=DAILY;COUNT=3;BYHOUR=9");
    assertThat(rule.next(at("2026-03-01T00:00:00+05:30"), at("2026-03-01T00:00:00+05:30"), 2))
        .isPresent();
    assertThat(rule.next(at("2026-03-01T00:00:00+05:30"), at("2026-03-01T00:00:00+05:30"), 3))
        .isEmpty();
  }

  @Test
  void untilStopsTheSeries() {
    var rule = Rrule.parse("FREQ=DAILY;BYHOUR=9;UNTIL=20260305");
    assertThat(rule.next(at("2026-03-01T00:00:00+05:30"), at("2026-03-04T10:00:00+05:30"), 0))
        .isPresent();
    assertThat(rule.next(at("2026-03-01T00:00:00+05:30"), at("2026-03-06T10:00:00+05:30"), 0))
        .isEmpty();
  }

  @Test
  void keepsLocalWallClockTimeAcrossADstTransition() {
    // London moves to BST at 01:00 UTC on 2026-03-29. A 09:00 local job must stay at 09:00 local,
    // which means the UTC instant shifts by an hour, not the other way round.
    ZoneId london = ZoneId.of("Europe/London");
    var rule = Rrule.parse("FREQ=DAILY;BYHOUR=9;BYMINUTE=0");
    ZonedDateTime anchor = ZonedDateTime.of(2026, 3, 27, 9, 0, 0, 0, london);

    ZonedDateTime beforeTransition = rule.next(anchor, anchor, 0).orElseThrow();
    ZonedDateTime afterTransition = rule.next(anchor,
        ZonedDateTime.of(2026, 3, 29, 0, 0, 0, 0, london), 0).orElseThrow();

    assertThat(beforeTransition.toLocalTime().toString()).isEqualTo("09:00");
    assertThat(afterTransition.toLocalTime().toString()).isEqualTo("09:00");
    assertThat(beforeTransition.getOffset()).isNotEqualTo(afterTransition.getOffset());
  }

  @Test
  void handlesATimeThatDoesNotExistOnADstSpringForward() {
    // 01:30 does not exist in London on 2026-03-29; the zone rules push it forward rather than
    // failing, so the occurrence still happens.
    ZoneId london = ZoneId.of("Europe/London");
    var rule = Rrule.parse("FREQ=DAILY;BYHOUR=1;BYMINUTE=30");
    ZonedDateTime anchor = ZonedDateTime.of(2026, 3, 28, 1, 30, 0, 0, london);

    var occurrence = rule.next(anchor, ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0, london), 0);

    assertThat(occurrence).isPresent();
    assertThat(occurrence.get().toLocalDate().toString()).isEqualTo("2026-03-29");
  }

  @Test
  void rejectsAMalformedRule() {
    assertThatThrownBy(() -> Rrule.parse("INTERVAL=2"))
        .isInstanceOf(ValidationException.class).hasMessageContaining("needs a FREQ");
    assertThatThrownBy(() -> Rrule.parse("FREQ=FORTNIGHTLY"))
        .isInstanceOf(ValidationException.class).hasMessageContaining("Unsupported FREQ");
    assertThatThrownBy(() -> Rrule.parse("FREQ=DAILY;BYSETPOS=1"))
        .isInstanceOf(ValidationException.class).hasMessageContaining("Unsupported recurrence part");
    assertThatThrownBy(() -> Rrule.parse("FREQ=WEEKLY;BYDAY=XX"))
        .isInstanceOf(ValidationException.class).hasMessageContaining("BYDAY");
  }

  @Test
  void acceptsTheRrulePrefix() {
    assertThat(Rrule.parse("RRULE:FREQ=DAILY").freq()).isEqualTo(Rrule.Freq.DAILY);
  }
}

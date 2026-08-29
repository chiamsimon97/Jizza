package com.jizza.scheduler.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.common.enums.CatchUpPolicy;
import com.jizza.common.error.ValidationException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class RecurrenceCalculatorTest {

  private final RecurrenceCalculator calculator = new RecurrenceCalculator();

  @Test
  void handlesCronExpressionsInTheTasksOwnZone() {
    Instant anchor = Instant.parse("2026-03-01T00:00:00Z");
    Instant after = Instant.parse("2026-03-02T10:00:00Z");

    Instant next = calculator
        .nextAfter("0 0 9 * * *", "Asia/Kolkata", anchor, after, 0).orElseThrow();

    assertThat(next.atZone(ZoneId.of("Asia/Kolkata")).toLocalTime().toString()).isEqualTo("09:00");
    assertThat(next).isAfter(after);
  }

  @Test
  void handlesRrulesToo() {
    Instant anchor = Instant.parse("2026-03-01T00:00:00Z");
    Instant next = calculator.nextAfter("FREQ=WEEKLY;BYDAY=MO;BYHOUR=9;BYMINUTE=0",
        "Asia/Kolkata", anchor, anchor, 0).orElseThrow();
    assertThat(next.atZone(ZoneId.of("Asia/Kolkata")).getDayOfWeek().toString()).isEqualTo("MONDAY");
  }

  @Test
  void theSameCronMeansDifferentInstantsInDifferentZones() {
    Instant anchor = Instant.parse("2026-06-01T00:00:00Z");
    Instant ist = calculator.nextAfter("0 0 9 * * *", "Asia/Kolkata", anchor, anchor, 0).orElseThrow();
    Instant utc = calculator.nextAfter("0 0 9 * * *", "UTC", anchor, anchor, 0).orElseThrow();
    assertThat(ist).isNotEqualTo(utc);
  }

  @Test
  void rejectsAnUnusableScheduleWhenItIsSaved() {
    assertThatThrownBy(() -> calculator.validate("not a schedule", "UTC"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> calculator.validate("FREQ=DAILY;UNTIL=20200101", "UTC"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("no future occurrences");
  }

  @Test
  void rejectsAnUnknownTimezone() {
    assertThatThrownBy(() -> calculator.zoneOf("Mars/Olympus"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void runOnceCatchUpFiresImmediatelyThenResumesTheCadence() {
    Instant anchor = Instant.parse("2026-03-01T00:00:00Z");
    Instant missed = Instant.parse("2026-03-02T03:30:00Z");
    Instant now = Instant.parse("2026-03-05T12:00:00Z");

    var settled = calculator.reconcileMissed("0 0 9 * * *", "Asia/Kolkata", anchor, missed, now,
        CatchUpPolicy.RUN_ONCE, 0);

    assertThat(settled).contains(now);
  }

  @Test
  void skipCatchUpJumpsToTheNextFutureOccurrence() {
    Instant anchor = Instant.parse("2026-03-01T00:00:00Z");
    Instant missed = Instant.parse("2026-03-02T03:30:00Z");
    Instant now = Instant.parse("2026-03-05T12:00:00Z");

    var settled = calculator.reconcileMissed("0 0 9 * * *", "Asia/Kolkata", anchor, missed, now,
        CatchUpPolicy.SKIP, 0);

    assertThat(settled).isPresent();
    assertThat(settled.get()).isAfter(now);
  }

  @Test
  void runAllCatchUpWorksThroughTheBacklogOneOccurrenceAtATime() {
    Instant missed = Instant.parse("2026-03-02T03:30:00Z");
    var settled = calculator.reconcileMissed("0 0 9 * * *", "Asia/Kolkata",
        Instant.parse("2026-03-01T00:00:00Z"), missed, Instant.parse("2026-03-05T12:00:00Z"),
        CatchUpPolicy.RUN_ALL, 0);
    assertThat(settled).contains(missed);
  }

  @Test
  void defaultsToTheCharterHomeZone() {
    assertThat(calculator.zoneOf(null)).isEqualTo(ZoneId.of("Asia/Kolkata"));
    assertThat(calculator.zoneOf("  ")).isEqualTo(ZoneId.of("Asia/Kolkata"));
  }
}

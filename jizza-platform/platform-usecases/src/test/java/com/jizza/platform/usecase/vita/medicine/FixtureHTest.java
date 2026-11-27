package com.jizza.platform.usecase.vita.medicine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.scheduler.RecurrenceDays;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Fixture H of spec section 10: time zones, read with each zone's own rules. */
class FixtureHTest {

  static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
  static final ZoneId LONDON = ZoneId.of("Europe/London");
  static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

  @Test
  void mothers2000DoseOn10DecemberIsDueAt1430Utc() {
    var plan = new DoseExpander.Plan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        List.of(LocalTime.of(20, 0)), "FREQ=DAILY", LocalDate.of(2026, 12, 1), null);
    var due = DoseExpander.dueTimes(plan, INDIA, Instant.parse("2026-12-10T00:00:00Z"),
        Instant.parse("2026-12-11T00:00:00Z"), RecurrenceDays.DAILY);
    assertThat(due).containsExactly(Instant.parse("2026-12-10T14:30:00Z"));
    assertThat(DoseExpander.clock(due.get(0), INDIA)).isEqualTo("8:00 PM");
    assertThat(DoseExpander.clock(due.get(0), LONDON)).isEqualTo("2:30 PM");
  }

  @Test
  void inJuneLondonIsOnSummerTimeSoTheSameDoseShows330() {
    Instant due = DoseExpander.at(LocalDate.of(2027, 6, 10), LocalTime.of(20, 0), INDIA);
    assertThat(due).isEqualTo(Instant.parse("2027-06-10T14:30:00Z"));
    assertThat(DoseExpander.clock(due, INDIA)).isEqualTo("8:00 PM");
    assertThat(DoseExpander.clock(due, LONDON)).isEqualTo("3:30 PM");
  }

  @Test
  void a0230DoseOnTheDayNewYorkSkipsAnHourIsDueAt0330() {
    var plan = new DoseExpander.Plan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        List.of(LocalTime.of(2, 30)), "FREQ=DAILY", LocalDate.of(2027, 3, 1), null);
    var due = DoseExpander.dueTimes(plan, NEW_YORK, LocalDate.of(2027, 3, 14).atStartOfDay(NEW_YORK).toInstant(),
        LocalDate.of(2027, 3, 15).atStartOfDay(NEW_YORK).toInstant(), RecurrenceDays.DAILY);
    assertThat(due).hasSize(1);
    assertThat(due.get(0).atZone(NEW_YORK).toLocalTime()).isEqualTo(LocalTime.of(3, 30));
    assertThat(due.get(0)).isEqualTo(Instant.parse("2027-03-14T07:30:00Z"));
  }
}

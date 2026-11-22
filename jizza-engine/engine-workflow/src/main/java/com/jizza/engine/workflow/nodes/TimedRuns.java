package com.jizza.engine.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Runs that keep time (plan 09): "Wait until" holds the run until an exact moment, such as when a
 * booking window opens; "Wait until the page answers" with {@code poll} looks again every so often until
 * what it waits for shows (a slot, a price), the time runs out, or it has tried enough.
 */
final class TimedRuns {

  private TimedRuns() {}

  /** The browser gets a Wait until this long before the moment and waits the rest itself. */
  static final Duration SEND_BEFORE = Duration.ofMinutes(2);
  /** Room past the moment for the browser to answer. */
  static final Duration SLACK = Duration.ofSeconds(60);
  /** A moment this far gone is a mistake, not a little lateness. */
  static final Duration TOO_LATE = Duration.ofMinutes(1);
  /** Sites are asked at most this often. */
  static final int MIN_EVERY_SEC = 15;
  static final int MAX_TRIES = 500;

  /**
   * The moment a step names: an ISO date and time with its offset ("2026-12-01T10:00:00+05:30"),
   * or a local one ("2026-12-01T10:00") read in {@code timeZone} (an id such as "Asia/Kolkata").
   */
  static Instant instant(JsonNode at, String timeZone) {
    String text = at == null || at.isNull() ? "" : at.asText("").strip();
    if (text.isEmpty()) throw new IllegalArgumentException("Say when to go on");
    try {
      return OffsetDateTime.parse(text).toInstant();
    } catch (DateTimeParseException e) {
      // A local time, in the zone given.
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException e) {
      // As above.
    }
    LocalDateTime local;
    try {
      local = LocalDateTime.parse(text.length() == 16 ? text + ":00" : text);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("'" + text + "' is not a date and time");
    }
    if (timeZone.isBlank()) {
      throw new IllegalArgumentException("Say which time zone " + text + " is in");
    }
    try {
      return local.atZone(ZoneId.of(timeZone)).toInstant();
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("'" + timeZone + "' is not a time zone");
    }
  }

  /** "10:00:00 UTC, 1 Dec 2026", for a message. */
  static String describe(Instant at) {
    return DateTimeFormatter.ofPattern("HH:mm:ss 'UTC', d MMM yyyy", Locale.ENGLISH)
        .format(ZonedDateTime.ofInstant(at, ZoneId.of("UTC")));
  }

  /** Whether a step looks again when what it waits for does not show. */
  static boolean polls(JsonNode config) {
    return config != null && config.path("poll").isObject()
        && config.path("poll").path("everySec").asInt(0) > 0;
  }

  /**
   * When to look again after {@code tries} looks that found nothing, or null when it should stop:
   * past {@code poll.until}, after {@code poll.forMinutes} from the first look, or after
   * {@code poll.maxTries} looks.
   */
  static Instant nextLook(JsonNode config, int tries, String since, Instant now) {
    JsonNode poll = config.path("poll");
    int every = Math.max(MIN_EVERY_SEC, poll.path("everySec").asInt(MIN_EVERY_SEC));
    int maxTries = Math.min(MAX_TRIES, poll.path("maxTries").asInt(MAX_TRIES));
    if (tries + 1 >= maxTries) return null;
    Instant next = now.plusSeconds(every);
    Instant stop = null;
    if (poll.hasNonNull("until")) {
      try {
        stop = instant(poll.get("until"), poll.path("timeZone").asText(""));
      } catch (IllegalArgumentException e) {
        return null;
      }
    } else if (poll.path("forMinutes").asInt(0) > 0) {
      Instant first = since == null ? now : Instant.parse(since);
      stop = first.plus(Duration.ofMinutes(poll.get("forMinutes").asInt()));
    }
    return stop != null && next.isAfter(stop) ? null : next;
  }

  /** Why polling stopped. */
  static String gaveUp(JsonNode config, int tries) {
    return "What this step waits for did not show after " + tries + (tries == 1 ? " look" : " looks");
  }
}

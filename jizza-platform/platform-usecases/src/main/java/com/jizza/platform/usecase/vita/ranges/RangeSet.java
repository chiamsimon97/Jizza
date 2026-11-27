package com.jizza.platform.usecase.vita.ranges;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * The range that applies to one reading (spec 2.4), with where it came from. For blood pressure
 * {@code value} bounds the systolic and {@code value2} the diastolic.
 *
 * @param source {@code doctor} (a target), {@code own} (the person's own row) or {@code vita_default}
 * @param configKey for a Vita default, the config key it came from
 */
public record RangeSet(Range value, Range value2, String source, UUID doctorId, String doctorName,
                       LocalDate setOn, boolean needsVerification, String configKey) {

  public static final String DOCTOR = "doctor";
  public static final String OWN = "own";
  public static final String VITA_DEFAULT = "vita_default";
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

  public static RangeSet doctor(Range v, Range v2, UUID doctorId, String doctorName, LocalDate setOn) {
    return new RangeSet(v, v2, DOCTOR, doctorId, doctorName, setOn, false, null);
  }

  public static RangeSet own(Range v, Range v2) {
    return new RangeSet(v, v2, OWN, null, null, null, false, null);
  }

  public static RangeSet vitaDefault(Range v, Range v2, String key, boolean needsVerification) {
    return new RangeSet(v, v2, VITA_DEFAULT, null, null, null, needsVerification, key);
  }

  /** "below 140/90" for a pair, otherwise the one range. */
  public String text() {
    if (value != null && value2 != null && value.low() == null && value2.low() == null
        && value.highInclusive() == value2.highInclusive()) {
      String head = value.highInclusive() ? "" : "below ";
      String tail = value.highInclusive() ? " or lower" : "";
      return head + Range.plain(value.high()) + "/" + Range.plain(value2.high()) + tail;
    }
    if (value != null && value2 != null) return value.text() + " / " + value2.text();
    return value != null ? value.text() : value2.text();
  }

  /** What the screen writes beside a status: who set the range. */
  public String label() {
    return switch (source) {
      case DOCTOR -> "from " + doctorTitle(doctorName) + (setOn == null ? "" : ", set on " + DAY.format(setOn));
      case OWN -> "Your own range";
      default -> needsVerification ? "Vita default, needs verification" : "Vita default";
    };
  }

  /** "Dr S Iyer" for a doctor named "S Iyer" or "Dr S Iyer". */
  public static String doctorTitle(String name) {
    if (name == null || name.isBlank()) return "your doctor";
    String n = name.strip();
    return n.regionMatches(true, 0, "dr ", 0, 3) || n.regionMatches(true, 0, "dr.", 0, 3) ? n : "Dr " + n;
  }
}

package com.jizza.platform.usecase.vita.documents;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import java.time.LocalDate;
import java.time.Period;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The patient check of spec 5.3: the name, age and sex printed on a document against the person
 * it is being filed for. A disagreement is shown at the top of the review; Vita never files the
 * document to someone else by itself, and the user confirms a mismatch knowingly.
 *
 * <p>The status is {@code mismatch} when the printed age or sex disagrees, {@code match} when at
 * least one of them was compared and agrees, and {@code unknown} when nothing could be compared. A
 * printed name sharing no word with the record's name is a note, not a mismatch, since a record may
 * be called "Amma" while the report prints her full name.
 */
public final class PatientCheck {

  private static final Pattern YEARS = Pattern.compile("(\\d{1,3})(?:\\.\\d+)?\\s*(?:y|yr|yrs|year|years)?\\b");
  private static final Set<String> TITLES = Set.of("mr", "mrs", "ms", "miss", "dr", "shri", "smt", "sri",
      "master", "baby", "kumari", "late");

  private PatientCheck() {}

  /**
   * @param printedName the name as printed, or null
   * @param printedAge  the age as printed ("62", "62 Y", "62 Yrs 3 Months"), or null
   * @param printedSex  the sex as printed ("F", "Female", "M"), or null
   * @param name        the record's name
   * @param dob         the record's date of birth, or null
   * @param sexAtBirth  the record's sex at birth (female, male, intersex, unknown), or null
   * @param on          the document's date, or today when it has none
   */
  public static ObjectNode check(String printedName, String printedAge, String printedSex, String name,
                                 LocalDate dob, String sexAtBirth, LocalDate on) {
    ObjectNode out = Json.obj();
    ArrayNode reasons = out.putArray("reasons");
    ArrayNode notes = out.putArray("notes");
    boolean compared = false;
    boolean mismatch = false;

    Integer age = age(printedAge);
    if (age != null && dob != null && on != null && !dob.isAfter(on)) {
      compared = true;
      int actual = Period.between(dob, on).getYears();
      // A report can print the age at booking or round it; a year either way agrees.
      if (Math.abs(actual - age) > 1) {
        mismatch = true;
        reasons.add("The document says age " + age + "; this record's age on that date is " + actual + ".");
      }
    }
    String sex = sex(printedSex);
    if (sex != null && sexAtBirth != null && (sexAtBirth.equals("female") || sexAtBirth.equals("male"))) {
      compared = true;
      if (!sex.equals(sexAtBirth)) {
        mismatch = true;
        reasons.add("The document says " + sex + "; this record says " + sexAtBirth + ".");
      }
    }
    if (printedName != null && !printedName.isBlank() && name != null && !name.isBlank()
        && !sharesAWord(printedName, name)) {
      notes.add("The name on the document is not the name of this record. Check it is the right person.");
    }
    out.put("status", mismatch ? "mismatch" : compared ? "match" : "unknown");
    return out;
  }

  static Integer age(String printed) {
    if (printed == null) return null;
    Matcher m = YEARS.matcher(printed.toLowerCase(Locale.ROOT));
    if (!m.find()) return null;
    int a = Integer.parseInt(m.group(1));
    return a <= 130 ? a : null;
  }

  static String sex(String printed) {
    if (printed == null) return null;
    String s = printed.strip().toLowerCase(Locale.ROOT);
    if (s.equals("f") || s.startsWith("female") || s.equals("woman")) return "female";
    if (s.equals("m") || s.startsWith("male") || s.equals("man")) return "male";
    return null;
  }

  private static boolean sharesAWord(String a, String b) {
    Set<String> left = words(a);
    Set<String> right = words(b);
    left.retainAll(right);
    return !left.isEmpty();
  }

  private static Set<String> words(String s) {
    return Arrays.stream(s.toLowerCase(Locale.ROOT).split("[^\\p{L}]+"))
        .filter(w -> w.length() > 1 && !TITLES.contains(w))
        .collect(Collectors.toCollection(java.util.HashSet::new));
  }
}

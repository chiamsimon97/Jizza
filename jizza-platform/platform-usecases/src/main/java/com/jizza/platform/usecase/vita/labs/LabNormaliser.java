package com.jizza.platform.usecase.vita.labs;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.vita.VitaStore.LabTestRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The lab normaliser (spec 5.4), a pure function over the reference table: a test name as printed
 * to a canonical test, a unit as printed to the test's canonical unit, and the value and printed
 * range converted together. A conversion is used only when the reference table carries one with a
 * source; it reads "needs verification" until its verified date is set. Every other unit stays as
 * printed and is not converted. Converted figures are kept unrounded; {@link #shown} rounds half up
 * for display only. An unknown test is kept with no code, as printed.
 */
public final class LabNormaliser {

  /** How the value relates to the canonical unit. */
  public static final String CANONICAL = "canonical";
  public static final String CONVERTED = "converted";
  public static final String NOT_CONVERTED = "not_converted";
  public static final String UNKNOWN_TEST = "unknown_test";

  /** Words that qualify a printed name without changing the test ("LDL Cholesterol, Calculated"). */
  private static final Set<String> QUALIFIERS = Set.of("serum", "plasma", "blood", "calculated", "calc",
      "direct", "estimated", "ultrasensitive", "ultra", "sensitive", "test", "level", "levels", "s", "whole");

  /** One conversion into the canonical unit: canonical = printed × multiply + add. */
  public record Conversion(String from, BigDecimal multiply, BigDecimal add, Integer decimals, String source,
                           LocalDate verifiedOn) {

    public BigDecimal apply(BigDecimal v) {
      return v == null ? null : v.multiply(multiply).add(add == null ? BigDecimal.ZERO : add);
    }

    /** The canonical value back in the {@code from} unit, unrounded to ten places. */
    public BigDecimal invert(BigDecimal v) {
      if (v == null) return null;
      return v.subtract(add == null ? BigDecimal.ZERO : add).divide(multiply, 10, RoundingMode.HALF_UP);
    }

    public boolean needsVerification() {
      return verifiedOn == null;
    }
  }

  /** A canonical test. */
  public record Test(String code, String name, String canonicalUnit, Set<String> sameUnits, int decimals,
                     List<Conversion> conversions, String group, int sortOrder) {}

  /**
   * One printed row, normalised. {@code valueCanonical} and the canonical bounds are null unless the
   * printed unit is the canonical one or a conversion applies.
   */
  public record Normalised(Test test, String unitStatus, String unitCanonical, BigDecimal valueCanonical,
                           BigDecimal refLowCanonical, BigDecimal refHighCanonical, Conversion conversion) {

    public String code() {
      return test == null ? null : test.code();
    }

    public int decimals() {
      return test == null ? 2 : test.decimals();
    }
  }

  private final Map<String, Test> byCode = new LinkedHashMap<>();
  private final Map<String, Test> byName = new LinkedHashMap<>();

  public LabNormaliser(List<LabTestRow> rows) {
    for (LabTestRow r : rows) {
      Set<String> units = new LinkedHashSet<>();
      if (r.canonicalUnit() != null) units.add(unitKey(r.canonicalUnit()));
      r.unitAliases().forEach(a -> units.add(unitKey(a)));
      List<Conversion> conversions = new ArrayList<>();
      for (JsonNode c : r.conversions() == null ? List.<JsonNode>of() : iterable(r.conversions())) {
        // A conversion with no source is not a conversion: it is never used.
        if (!c.hasNonNull("from") || !c.hasNonNull("multiply") || c.path("source").asText("").isBlank()) continue;
        conversions.add(new Conversion(c.get("from").asText(), c.get("multiply").decimalValue(),
            c.hasNonNull("add") ? c.get("add").decimalValue() : null,
            c.hasNonNull("decimals") ? c.get("decimals").asInt() : null, c.get("source").asText(),
            c.hasNonNull("verifiedOn") ? LocalDate.parse(c.get("verifiedOn").asText()) : null));
      }
      Test t = new Test(r.code(), r.name(), r.canonicalUnit(), Set.copyOf(units), r.displayDecimals(),
          List.copyOf(conversions), r.group(), r.sortOrder());
      byCode.put(t.code(), t);
      byName.putIfAbsent(key(r.name()), t);
      byName.putIfAbsent(key(r.code().replace('_', ' ')), t);
      for (String s : r.synonyms()) byName.putIfAbsent(key(s), t);
    }
  }

  public Optional<Test> byCode(String code) {
    return Optional.ofNullable(code == null ? null : byCode.get(code));
  }

  public List<Test> tests() {
    return List.copyOf(byCode.values());
  }

  /**
   * The canonical test a printed name stands for. Tries the whole name, then the name without
   * qualifiers ("Serum", "Calculated"), then each part around brackets and dashes
   * ("HbA1C- Glycated Haemoglobin", "Glucose Fasting (FBS)"). Empty when none matches exactly.
   */
  public Optional<Test> test(String printed) {
    if (printed == null || printed.isBlank()) return Optional.empty();
    for (String candidate : candidates(printed)) {
      Test t = byName.get(candidate);
      if (t != null) return Optional.of(t);
    }
    return Optional.empty();
  }

  static List<String> candidates(String printed) {
    List<String> out = new ArrayList<>();
    String whole = key(printed);
    out.add(whole);
    out.add(stripQualifiers(whole));
    List<String> parts = new ArrayList<>();
    String outside = printed.replaceAll("\\([^)]*\\)", " ");
    var inside = java.util.regex.Pattern.compile("\\(([^)]*)\\)").matcher(printed);
    parts.add(outside);
    while (inside.find()) parts.add(inside.group(1));
    for (String p : List.copyOf(parts)) {
      for (String bit : p.split("\\s[-–]\\s?|[-–]\\s|\\s?/\\s|,")) parts.add(bit);
    }
    for (String p : parts) {
      String k = key(p);
      if (k.isEmpty()) continue;
      out.add(k);
      out.add(stripQualifiers(k));
    }
    return out.stream().filter(s -> !s.isEmpty()).distinct().toList();
  }

  /** Lower case, letters and digits only, single spaces. */
  static String key(String s) {
    return s.toLowerCase(Locale.ROOT).replace('µ', 'u').replace('μ', 'u')
        .replaceAll("[^a-z0-9]+", " ").strip();
  }

  private static String stripQualifiers(String key) {
    List<String> words = new ArrayList<>(List.of(key.split(" ")));
    words.removeIf(QUALIFIERS::contains);
    return String.join(" ", words);
  }

  /** A unit's spelling reduced so "mg/dl", "MG/DL" and "mg / dL" are one, and µ, μ and u are one. */
  public static String unitKey(String unit) {
    if (unit == null) return "";
    return unit.toLowerCase(Locale.ROOT).replace('µ', 'u').replace('μ', 'u').replace("²", "2")
        .replace("^2", "2").replaceAll("\\s+", "");
  }

  /**
   * Normalises one printed row. The value and the printed range move together: both convert, or
   * neither does.
   */
  public Normalised normalise(String printedName, String unit, BigDecimal value, BigDecimal refLow,
                              BigDecimal refHigh) {
    Test t = test(printedName).orElse(null);
    if (t == null) return new Normalised(null, UNKNOWN_TEST, null, null, null, null, null);
    String u = unitKey(unit);
    if (t.canonicalUnit() != null && t.sameUnits().contains(u)) {
      return new Normalised(t, CANONICAL, t.canonicalUnit(), value, refLow, refHigh, null);
    }
    for (Conversion c : t.conversions()) {
      if (unitKey(c.from()).equals(u)) {
        return new Normalised(t, CONVERTED, t.canonicalUnit(), c.apply(value), c.apply(refLow), c.apply(refHigh), c);
      }
    }
    return new Normalised(t, NOT_CONVERTED, null, null, null, null, null);
  }

  /** A figure rounded half up to {@code decimals} places, for display only. */
  public static BigDecimal shown(BigDecimal v, int decimals) {
    return v == null ? null : v.setScale(decimals, RoundingMode.HALF_UP);
  }

  /** "<101", "4000–11000", "≥40": a range in canonical figures, rounded for display. */
  public static String rangeShown(BigDecimal low, boolean lowInclusive, BigDecimal high, boolean highInclusive,
                                  int decimals) {
    if (low == null && high == null) return null;
    String lo = low == null ? null : plain(shown(low, decimals));
    String hi = high == null ? null : plain(shown(high, decimals));
    if (lo != null && hi != null) return lo + "–" + hi;
    if (hi != null) return (highInclusive ? "≤" : "<") + hi;
    return (lowInclusive ? "≥" : ">") + lo;
  }

  static String plain(BigDecimal d) {
    return d.toPlainString();
  }

  private static Iterable<JsonNode> iterable(JsonNode array) {
    List<JsonNode> out = new ArrayList<>();
    if (array.isArray()) array.forEach(out::add);
    return out;
  }
}

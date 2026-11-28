package com.jizza.platform.usecase.vita.documents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prescription shorthand (spec 5.3): OD, BD, TDS, HS, SOS, AC, PC and patterns such as
 * {@code 1-0-1}, read through {@code clinical.rx_shorthand} only to DRAFT a schedule the user
 * confirms. The clock times come from the user's reminder times ({@code reminder.slot_times}).
 * When the shorthand does not say one plain thing (two codes that disagree, different amounts at
 * different times, "once a week"), no schedule is drafted and the user sets it.
 */
public final class RxShorthand {

  /**
   * A drafted schedule, or why there is none.
   *
   * @param schedule the body {@code schedule} of a medicine form, or null
   * @param asNeeded true for SOS and the like
   * @param reading  how the shorthand was read, for the review ("1-0-1: morning and night")
   */
  public record Draft(ObjectNode schedule, boolean asNeeded, String reading) {}

  private static final String AMOUNT = "(\\d(?:\\.\\d{1,2})?|½|1/2|¼|1/4|¾|3/4)";
  private static final Pattern PATTERN = Pattern.compile(
      "(?<![\\d/])" + AMOUNT + "\\s*-\\s*" + AMOUNT + "\\s*-\\s*" + AMOUNT + "(?:\\s*-\\s*" + AMOUNT + ")?(?![\\d/])");
  private static final Pattern DURATION = Pattern.compile("(\\d{1,3})\\s*(day|days|d|week|weeks|wk|wks|month|months|mo)\\b");

  private final JsonNode codes;
  private final List<String> patternSlots;
  private final Map<String, String> slotTimes;

  /**
   * @param shorthand the value of {@code clinical.rx_shorthand}
   * @param slotTimes slot name to "HH:mm", from {@code reminder.slot_times}
   */
  public RxShorthand(JsonNode shorthand, Map<String, String> slotTimes) {
    this.codes = shorthand == null ? Json.obj() : shorthand.path("codes");
    List<String> slots = new ArrayList<>();
    if (shorthand != null) shorthand.path("pattern").path("slots").forEach(s -> slots.add(s.asText()));
    this.patternSlots = slots;
    this.slotTimes = slotTimes;
  }

  /** Drafts a schedule from what the prescription printed for one medicine. */
  public Draft read(String dose, String frequency, String timing, String form) {
    String all = String.join(" ", nz(dose), nz(frequency), nz(timing));
    String upper = all.toUpperCase(Locale.ROOT);
    String withFood = withFood(upper);
    String unit = unitFor(form);

    Matcher m = PATTERN.matcher(all);
    if (m.find() && !patternSlots.isEmpty()) {
      List<BigDecimal> amounts = new ArrayList<>();
      for (int g = 1; g <= 4; g++) if (m.group(g) != null) amounts.add(amount(m.group(g)));
      if (amounts.size() > patternSlots.size()) {
        return new Draft(null, false, m.group() + ": more times than Vita's slots; set the times yourself");
      }
      List<String> slots = new ArrayList<>();
      BigDecimal each = null;
      boolean differ = false;
      for (int i = 0; i < amounts.size(); i++) {
        BigDecimal a = amounts.get(i);
        if (a.signum() == 0) continue;
        slots.add(patternSlots.get(i));
        if (each == null) each = a;
        else if (each.compareTo(a) != 0) differ = true;
      }
      if (slots.isEmpty()) return new Draft(null, false, m.group() + ": no dose; set it yourself");
      if (differ) return new Draft(null, false, m.group() + ": different amounts at different times; set it yourself");
      return draft(slots, each, unit, withFood, m.group() + ": " + String.join(" and ", slots));
    }

    Set<String> slotCodes = new LinkedHashSet<>();
    boolean asNeeded = false;
    Iterator<Map.Entry<String, JsonNode>> it = codes.fields();
    while (it.hasNext()) {
      var e = it.next();
      if (!Pattern.compile("(?<![A-Z])" + Pattern.quote(e.getKey()) + "(?![A-Z])").matcher(upper).find()) continue;
      if (e.getValue().path("asNeeded").asBoolean(false)) asNeeded = true;
      if (e.getValue().has("slots")) slotCodes.add(e.getKey());
    }
    if (asNeeded) return new Draft(null, true, "only when needed");
    if (slotCodes.size() == 1) {
      String code = slotCodes.iterator().next();
      List<String> slots = new ArrayList<>();
      codes.path(code).path("slots").forEach(s -> slots.add(s.asText()));
      return draft(slots, BigDecimal.ONE, unit, withFood, code + ": " + codes.path(code).path("meaning").asText());
    }
    if (slotCodes.size() > 1) return new Draft(null, false, String.join(" and ", slotCodes) + " disagree; set it yourself");
    return new Draft(null, false, "no shorthand Vita reads; set the times yourself");
  }

  /** The last day of a printed duration such as "30 days" or "8 weeks", counting the first day. */
  public static LocalDate endDate(String duration, LocalDate start) {
    if (duration == null || start == null) return null;
    Matcher m = DURATION.matcher(duration.toLowerCase(Locale.ROOT));
    if (!m.find()) return null;
    int n = Integer.parseInt(m.group(1));
    if (n <= 0) return null;
    String u = m.group(2);
    LocalDate after = u.startsWith("d") ? start.plusDays(n)
        : u.startsWith("w") ? start.plusWeeks(n) : start.plusMonths(n);
    return after.minusDays(1);
  }

  private Draft draft(List<String> slots, BigDecimal each, String unit, String withFood, String reading) {
    List<String> times = new ArrayList<>();
    for (String slot : slots) {
      String t = slotTimes.get(slot);
      if (t == null) return new Draft(null, false, reading + "; no reminder time set for " + slot);
      if (!times.contains(t)) times.add(t);
    }
    times.sort(String::compareTo);
    ObjectNode s = Json.obj();
    var arr = s.putArray("times");
    times.forEach(arr::add);
    s.put("recurrence", "FREQ=DAILY").put("doseUnits", each).put("doseUnit", unit).put("withFood", withFood);
    return new Draft(s, false, reading);
  }

  private String withFood(String upper) {
    if (has(codes, "AC") && word(upper, "AC") || upper.matches("(?s).*\\b(BEFORE (FOOD|MEALS?|BREAKFAST|LUNCH|DINNER)|EMPTY STOMACH)\\b.*")) {
      return codes.path("AC").path("withFood").asText("before");
    }
    if (has(codes, "PC") && word(upper, "PC") || upper.matches("(?s).*\\bAFTER (FOOD|MEALS?|BREAKFAST|LUNCH|DINNER)\\b.*")) {
      return codes.path("PC").path("withFood").asText("after");
    }
    if (upper.matches("(?s).*\\bWITH (FOOD|MEALS?)\\b.*")) return "with";
    return "any";
  }

  private static boolean has(JsonNode codes, String key) {
    return codes.has(key);
  }

  private static boolean word(String upper, String w) {
    return Pattern.compile("(?<![A-Z])" + w + "(?![A-Z])").matcher(upper).find();
  }

  private static String unitFor(String form) {
    if (form == null) return "tablet";
    return switch (form) {
      case "tablet", "capsule" -> "tablet";
      case "inhaler" -> "puffs";
      case "drops" -> "drops";
      default -> "other";
    };
  }

  private static BigDecimal amount(String s) {
    return switch (s) {
      case "½", "1/2" -> new BigDecimal("0.5");
      case "¼", "1/4" -> new BigDecimal("0.25");
      case "¾", "3/4" -> new BigDecimal("0.75");
      default -> new BigDecimal(s);
    };
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}

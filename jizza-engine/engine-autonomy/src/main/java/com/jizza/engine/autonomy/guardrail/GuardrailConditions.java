package com.jizza.engine.autonomy.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.JexlFeatures;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.introspection.JexlPermissions;
import org.springframework.stereotype.Component;

/**
 * The conditions a looser guardrail carries, checked by code against the real arguments of the
 * call, never by a model deciding that a condition "seems met".
 *
 * <p>A rule's {@code limits} may hold:
 * <ul>
 *   <li>{@code condition}: an expression over {@code args} that must be exactly {@code true},
 *       such as {@code args.amount <= 500 && args.symbol =~ ['VOO','VTI']};</li>
 *   <li>{@code maxValue} with {@code field}: a cap on one numeric argument;</li>
 *   <li>{@code activeHours}: {@code {"days":["MON","FRI"],"from":"09:30","to":"16:00",
 *       "zone":"America/New_York"}};</li>
 *   <li>{@code budget} and {@code maxCalls}: running totals, checked by {@link GuardrailLedger}.</li>
 * </ul>
 * Anything that cannot be evaluated counts as not met, so a broken condition makes a rule
 * stricter, never looser.
 */
@Component
public class GuardrailConditions {

  private static final long TIMEOUT_MILLIS = 500;
  private static final int MAX_EXPRESSION_LENGTH = 500;

  private final JexlEngine engine;
  private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
    Thread thread = new Thread(runnable, "jizza-guardrail");
    thread.setDaemon(true);
    return thread;
  });

  public GuardrailConditions() {
    JexlFeatures features = new JexlFeatures()
        .loops(false)
        .newInstance(false)
        .methodCall(true)
        .sideEffect(false)
        .sideEffectGlobal(false)
        .lambda(false)
        .annotation(false)
        .pragma(false)
        .namespacePragma(false)
        .importPragma(false)
        .register(false);
    this.engine = new JexlBuilder()
        .features(features)
        .permissions(JexlPermissions.RESTRICTED)
        .strict(true)
        .silent(false)
        .safe(false)
        .create();
  }

  /** True when a rule's limits hold anything that can make it not apply to a given call. */
  public static boolean hasConditions(JsonNode limits) {
    return hasCondition(limits) || hasBudget(limits) || has(limits, "activeHours");
  }

  /** A condition or cap on the call's own arguments. */
  public static boolean hasCondition(JsonNode limits) {
    return has(limits, "condition") || has(limits, "maxValue");
  }

  /** A running total: an amount budget or a call count. */
  public static boolean hasBudget(JsonNode limits) {
    return has(limits, "budget") || has(limits, "maxCalls");
  }

  private static boolean has(JsonNode limits, String key) {
    return limits != null && limits.isObject() && limits.hasNonNull(key);
  }

  /**
   * Checks everything except running totals.
   *
   * @return why the call does not meet the rule, or empty when it does
   */
  public Optional<String> check(JsonNode limits, JsonNode args, Instant now) {
    if (limits == null || !limits.isObject()) return Optional.empty();
    JsonNode safeArgs = args == null || args.isNull() ? Json.obj() : args;

    Optional<String> cap = overCap(limits, safeArgs);
    if (cap.isPresent()) return cap;

    if (limits.hasNonNull("condition")) {
      String expression = limits.get("condition").asText();
      if (!conditionHolds(expression, safeArgs)) {
        String label = limits.path("conditionLabel").asText(expression);
        return Optional.of("This doesn't meet your condition (" + label + "), so it needs your "
            + "approval");
      }
    }

    if (limits.hasNonNull("activeHours") && !withinHours(limits.get("activeHours"), now)) {
      return Optional.of("This is outside the hours your rule allows, so it needs your approval");
    }
    return Optional.empty();
  }

  /** Throws when a rule's limits could never be evaluated, so a broken rule is refused on save. */
  public void validate(JsonNode limits) {
    if (limits == null || limits.isNull()) return;
    if (!limits.isObject()) throw new ValidationException("A guardrail's limits must be an object");
    if (limits.hasNonNull("condition")) {
      String expression = limits.get("condition").asText("");
      if (expression.isBlank() || expression.length() > MAX_EXPRESSION_LENGTH) {
        throw new ValidationException("A condition must be between 1 and "
            + MAX_EXPRESSION_LENGTH + " characters");
      }
      try {
        engine.createExpression(expression);
      } catch (JexlException e) {
        throw new ValidationException("That condition is not valid: " + rootMessage(e));
      }
    }
    if (limits.hasNonNull("maxValue") && !limits.get("maxValue").isNumber()) {
      throw new ValidationException("maxValue must be a number");
    }
    if (limits.hasNonNull("budget")) {
      JsonNode budget = limits.get("budget");
      if (!budget.path("max").isNumber() || budget.path("max").asDouble() < 0) {
        throw new ValidationException("A budget needs a max that is a number of zero or more");
      }
      period(budget.path("per").asText(null));
    }
    if (limits.hasNonNull("maxCalls")) {
      JsonNode calls = limits.get("maxCalls");
      if (!calls.path("count").canConvertToInt() || calls.path("count").asInt() < 0) {
        throw new ValidationException("maxCalls needs a count of zero or more");
      }
      period(calls.path("per").asText(null));
    }
    if (limits.hasNonNull("activeHours")) {
      JsonNode hours = limits.get("activeHours");
      try {
        LocalTime.parse(hours.path("from").asText("00:00"));
        LocalTime.parse(hours.path("to").asText("23:59"));
        zone(hours);
        days(hours);
      } catch (DateTimeParseException | IllegalArgumentException e) {
        throw new ValidationException("activeHours needs from/to as HH:mm, a valid zone and "
            + "day names such as MON");
      }
    }
  }

  /** "P7D" for a week; anything unreadable is refused rather than guessed. */
  public static Duration period(String text) {
    if (text == null || text.isBlank()) return Duration.ofDays(1);
    try {
      Duration duration = Duration.parse(text);
      if (duration.isNegative() || duration.isZero()) throw new ValidationException(
          "A budget period must be positive");
      return duration;
    } catch (DateTimeParseException e) {
      throw new ValidationException("A budget period must look like P1D or P7D");
    }
  }

  /** The number at a dotted path in the arguments, or null when there is none. */
  public static BigDecimal number(JsonNode args, String field) {
    if (args == null) return null;
    JsonNode value = args;
    for (String segment : field.split("\\.")) {
      value = value.path(segment);
    }
    if (value.isNumber()) return value.decimalValue();
    if (value.isTextual()) {
      try {
        return new BigDecimal(value.asText().trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private Optional<String> overCap(JsonNode limits, JsonNode args) {
    if (!limits.hasNonNull("maxValue")) return Optional.empty();
    String field = limits.path("field").asText("amount");
    BigDecimal value = number(args, field);
    // A cap on a field the call does not carry is not breached; a condition is the way to require
    // the field.
    if (value == null) return Optional.empty();
    BigDecimal max = limits.get("maxValue").decimalValue();
    if (value.compareTo(max) > 0) {
      return Optional.of("This exceeds the configured limit of " + max.stripTrailingZeros()
          .toPlainString() + " for " + field + ", so it needs your approval");
    }
    return Optional.empty();
  }

  boolean conditionHolds(String expression, JsonNode args) {
    if (expression == null || expression.isBlank()) return false;
    JexlExpression compiled;
    try {
      compiled = engine.createExpression(expression);
    } catch (JexlException e) {
      return false;
    }
    MapContext context = new MapContext();
    context.set("args", Json.mapper().convertValue(args, Map.class));
    Future<Object> future = executor.submit(() -> compiled.evaluate(context));
    try {
      return Boolean.TRUE.equals(future.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
    } catch (TimeoutException e) {
      future.cancel(true);
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (java.util.concurrent.ExecutionException e) {
      return false;
    }
  }

  static boolean withinHours(JsonNode hours, Instant now) {
    try {
      ZonedDateTime at = now.atZone(zone(hours));
      Set<DayOfWeek> days = days(hours);
      LocalTime from = LocalTime.parse(hours.path("from").asText("00:00"));
      LocalTime to = LocalTime.parse(hours.path("to").asText("23:59"));
      LocalTime time = at.toLocalTime();
      // A window that wraps past midnight belongs to the day it started on.
      boolean overnight = to.isBefore(from);
      DayOfWeek day = overnight && time.isBefore(to) ? at.getDayOfWeek().minus(1)
          : at.getDayOfWeek();
      if (!days.contains(day)) return false;
      return overnight
          ? !time.isBefore(from) || time.isBefore(to)
          : !time.isBefore(from) && !time.isAfter(to);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static ZoneId zone(JsonNode hours) {
    String zone = hours.path("zone").asText("UTC");
    return ZoneId.of(zone);
  }

  private static Set<DayOfWeek> days(JsonNode hours) {
    if (!hours.path("days").isArray() || hours.path("days").isEmpty()) {
      return EnumSet.allOf(DayOfWeek.class);
    }
    Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
    for (JsonNode day : hours.path("days")) {
      String name = day.asText().toUpperCase(Locale.ROOT);
      DayOfWeek match = null;
      for (DayOfWeek candidate : DayOfWeek.values()) {
        if (candidate.name().startsWith(name) && name.length() >= 3) match = candidate;
      }
      if (match == null) throw new IllegalArgumentException("Unknown day " + name);
      days.add(match);
    }
    return days;
  }

  private static String rootMessage(JexlException e) {
    Throwable cause = e;
    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
    String message = cause.getMessage();
    return message == null ? e.toString() : message;
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }
}

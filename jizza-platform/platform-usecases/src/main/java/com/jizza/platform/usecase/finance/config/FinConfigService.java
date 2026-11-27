package com.jizza.platform.usecase.finance.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.engine.api.finance.FinanceStore.ConfigRow;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Kai's defaults and the tax and regulatory figures, with the person's own overrides on top.
 *
 * <p>Keys under {@code tax.} and {@code regulatory.} are figures that need verification: one whose
 * {@code verified_on} is empty or earlier than the start of its financial year is shown with a
 * "needs verification" badge, and later phases carry that badge into every result that uses it.
 */
@Service
public class FinConfigService {

  static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
  private static final int MAX_VALUE_CHARS = 20_000;

  private final FinanceStore store;
  private final FinScopeResolver scopes;
  private final AuditLog audit;

  public FinConfigService(FinanceStore store, FinScopeResolver scopes, AuditLog audit) {
    this.store = store;
    this.scopes = scopes;
    this.audit = audit;
  }

  /**
   * One setting as the Settings screen shows it. {@code source} is {@code kai_default} or
   * {@code you}; {@code defaultValue} is Kai's, so the screen can show what a reset returns to.
   */
  public record ConfigItem(String key, String area, JsonNode value, JsonNode defaultValue,
                           String source, boolean isOverride, LocalDate effectiveFrom,
                           String sourceUrl, LocalDate verifiedOn, boolean needsVerification) {}

  public List<ConfigItem> list(UUID callerUserId) {
    return list(callerUserId, LocalDate.now(ZONE));
  }

  List<ConfigItem> list(UUID callerUserId, LocalDate today) {
    var scope = scopes.own(callerUserId);
    Map<String, List<ConfigRow>> system = new LinkedHashMap<>();
    Map<String, List<ConfigRow>> mine = new LinkedHashMap<>();
    for (ConfigRow r : store.config(scope.ownerUserId())) {
      (r.userId() == null ? system : mine).computeIfAbsent(r.key(), k -> new ArrayList<>()).add(r);
    }
    List<ConfigItem> out = new ArrayList<>();
    system.forEach((key, versions) -> {
      ConfigRow base = current(versions, today);
      Optional<ConfigRow> override = mine.getOrDefault(key, List.of()).stream()
          .filter(r -> r.effectiveFrom().equals(base.effectiveFrom())).findFirst();
      out.add(new ConfigItem(key, area(key), override.map(ConfigRow::value).orElse(base.value()),
          base.value(), override.isPresent() ? "you" : "kai_default", override.isPresent(),
          base.effectiveFrom(), base.sourceUrl(), base.verifiedOn(), needsVerification(base)));
    });
    return out;
  }

  /** The value in force for this person today: their override, else Kai's. */
  public JsonNode value(UUID callerUserId, String key) {
    return list(callerUserId).stream().filter(i -> i.key().equals(key)).findFirst()
        .map(ConfigItem::value).orElseThrow(() -> new NotFoundException("Setting", key));
  }

  /** The person's own value for a key. It must keep the shape of Kai's default. */
  public ConfigItem put(UUID callerUserId, String key, JsonNode value) {
    var scope = scopes.own(callerUserId);
    ConfigItem item = item(callerUserId, key);
    if (value == null || value.isNull() || value.isMissingNode()) {
      throw new ValidationException("Send a value.");
    }
    if (value.toString().length() > MAX_VALUE_CHARS) throw new ValidationException("The value is too large.");
    String problem = shapeProblem(item.defaultValue(), value, "value");
    if (problem != null) throw new ValidationException(problem);
    store.putOverride(scope.ownerUserId(), key, item.effectiveFrom(), value);
    audit.record(scope.ownerUserId(), ActorType.USER, callerUserId, "finance.config.overridden", key, null);
    return item(callerUserId, key);
  }

  /** "Reset to Kai default". */
  public ConfigItem reset(UUID callerUserId, String key) {
    var scope = scopes.own(callerUserId);
    item(callerUserId, key);
    if (store.deleteOverrides(scope.ownerUserId(), key) > 0) {
      audit.record(scope.ownerUserId(), ActorType.USER, callerUserId, "finance.config.reset", key, null);
    }
    return item(callerUserId, key);
  }

  static boolean regulatory(String key) {
    return key.startsWith("tax.") || key.startsWith("regulatory.");
  }

  static boolean needsVerification(ConfigRow row) {
    if (!regulatory(row.key())) return false;
    return row.verifiedOn() == null || row.verifiedOn().isBefore(financialYearStart(row.effectiveFrom()));
  }

  /** 1 April on or before the date: India's financial year. */
  static LocalDate financialYearStart(LocalDate d) {
    int year = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
    return LocalDate.of(year, 4, 1);
  }

  /** The Settings group a key belongs to: its first segment. */
  static String area(String key) {
    int dot = key.indexOf('.');
    return dot < 0 ? key : key.substring(0, dot);
  }

  /** The version in force today; before the first one starts, the earliest. */
  static ConfigRow current(List<ConfigRow> versions, LocalDate today) {
    return versions.stream()
        .filter(r -> !r.effectiveFrom().isAfter(today))
        .filter(r -> r.effectiveTo() == null || !r.effectiveTo().isBefore(today))
        .max(Comparator.comparing(ConfigRow::effectiveFrom))
        .orElseGet(() -> versions.stream().min(Comparator.comparing(ConfigRow::effectiveFrom)).orElseThrow());
  }

  /** Null when {@code value} has the same structure as {@code template}, else what differs. */
  public static String shapeProblem(JsonNode template, JsonNode value, String path) {
    if (template.isNumber()) return value.isNumber() ? null : path + " must be a number.";
    if (template.isBoolean()) return value.isBoolean() ? null : path + " must be true or false.";
    if (template.isTextual()) return value.isTextual() ? null : path + " must be text.";
    if (template.isArray()) {
      if (!value.isArray()) return path + " must be a list.";
      if (value.isEmpty()) return path + " cannot be empty.";
      if (template.isEmpty()) return null;
      for (int i = 0; i < value.size(); i++) {
        // Compare with Kai's item at the same place (the last one for extra items), so a list
        // whose last row is open-ended, like the top tax slab, keeps that shape.
        JsonNode like = template.get(Math.min(i, template.size() - 1));
        String p = shapeProblem(like, value.get(i), path + "[" + i + "]");
        if (p != null) return p;
      }
      return null;
    }
    if (template.isObject()) {
      if (!value.isObject()) return path + " must be an object.";
      var expected = new HashSet<String>();
      template.fieldNames().forEachRemaining(expected::add);
      var actual = new HashSet<String>();
      value.fieldNames().forEachRemaining(actual::add);
      if (!expected.equals(actual)) return path + " must have exactly the fields " + expected + ".";
      for (String f : expected) {
        if (template.get(f).isNull()) {
          if (value.get(f).isNull() || value.get(f).isNumber()) continue; // "no upper limit"
          return path + "." + f + " must be a number or empty.";
        }
        String p = shapeProblem(template.get(f), value.get(f), path + "." + f);
        if (p != null) return p;
      }
      return null;
    }
    return null;
  }

  private ConfigItem item(UUID callerUserId, String key) {
    return list(callerUserId).stream().filter(i -> i.key().equals(key)).findFirst()
        .orElseThrow(() -> new NotFoundException("Setting", key));
  }
}

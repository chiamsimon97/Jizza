package com.jizza.platform.usecase.vita.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.vita.VitaStore;
import com.jizza.engine.api.vita.VitaStore.ConfigRow;
import com.jizza.platform.usecase.finance.config.FinConfigService;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Vita's defaults and clinical figures, with the user's own overrides on top (spec 4.2, 8).
 *
 * <p>Every figure is in one of three states. <b>verified</b>: someone checked it against its source
 * ({@code verified_on} set). <b>needs verification</b>: seeded from a recorded source but not yet
 * checked; keys under {@code clinical.} and the emergency numbers start here. <b>not set</b>: the
 * spec found no source, so there is no number at all and the screen says "Not set: ask your
 * doctor"; nothing may be computed from it. The not-set keys are listed in {@link #NOT_SET} only so
 * the screen can name them; this class never holds a clinical number.
 */
@Service
public class VitaConfigService {

  static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
  private static final int MAX_VALUE_CHARS = 20_000;

  /** Figures spec section 8 marks "not set": no source was found, so no number is seeded. */
  public static final List<String> NOT_SET = List.of(
      "clinical.redflags.high_sugar",
      "clinical.redflags.spo2",
      "clinical.redflags.temperature",
      "clinical.ranges.general.glucose_fasting",
      "clinical.ranges.general.glucose_after_meal",
      "clinical.ranges.general.spo2",
      "clinical.ranges.general.resting_heart_rate",
      "clinical.ranges.general.temperature",
      "clinical.intervals.diabetes_t2.kidney_function",
      "clinical.intervals.diabetes_t2.lipids",
      "clinical.intervals.hypertension.bp_checks",
      "clinical.intervals.hypertension.kidney_function",
      "clinical.intervals.hypertension.lipids",
      "clinical.goal_plans");

  public static final String VERIFIED = "verified";
  public static final String NEEDS_VERIFICATION = "needs_verification";
  public static final String NOT_SET_STATE = "not_set";
  /** A setting that is a product choice rather than a figure to check. */
  public static final String PRODUCT_DEFAULT = "product_default";

  private final VitaStore store;
  private final VitaScopeResolver scopes;
  private final AuditLog audit;

  public VitaConfigService(VitaStore store, VitaScopeResolver scopes, AuditLog audit) {
    this.store = store;
    this.scopes = scopes;
    this.audit = audit;
  }

  /**
   * One setting as the Settings screen shows it. {@code source} is {@code vita_default}, {@code you}
   * or, for a figure with no source, {@code none}; {@code state} is one of verified,
   * needs_verification, not_set, product_default; {@code label} is the words the screen shows.
   */
  public record ConfigItem(String key, String area, JsonNode value, JsonNode defaultValue,
                           String source, boolean isOverride, LocalDate effectiveFrom,
                           String sourceUrl, LocalDate verifiedOn, boolean needsVerification,
                           String state, String label) {}

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
      String state = state(base);
      out.add(new ConfigItem(key, area(key), override.map(ConfigRow::value).orElse(base.value()),
          base.value(), override.isPresent() ? "you" : "vita_default", override.isPresent(),
          base.effectiveFrom(), base.sourceUrl(), base.verifiedOn(), NEEDS_VERIFICATION.equals(state),
          state, label(state)));
    });
    for (String key : NOT_SET) {
      if (system.containsKey(key)) continue;
      out.add(new ConfigItem(key, area(key), null, null, "none", false, null, null, null, false,
          NOT_SET_STATE, label(NOT_SET_STATE)));
    }
    out.sort(Comparator.comparing(ConfigItem::key));
    return out;
  }

  /** One setting or figure; a figure with no source comes back as "not set", never as a number. */
  public ConfigItem item(UUID callerUserId, String key) {
    return list(callerUserId).stream().filter(i -> i.key().equals(key)).findFirst()
        .orElseThrow(() -> new NotFoundException("Setting", key));
  }

  /** The value in force for this user today, or empty when the figure is not set. */
  public Optional<JsonNode> value(UUID callerUserId, String key) {
    return Optional.ofNullable(item(callerUserId, key).value());
  }

  /** The user's own value for a key. It must keep the shape of Vita's default. */
  public ConfigItem put(UUID callerUserId, String key, JsonNode value) {
    var scope = scopes.own(callerUserId);
    ConfigItem item = item(callerUserId, key);
    if (NOT_SET_STATE.equals(item.state())) {
      throw new ValidationException("This figure is not set: there is no source for a default. "
          + "Targets from the person's doctor are added per person.");
    }
    if (value == null || value.isNull() || value.isMissingNode()) throw new ValidationException("Send a value.");
    if (value.toString().length() > MAX_VALUE_CHARS) throw new ValidationException("The value is too large.");
    String problem = FinConfigService.shapeProblem(item.defaultValue(), value, "value");
    if (problem != null) throw new ValidationException(problem);
    store.putOverride(scope.ownerUserId(), key, item.effectiveFrom(), value);
    audit.record(scope.ownerUserId(), ActorType.USER, callerUserId, "vita.config.overridden", key, null);
    return item(callerUserId, key);
  }

  /** "Reset to Vita default". */
  public ConfigItem reset(UUID callerUserId, String key) {
    var scope = scopes.own(callerUserId);
    item(callerUserId, key);
    if (store.deleteOverrides(scope.ownerUserId(), key) > 0) {
      audit.record(scope.ownerUserId(), ActorType.USER, callerUserId, "vita.config.reset", key, null);
    }
    return item(callerUserId, key);
  }

  /** Keys whose figures must be checked against their source before real use. */
  static boolean checked(String key) {
    return key.startsWith("clinical.") || key.startsWith("emergency.");
  }

  static String state(ConfigRow row) {
    if (!checked(row.key())) return PRODUCT_DEFAULT;
    return row.verifiedOn() == null ? NEEDS_VERIFICATION : VERIFIED;
  }

  static String label(String state) {
    return switch (state) {
      case VERIFIED -> "Vita default";
      case NEEDS_VERIFICATION -> "Vita default · needs verification";
      case NOT_SET_STATE -> "Not set: ask your doctor";
      default -> "Vita default";
    };
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
}

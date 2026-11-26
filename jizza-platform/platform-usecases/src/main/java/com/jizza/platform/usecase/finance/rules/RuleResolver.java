package com.jizza.platform.usecase.finance.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rule inheritance resolver (spec 5.1). Pure: it takes the levels that apply to one holding
 * in one goal, nearest first (instrument in goal → sub-category → asset class → goal defaults →
 * your defaults → Kai's defaults), and resolves every field on its own: the nearest level that
 * says SET or OFF wins, INHERIT looks further up.
 *
 * <p>A level can LOCK a field: the levels below cannot loosen it. A stricter value below a lock is
 * allowed for the fields where "stricter" has a meaning (the execution mode, the concentration
 * cap); anything else below a lock is refused on save and ignored here. Hard constraints are
 * never inherited: they are listed apart and applied after netting (Phase 4).
 */
public final class RuleResolver {

  private RuleResolver() {}

  public static final String INSTRUMENT = "instrument";
  public static final String SUB_CATEGORY = "sub_category";
  public static final String ASSET_CLASS = "asset_class";
  public static final String GOAL = "goal";
  public static final String USER = "user";
  public static final String KAI = "kai";

  /** Nearest first. */
  public static final List<String> LEVELS = List.of(INSTRUMENT, SUB_CATEGORY, ASSET_CLASS, GOAL, USER, KAI);

  /** The fields, one per rule type, in the order the screen shows them. */
  public static final List<String> FIELDS = List.of("stop_loss", "profit_booking", "rebalance_band",
      "execution_mode", "concentration_cap", "accumulate", "exit");

  public static final Set<String> STATES = Set.of("inherit", "set", "off");

  /** The execution modes from least to most freedom. Auto is not part of this build. */
  static final List<String> MODES = List.of("observe", "propose", "auto");

  /** One stored rule field at one level. */
  public record Row(String field, String state, boolean locked, boolean hardConstraint, JsonNode params) {}

  /** One level of the chain: its kind, its id (node, goal…; null for your and Kai's defaults), its name. */
  public record Level(String level, String id, String name, Map<String, Row> rows) {}

  /**
   * A field's effective value. {@code state} is set or off; {@code value} is null when off.
   * {@code sourceLevel}/{@code sourceId}/{@code sourceName} say where it comes from;
   * {@code locked} when a level above holds it, {@code lockedBy} that level's name.
   */
  public record Effective(String field, String state, JsonNode value, String sourceLevel, String sourceId,
                          String sourceName, boolean locked, String lockedBy) {}

  public record Resolved(Map<String, Effective> fields, List<Effective> hardConstraints) {}

  public static Resolved resolve(List<Level> chain) {
    Map<String, Effective> out = new LinkedHashMap<>();
    List<Effective> hard = new ArrayList<>();
    for (Level l : chain) {
      for (Row r : l.rows().values()) {
        if (r.hardConstraint() && "set".equals(r.state())) {
          hard.add(new Effective(r.field(), "set", r.params(), l.level(), l.id(), l.name(), r.locked(), null));
        }
      }
    }
    for (String field : FIELDS) out.put(field, field(chain, field));
    return new Resolved(out, hard);
  }

  static Effective field(List<Level> chain, String field) {
    int lockAt = -1;
    for (int i = chain.size() - 1; i >= 0; i--) {
      Row r = chain.get(i).rows().get(field);
      if (r != null && !r.hardConstraint() && r.locked() && !"inherit".equals(r.state())) {
        lockAt = i;
        break;
      }
    }
    for (int i = 0; i < chain.size(); i++) {
      Level l = chain.get(i);
      Row r = l.rows().get(field);
      if (r == null || r.hardConstraint() || "inherit".equals(r.state())) continue;
      if (lockAt >= 0 && i < lockAt && !stricter(field, r, chain.get(lockAt).rows().get(field))) continue;
      boolean locked = lockAt >= 0;
      return new Effective(field, r.state(), "off".equals(r.state()) ? null : r.params(), l.level(), l.id(),
          l.name(), locked, locked ? chain.get(lockAt).name() : null);
    }
    return new Effective(field, "off", null, KAI, null, "Kai default", false, null);
  }

  /**
   * Why a new value cannot be saved at {@code chain.get(at)}, or null when it can. The chain is
   * the holding's (or node's) own, nearest first; levels above {@code at} are checked for locks.
   */
  public static String refusal(List<Level> chain, int at, String field, Row proposed) {
    if (!FIELDS.contains(field)) return "Unknown rule " + field + ".";
    if (proposed.hardConstraint() || "inherit".equals(proposed.state())) return null;
    for (int i = chain.size() - 1; i > at; i--) {
      Row r = chain.get(i).rows().get(field);
      if (r != null && !r.hardConstraint() && r.locked() && !"inherit".equals(r.state())
          && !stricter(field, proposed, r)) {
        return chain.get(i).name() + " locks " + label(field) + "; it can't be loosened here.";
      }
    }
    return null;
  }

  /** Whether {@code child} is at least as strict as the locked {@code parent}. */
  static boolean stricter(String field, Row child, Row parent) {
    if (parent == null) return true;
    if ("off".equals(child.state())) return "off".equals(parent.state()) && !"concentration_cap".equals(field);
    return switch (field) {
      case "execution_mode" -> {
        int c = MODES.indexOf(child.params().path("mode").asText());
        int p = "off".equals(parent.state()) ? -1 : MODES.indexOf(parent.params().path("mode").asText());
        yield c >= 0 && p >= 0 && c <= p;
      }
      case "concentration_cap" -> {
        if ("off".equals(parent.state())) yield true;
        BigDecimal c = num(child.params(), "maxPct");
        BigDecimal p = num(parent.params(), "maxPct");
        yield c != null && p != null && c.compareTo(p) <= 0;
      }
      default -> false;
    };
  }

  /** Checks a field's values; returns the message to show, or null. */
  public static String invalid(String field, JsonNode p) {
    if (p == null || !p.isObject()) return "Send the rule's values.";
    return switch (field) {
      case "stop_loss" -> oneOf(p, "kind", "fixed", "trailing") ? pct(p, "pct", false) : "Stop loss is fixed or trailing.";
      case "profit_booking" -> first(pct(p, "sellPct", false), pct(p, "atGainPct", true));
      case "rebalance_band" -> switch (p.path("mode").asText()) {
        case "absolute" -> pct(p, "points", false);
        case "relative" -> pct(p, "pct", false);
        case "five_twenty_five" -> null;
        default -> "The band is absolute points, relative percent, or the 5/25 rule.";
      };
      case "execution_mode" -> oneOf(p, "mode", "observe", "propose", "auto") ? null : "Execution is Observe or Propose.";
      case "concentration_cap" -> pct(p, "maxPct", false);
      case "accumulate" -> oneOf(p, "kind", "dip", "sip_date") ? null : "Accumulate on a dip or on the SIP date.";
      case "exit" -> oneOf(p, "kind", "thesis", "time", "bucket_change") ? null : "Exit on thesis, time or a bucket change.";
      default -> "Unknown rule " + field + ".";
    };
  }

  /** The field's name on screen. */
  public static String label(String field) {
    return switch (field) {
      case "stop_loss" -> "Stop loss";
      case "profit_booking" -> "Profit booking";
      case "rebalance_band" -> "Rebalance band";
      case "execution_mode" -> "Execution";
      case "concentration_cap" -> "Concentration cap";
      case "accumulate" -> "Accumulate";
      case "exit" -> "Exit";
      default -> field;
    };
  }

  /** Kai's defaults as a level, from the {@code rules.defaults} configuration value. */
  public static Level kaiLevel(JsonNode defaults) {
    Map<String, Row> rows = new LinkedHashMap<>();
    if (defaults != null) {
      for (String f : FIELDS) {
        JsonNode d = defaults.get(f);
        if (d == null) continue;
        JsonNode params = d.has("params") ? d.get("params") : JsonNodeFactory.instance.objectNode();
        rows.put(f, new Row(f, d.path("state").asText("off"), false, false, params));
      }
    }
    return new Level(KAI, null, "Kai default", rows);
  }

  private static boolean oneOf(JsonNode p, String f, String... values) {
    String v = p.path(f).asText("");
    for (String x : values) if (x.equals(v)) return true;
    return false;
  }

  private static String pct(JsonNode p, String f, boolean allowAbove100) {
    BigDecimal v = num(p, f);
    if (v == null || v.signum() <= 0 || (!allowAbove100 && v.compareTo(BigDecimal.valueOf(100)) > 0)) {
      return "Give " + f + " as a percentage above 0" + (allowAbove100 ? "." : " and at most 100.");
    }
    return null;
  }

  private static String first(String a, String b) {
    return a != null ? a : b;
  }

  static BigDecimal num(JsonNode p, String f) {
    JsonNode v = p == null ? null : p.get(f);
    return v == null || !v.isNumber() ? null : v.decimalValue();
  }
}

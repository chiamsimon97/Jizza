package com.jizza.platform.usecase.finance.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceMarket;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.config.FinConfigService;
import com.jizza.platform.usecase.finance.rules.RuleResolver.Effective;
import com.jizza.platform.usecase.finance.rules.RuleResolver.Level;
import com.jizza.platform.usecase.finance.rules.RuleResolver.Row;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import com.jizza.platform.usecase.finance.tree.AssetTree;
import com.jizza.platform.usecase.finance.tree.TreeIndex;
import com.jizza.platform.usecase.finance.tree.TreeStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rules (spec 5.1, screen 7.5): what is set at each level, what is in force for a holding in a
 * goal and where it comes from, and the impact preview ("This change affects N earmarks") before
 * a save. Rules describe what Kai watches for; in this build nothing they say places an order.
 */
@Service
public class RulesService {

  public static final String DEFAULTS_KEY = "rules.defaults";

  private final FinanceRecords records;
  private final FinanceMarket market;
  private final TreeStore trees;
  private final FinConfigService config;
  private final FinScopeResolver scopes;
  private final AuditLog audit;

  public RulesService(FinanceRecords records, FinanceMarket market, TreeStore trees, FinConfigService config,
                      FinScopeResolver scopes, AuditLog audit) {
    this.records = records;
    this.market = market;
    this.trees = trees;
    this.config = config;
    this.scopes = scopes;
    this.audit = audit;
  }

  /** Where a rule is set: its level and, for the lower levels, the goal, node or instrument. */
  public record Target(String level, String goalId, String nodeId, String instrumentId) {}

  /** A row of the rules table: the field, each level's cell, and the effective value. */
  public record FieldView(String field, String label, List<ObjectNode> cells, Effective effective) {}

  public record RulesView(String goalId, String goalName, String instrumentId, String instrumentName,
                          String nodeId, List<ObjectNode> chain, List<FieldView> fields,
                          List<Effective> hardConstraints, List<ObjectNode> plans) {}

  // ── the chain ─────────────────────────────────────────────────────────────

  /** The levels for a holding (or a node) in a goal, nearest first. */
  public List<Level> chain(UUID owner, String goalId, String instrumentId, String nodeId, TreeIndex index,
                           List<ObjectNode> ruleRows, JsonNode kaiDefaults) {
    List<Level> out = new ArrayList<>();
    if (goalId != null) {
      if (instrumentId != null) {
        String name = market.instrument(UUID.fromString(instrumentId)).map(i -> i.path("name").asText()).orElse("This holding");
        out.add(level(RuleResolver.INSTRUMENT, instrumentId, name, ruleRows, r ->
            "instrument".equals(r.path("scopeLevel").asText()) && goalId.equals(r.path("planId").asText(null))
                && instrumentId.equals(r.path("instrumentId").asText(null))));
      }
      AssetTree.Node start = null;
      if (nodeId != null) start = index.node(nodeId);
      else if (instrumentId != null) {
        start = market.instrument(UUID.fromString(instrumentId)).map(index::place).orElse(null);
      }
      if (start != null) {
        for (AssetTree.Node n : index.path(start)) {
          if ("instrument".equals(n.kind())) continue;
          out.add(level(n.kind(), n.id(), n.name(), ruleRows, r -> n.id().equals(r.path("nodeId").asText(null))
              && n.kind().equals(r.path("scopeLevel").asText())));
        }
      }
      String goalName = records.get("fin_plan", owner, UUID.fromString(goalId)).map(g -> g.path("name").asText()).orElse("Goal");
      out.add(level(RuleResolver.GOAL, goalId, goalName + " defaults", ruleRows, r ->
          "goal".equals(r.path("scopeLevel").asText()) && goalId.equals(r.path("planId").asText(null))));
    }
    out.add(level(RuleResolver.USER, null, "Your defaults", ruleRows, r -> "user".equals(r.path("scopeLevel").asText())));
    out.add(RuleResolver.kaiLevel(kaiDefaults));
    return out;
  }

  private static Level level(String kind, String id, String name, List<ObjectNode> rows,
                             java.util.function.Predicate<ObjectNode> mine) {
    Map<String, Row> m = new LinkedHashMap<>();
    for (ObjectNode r : rows) {
      if (!mine.test(r)) continue;
      m.put(r.path("field").asText(), new Row(r.path("field").asText(), r.path("state").asText(),
          r.path("locked").asBoolean(), r.path("isHardConstraint").asBoolean(), r.path("params")));
    }
    return new Level(kind, id, name, m);
  }

  public JsonNode kaiDefaults(UUID owner) {
    return config.value(owner, DEFAULTS_KEY);
  }

  public List<ObjectNode> ruleRows(UUID owner) {
    return records.list("fin_rule", owner, Map.of());
  }

  /** Everything in force for one holding (or node) of a goal, with the table the screen shows. */
  public RulesView view(UUID caller, UUID goalId, UUID instrumentId, UUID nodeId) {
    UUID owner = scopes.own(caller).ownerUserId();
    String g = goalId == null ? null : goal(owner, goalId).path("id").asText();
    TreeIndex index = g == null ? new TreeIndex(List.of()) : trees.index(owner, g);
    if (nodeId != null && index.node(nodeId.toString()) == null) throw new NotFoundException("Tree node", nodeId);
    String ins = instrumentId == null ? null : market.instrument(instrumentId)
        .orElseThrow(() -> new NotFoundException("Instrument", instrumentId)).path("id").asText();
    List<Level> chain = chain(owner, g, ins, nodeId == null ? null : nodeId.toString(), index, ruleRows(owner), kaiDefaults(owner));
    var resolved = RuleResolver.resolve(chain);
    List<ObjectNode> levels = new ArrayList<>();
    for (Level l : chain) levels.add(Json.obj().put("level", l.level()).put("id", l.id()).put("name", l.name()));
    List<FieldView> fields = new ArrayList<>();
    for (String f : RuleResolver.FIELDS) {
      List<ObjectNode> cells = new ArrayList<>();
      for (Level l : chain) {
        Row r = l.rows().get(f);
        ObjectNode c = Json.obj().put("level", l.level()).put("levelId", l.id())
            .put("state", r == null ? "inherit" : r.state()).put("locked", r != null && r.locked());
        if (r != null && !"off".equals(r.state())) c.set("params", r.params());
        cells.add(c);
      }
      fields.add(new FieldView(f, RuleResolver.label(f), cells, resolved.fields().get(f)));
    }
    // The goals that hold this instrument, for the plan tab strip.
    List<ObjectNode> plans = new ArrayList<>();
    if (ins != null) {
      Map<String, Boolean> seen = new HashMap<>();
      for (ObjectNode e : records.list("fin_earmark", owner, Map.of("instrumentId", ins))) {
        String pid = e.path("planId").asText();
        if (seen.put(pid, true) != null) continue;
        records.get("fin_plan", owner, UUID.fromString(pid)).filter(p -> !"unassigned".equals(p.path("type").asText()))
            .ifPresent(p -> plans.add(Json.obj().put("id", pid).put("name", p.path("name").asText())));
      }
    }
    String insName = ins == null ? null : market.instrument(UUID.fromString(ins)).map(i -> i.path("name").asText()).orElse(null);
    String goalName = g == null ? null : goal(owner, goalId).path("name").asText();
    return new RulesView(g, goalName, ins, insName, nodeId == null ? null : nodeId.toString(), levels, fields,
        resolved.hardConstraints(), plans);
  }

  // ── save and preview ──────────────────────────────────────────────────────

  /** One field set at one level: {target…, field, state, locked, params}. */
  public record Change(Target target, String field, String state, boolean locked, JsonNode params) {}

  public Change change(UUID owner, JsonNode body) {
    if (body == null || !body.isObject()) throw new ValidationException("Send the rule as an object.");
    String level = body.path("level").asText("");
    if (!List.of("user", "goal", "asset_class", "sub_category", "instrument").contains(level)) {
      throw new ValidationException("level is user, goal, asset_class, sub_category or instrument.");
    }
    String goalId = text(body, "goalId");
    String nodeId = text(body, "nodeId");
    String instrumentId = text(body, "instrumentId");
    switch (level) {
      case "user" -> { goalId = null; nodeId = null; instrumentId = null; }
      case "goal" -> { need(goalId, "goalId"); nodeId = null; instrumentId = null; }
      case "instrument" -> { need(goalId, "goalId"); need(instrumentId, "instrumentId"); nodeId = null; }
      default -> { need(goalId, "goalId"); need(nodeId, "nodeId"); instrumentId = null; }
    }
    if (goalId != null) goal(owner, UUID.fromString(goalId));
    if (nodeId != null) {
      AssetTree.Node n = trees.index(owner, goalId).node(nodeId);
      if (n == null) throw new NotFoundException("Tree node", nodeId);
      if (!n.kind().equals(level)) throw new ValidationException("That node is not a " + level.replace('_', ' ') + ".");
    }
    if (instrumentId != null) {
      String id = instrumentId;
      market.instrument(UUID.fromString(id)).orElseThrow(() -> new NotFoundException("Instrument", id));
    }
    String field = body.path("field").asText("");
    if (!RuleResolver.FIELDS.contains(field)) throw new ValidationException("Unknown rule " + field + ".");
    String state = body.path("state").asText("");
    if (!RuleResolver.STATES.contains(state)) throw new ValidationException("state is inherit, set or off.");
    JsonNode params = body.has("params") && body.get("params").isObject() ? body.get("params") : Json.obj();
    if ("set".equals(state)) {
      String bad = RuleResolver.invalid(field, params);
      if (bad != null) throw new ValidationException(bad);
    }
    boolean locked = body.path("locked").asBoolean(false);
    if (locked && "instrument".equals(level)) throw new ValidationException("A lock is set on a parent level, not on a holding.");
    return new Change(new Target(level, goalId, nodeId, instrumentId), field, state, locked, params);
  }

  /** Why the change cannot be saved, checked against every holding it reaches; null when fine. */
  String refusal(UUID owner, Change c) {
    if ("execution_mode".equals(c.field()) && "set".equals(c.state()) && "auto".equals(c.params().path("mode").asText())) {
      // The lock message comes first where there is one, then the build's own limit.
      String locked = lockRefusal(owner, c);
      return locked != null ? locked : "Kai only observes and proposes in this build; it never acts on its own.";
    }
    return lockRefusal(owner, c);
  }

  private String lockRefusal(UUID owner, Change c) {
    if (c.target().goalId() == null) return null;
    TreeIndex index = trees.index(owner, c.target().goalId());
    List<Level> chain = chain(owner, c.target().goalId(), c.target().instrumentId(), c.target().nodeId(), index,
        ruleRows(owner), kaiDefaults(owner));
    int at = 0;
    for (int i = 0; i < chain.size(); i++) {
      Level l = chain.get(i);
      if (l.level().equals(c.target().level()) && Objects.equals(l.id(), id(c.target()))) {
        at = i;
        break;
      }
    }
    return RuleResolver.refusal(chain, at, c.field(), new Row(c.field(), c.state(), c.locked(), false, c.params()));
  }

  private static String id(Target t) {
    return switch (t.level()) {
      case "instrument" -> t.instrumentId();
      case "goal" -> t.goalId();
      case "user" -> null;
      default -> t.nodeId();
    };
  }

  /** How many earmarks' effective value of this field would change. */
  public int affected(UUID owner, Change c) {
    List<ObjectNode> rows = ruleRows(owner);
    List<ObjectNode> after = withChange(rows, c);
    JsonNode kai = kaiDefaults(owner);
    int n = 0;
    Map<String, TreeIndex> indexes = new HashMap<>();
    for (ObjectNode e : records.list("fin_earmark", owner, Map.of("mode", "units"))) {
      String plan = e.path("planId").asText();
      String ins = e.path("instrumentId").asText();
      if (c.target().goalId() != null && !c.target().goalId().equals(plan)) continue;
      if (c.target().instrumentId() != null && !c.target().instrumentId().equals(ins)) continue;
      TreeIndex index = indexes.computeIfAbsent(plan, p -> trees.index(owner, p));
      if (c.target().nodeId() != null) {
        AssetTree.Node at = market.instrument(UUID.fromString(ins)).map(index::place).orElse(null);
        if (at == null || !index.under(at, c.target().nodeId())) continue;
      }
      Effective before = RuleResolver.resolve(chain(owner, plan, ins, null, index, rows, kai)).fields().get(c.field());
      Effective now = RuleResolver.resolve(chain(owner, plan, ins, null, index, after, kai)).fields().get(c.field());
      if (!same(before, now)) n++;
    }
    return n;
  }

  public record Saved(ObjectNode rule, int affectedEarmarks) {}

  @Transactional
  public int preview(UUID caller, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    Change c = change(owner, body);
    String why = refusal(owner, c);
    if (why != null) throw new ValidationException(why);
    return affected(owner, c);
  }

  @Transactional
  public Saved put(UUID caller, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    Change c = change(owner, body);
    String why = refusal(owner, c);
    if (why != null) throw new ValidationException(why);
    int affected = affected(owner, c);
    ObjectNode existing = find(ruleRows(owner), c);
    ObjectNode v = Json.obj().put("state", c.state()).put("locked", c.locked());
    v.set("params", c.params());
    ObjectNode saved;
    if (existing == null) {
      v.put("scopeLevel", c.target().level()).put("ruleType", c.field()).put("field", c.field());
      if (c.target().goalId() != null) v.put("planId", c.target().goalId());
      if (c.target().nodeId() != null) v.put("nodeId", c.target().nodeId());
      if (c.target().instrumentId() != null) v.put("instrumentId", c.target().instrumentId());
      saved = records.insert("fin_rule", owner, v);
    } else {
      saved = records.update("fin_rule", owner, UUID.fromString(existing.path("id").asText()), v).orElseThrow();
    }
    audit.record(owner, ActorType.USER, caller, "finance.rule.saved", saved.path("id").asText(),
        Json.obj().put("field", c.field()).put("level", c.target().level()).put("affected", affected));
    return new Saved(saved, affected);
  }

  // ── templates ─────────────────────────────────────────────────────────────

  public List<ObjectNode> templates(UUID caller) {
    UUID owner = scopes.own(caller).ownerUserId();
    return records.list("fin_rule_template", owner, Map.of());
  }

  /** Saves what is set at one level as a named template ("Conservative mid cap"). */
  @Transactional
  public ObjectNode saveTemplate(UUID caller, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    String name = body == null ? "" : body.path("name").asText("").trim();
    if (name.isEmpty() || name.length() > 80) throw new ValidationException("Give the template a name of up to 80 characters.");
    Change from = change(owner, ((ObjectNode) body.deepCopy()).put("field", "stop_loss").put("state", "inherit"));
    ArrayNode rules = Json.obj().arrayNode();
    for (ObjectNode r : ruleRows(owner)) {
      if (!matches(r, from.target())) continue;
      ObjectNode x = rules.addObject().put("field", r.path("field").asText()).put("state", r.path("state").asText())
          .put("locked", r.path("locked").asBoolean());
      x.set("params", r.path("params"));
    }
    if (rules.isEmpty()) throw new ValidationException("Nothing is set there yet to save as a template.");
    var existing = records.list("fin_rule_template", owner, Map.of("name", name));
    ObjectNode v = Json.obj().put("name", name);
    v.set("rules", rules);
    return existing.isEmpty() ? records.insert("fin_rule_template", owner, v)
        : records.update("fin_rule_template", owner, UUID.fromString(existing.get(0).path("id").asText()), v).orElseThrow();
  }

  /** Applies a template's fields at a level; each field is checked like a single save. */
  @Transactional
  public int applyTemplate(UUID caller, UUID templateId, JsonNode target) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode t = records.get("fin_rule_template", owner, templateId).orElseThrow(() -> new NotFoundException("Template", templateId));
    int affected = 0;
    for (JsonNode r : t.path("rules")) {
      ObjectNode body = ((ObjectNode) target.deepCopy()).put("field", r.path("field").asText())
          .put("state", r.path("state").asText()).put("locked", r.path("locked").asBoolean() && !"instrument".equals(target.path("level").asText()));
      body.set("params", r.path("params"));
      affected += put(caller, body).affectedEarmarks();
    }
    return affected;
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static boolean same(Effective a, Effective b) {
    return a.state().equals(b.state()) && Objects.equals(a.value(), b.value());
  }

  private static List<ObjectNode> withChange(List<ObjectNode> rows, Change c) {
    List<ObjectNode> out = new ArrayList<>();
    boolean replaced = false;
    for (ObjectNode r : rows) {
      if (matches(r, c.target()) && c.field().equals(r.path("field").asText())) {
        out.add(changed(r.deepCopy(), c));
        replaced = true;
      } else {
        out.add(r);
      }
    }
    if (!replaced) {
      ObjectNode r = Json.obj().put("scopeLevel", c.target().level()).put("field", c.field());
      if (c.target().goalId() != null) r.put("planId", c.target().goalId());
      if (c.target().nodeId() != null) r.put("nodeId", c.target().nodeId());
      if (c.target().instrumentId() != null) r.put("instrumentId", c.target().instrumentId());
      out.add(changed(r, c));
    }
    return out;
  }

  private static ObjectNode changed(ObjectNode r, Change c) {
    r.put("state", c.state()).put("locked", c.locked()).put("isHardConstraint", false);
    r.set("params", c.params());
    return r;
  }

  private static ObjectNode find(List<ObjectNode> rows, Change c) {
    return rows.stream().filter(r -> matches(r, c.target()) && c.field().equals(r.path("field").asText())).findFirst().orElse(null);
  }

  private static boolean matches(ObjectNode r, Target t) {
    return t.level().equals(r.path("scopeLevel").asText())
        && Objects.equals(t.goalId(), r.path("planId").asText(null))
        && Objects.equals(t.nodeId(), r.path("nodeId").asText(null))
        && Objects.equals(t.instrumentId(), r.path("instrumentId").asText(null));
  }

  private ObjectNode goal(UUID owner, UUID id) {
    return records.get("fin_plan", owner, id).filter(g -> !"unassigned".equals(g.path("type").asText()))
        .orElseThrow(() -> new NotFoundException("Goal", id));
  }

  private static String text(JsonNode b, String f) {
    JsonNode v = b.get(f);
    if (v == null || v.isNull() || v.asText().isBlank()) return null;
    try {
      return UUID.fromString(v.asText()).toString();
    } catch (IllegalArgumentException e) {
      throw new ValidationException(f + " is not an id.");
    }
  }

  private static void need(String v, String f) {
    if (v == null) throw new ValidationException("Send " + f + ".");
  }
}

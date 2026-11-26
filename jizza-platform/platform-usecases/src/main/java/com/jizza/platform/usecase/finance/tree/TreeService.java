package com.jizza.platform.usecase.finance.tree;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceMarket;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.builder.AllocationResolver;
import com.jizza.platform.usecase.finance.builder.AllocationResolver.TemplateNode;
import com.jizza.platform.usecase.finance.goal.GoalMath;
import com.jizza.platform.usecase.finance.health.HouseholdLoader;
import com.jizza.platform.usecase.finance.health.HouseholdLoader.Loaded;
import com.jizza.platform.usecase.finance.ledger.HoldingsReader;
import com.jizza.platform.usecase.finance.ledger.HoldingsReader.Position;
import com.jizza.platform.usecase.finance.market.InstrumentService;
import com.jizza.platform.usecase.finance.model.Household.Person;
import com.jizza.platform.usecase.finance.rules.RuleResolver;
import com.jizza.platform.usecase.finance.rules.RulesService;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A goal's asset tree (screen 7.4, goal wizard step 5) and its allocation and drift (7.7). Until
 * the user saves a tree, a goal follows Kai's template for its horizon and risk (2A.8). Saving
 * keeps every node's id, so the rules set on a node stay with it.
 *
 * <p>The short-goal rule: Kai's Short template holds no direct shares and no gold, and Kai never
 * proposes direct shares for a Short goal. When the user puts shares in a goal under three years
 * away, Kai shows the warning once; keeping them records the choice and its date, shown as a
 * "Your choice" badge.
 */
@Service
public class TreeService {

  public static final String SHORT_GOAL_WARNING =
      "This goal is under 3 years away. Shares can fall sharply in that time. Kai's template keeps them out "
          + "and Kai will never suggest them here.";
  public static final String SHORT_GOAL_TYPE = "finance-short-goal-shares";
  static final int MAX_NODES = 200;

  private final FinanceRecords records;
  private final FinanceMarket market;
  private final TreeStore store;
  private final HouseholdLoader loader;
  private final HoldingsReader holdings;
  private final RulesService rules;
  private final FinScopeResolver scopes;
  private final AuditLog audit;

  public TreeService(FinanceRecords records, FinanceMarket market, TreeStore store, HouseholdLoader loader,
                     HoldingsReader holdings, RulesService rules, FinScopeResolver scopes, AuditLog audit) {
    this.records = records;
    this.market = market;
    this.store = store;
    this.loader = loader;
    this.holdings = holdings;
    this.rules = rules;
    this.scopes = scopes;
    this.audit = audit;
  }

  /** The warning the screens show before shares go into a goal under three years away. */
  public static JizzaException shortGoalWarning() {
    return new JizzaException(HttpStatus.CONFLICT, SHORT_GOAL_TYPE, "Shares in a short goal", SHORT_GOAL_WARNING);
  }

  public record TreeView(String goalId, String goalName, String goalType, String bucket, boolean saved,
                         boolean neverRebalanced, List<ObjectNode> nodes, List<String> problems) {}

  // ── reading ───────────────────────────────────────────────────────────────

  public TreeView get(UUID caller, UUID goalId) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode goal = goal(owner, goalId);
    Loaded l = loader.load(owner);
    return view(owner, goal, l);
  }

  TreeView view(UUID owner, ObjectNode goal, Loaded l) {
    List<ObjectNode> rows = store.rows(owner, goal.path("id").asText());
    boolean saved = !rows.isEmpty();
    List<ObjectNode> nodes = saved ? rows.stream().map(TreeService::out).toList() : template(goal, l);
    List<AssetTree.Node> plain = nodes.stream().map(TreeService::plain).toList();
    Map<String, BigDecimal> goalPct = AssetTree.goalTargets(plain);
    Map<String, String> names = names(nodes);
    for (ObjectNode n : nodes) {
      BigDecimal p = goalPct.get(n.path("id").asText());
      if (p != null) n.put("goalPct", p.setScale(2, RoundingMode.HALF_UP));
      if (n.hasNonNull("instrumentId")) n.put("instrumentName", names.get(n.path("instrumentId").asText()));
    }
    return new TreeView(goal.path("id").asText(), goal.path("name").asText(), goal.path("type").asText(),
        bucket(goal, l), saved, "emergency".equals(goal.path("type").asText()), nodes, AssetTree.problems(plain));
  }

  /** Kai's template for the goal as unsaved nodes (ids "new:equity", "new:equity/mid_cap"). */
  List<ObjectNode> template(ObjectNode goal, Loaded l) {
    Map<String, Person> people = new HashMap<>();
    l.household().people().forEach(p -> people.put(p.id(), p));
    Integer age = GoalMath.ageFor(goal, people, l.selfId(), l.household().asOf());
    String risk = GoalMath.riskFor(goal, l.latestRisk(), l.selfId()).profile();
    List<ObjectNode> out = new ArrayList<>();
    int order = 0;
    for (TemplateNode c : AllocationResolver.template(bucket(goal, l), age == null ? 35 : age, risk, l.plan())) {
      String cid = "new:" + c.key();
      out.add(Json.obj().put("id", cid).putNull("parentId").put("kind", "asset_class").put("key", c.key())
          .put("name", c.label()).put("targetPct", c.pct()).put("source", c.sourceLabel()).put("sortOrder", order++)
          .put("inRebalancePool", !"emergency".equals(goal.path("type").asText())));
      for (TemplateNode s : c.children()) {
        out.add(Json.obj().put("id", cid + "/" + s.key()).put("parentId", cid).put("kind", "sub_category")
            .put("key", s.key()).put("name", s.label()).put("targetPct", s.pct()).put("source", s.sourceLabel())
            .put("sortOrder", order++).put("inRebalancePool", !"emergency".equals(goal.path("type").asText())));
      }
    }
    return out;
  }

  public String bucket(ObjectNode goal, Loaded l) {
    return l.plan().bucketFor(months(goal, l.household().asOf()));
  }

  static long months(ObjectNode goal, LocalDate today) {
    return Math.max(0, ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(LocalDate.parse(goal.path("targetDate").asText()))));
  }

  public boolean isShort(ObjectNode goal, Loaded l) {
    return "short".equals(bucket(goal, l));
  }

  // ── saving ────────────────────────────────────────────────────────────────

  /**
   * Replaces the goal's tree with {@code body.nodes}. A node is matched to the saved one at the
   * same place (its parent's place and its key), so its id and its rules stay. Siblings with a
   * target must add up to 100 (422 otherwise). Shares added to a goal under three years away need
   * {@code keepShares} (409 with the warning otherwise).
   */
  @Transactional
  public TreeView put(UUID caller, UUID goalId, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode goal = goal(owner, goalId);
    Loaded l = loader.load(owner);
    if (body == null || !body.path("nodes").isArray()) throw new ValidationException("Send the tree as {nodes: [...]}.");
    if (body.path("nodes").size() > MAX_NODES) throw new ValidationException("A tree has at most " + MAX_NODES + " nodes.");
    boolean keepShares = body.path("keepShares").asBoolean(false);

    // Read the nodes and check their shape.
    Map<String, ObjectNode> in = new LinkedHashMap<>();
    for (JsonNode n : body.path("nodes")) {
      String id = n.path("id").asText("");
      if (id.isEmpty() || in.containsKey(id)) throw new ValidationException("Each node needs its own id.");
      in.put(id, clean(n));
    }
    Map<String, String> path = new HashMap<>();
    for (String id : in.keySet()) path.put(id, pathOf(id, in, 0));
    List<AssetTree.Node> plain = new ArrayList<>();
    in.values().forEach(n -> plain.add(plain(n)));
    List<String> problems = AssetTree.problems(plain);
    if (!problems.isEmpty()) {
      throw new JizzaException(HttpStatus.UNPROCESSABLE_ENTITY, "finance-tree-sum", "Targets don't add up",
          String.join(" ", problems));
    }

    List<ObjectNode> existing = store.rows(owner, goal.path("id").asText());
    Map<String, ObjectNode> savedByPath = new HashMap<>();
    Map<String, ObjectNode> savedById = new HashMap<>();
    existing.forEach(r -> savedById.put(r.path("id").asText(), r));
    for (ObjectNode r : existing) savedByPath.put(savedPath(r, savedById, 0), r);

    // Shares in a short goal: new ones need the user's choice.
    boolean isShort = isShort(goal, l);
    List<String> newShares = new ArrayList<>();
    for (var e : in.entrySet()) {
      ObjectNode n = e.getValue();
      if (!"instrument".equals(n.path("kind").asText())) continue;
      ObjectNode ins = market.instrument(UUID.fromString(n.path("instrumentId").asText()))
          .orElseThrow(() -> new NotFoundException("Instrument", n.path("instrumentId").asText()));
      ObjectNode was = savedByPath.get(path.get(e.getKey()));
      boolean acked = was != null && was.hasNonNull("userChoiceAckAt");
      if (isShort && InstrumentService.DIRECT_SHARES.contains(ins.path("type").asText()) && !acked) newShares.add(e.getKey());
    }
    if (!newShares.isEmpty() && !keepShares) throw shortGoalWarning();

    // Write parents before children, keeping ids where the place is the same.
    Map<String, String> realId = new HashMap<>();
    Set<String> kept = new HashSet<>();
    List<String> order = new ArrayList<>(in.keySet());
    order.sort((a, b) -> Integer.compare(depth(a, in), depth(b, in)));
    int sort = 0;
    for (String id : order) {
      ObjectNode n = in.get(id);
      ObjectNode v = Json.obj().put("kind", n.path("kind").asText()).put("nodeKey", n.path("key").asText())
          .put("name", n.path("name").asText()).put("sortOrder", n.has("sortOrder") ? n.get("sortOrder").asInt() : sort)
          .put("inRebalancePool", n.path("inRebalancePool").asBoolean(!"emergency".equals(goal.path("type").asText())));
      sort++;
      if (n.hasNonNull("targetPct")) v.put("targetPct", n.get("targetPct").decimalValue());
      else v.putNull("targetPct");
      if (n.hasNonNull("userChoiceNote")) v.put("userChoiceNote", n.get("userChoiceNote").asText());
      String parent = n.hasNonNull("parentId") ? realId.get(n.get("parentId").asText()) : null;
      if (parent != null) v.put("parentId", parent);
      else v.putNull("parentId");
      if (newShares.contains(id)) {
        v.put("userChoiceAckAt", Instant.now().toString());
        if (!v.has("userChoiceNote")) v.put("userChoiceNote", "Kept by your choice");
      }
      ObjectNode was = savedByPath.get(path.get(id));
      if (was != null) {
        records.update("fin_node", owner, UUID.fromString(was.path("id").asText()), v);
        realId.put(id, was.path("id").asText());
        kept.add(was.path("id").asText());
      } else {
        v.put("planId", goal.path("id").asText());
        if ("instrument".equals(n.path("kind").asText())) v.put("instrumentId", n.path("instrumentId").asText());
        realId.put(id, records.insert("fin_node", owner, v).path("id").asText());
      }
    }
    // Nodes no longer in the tree go, children first (their rules go with them).
    List<ObjectNode> gone = existing.stream().filter(r -> !kept.contains(r.path("id").asText())).toList();
    gone.stream().sorted((a, b) -> Integer.compare(savedDepth(b, savedById), savedDepth(a, savedById)))
        .forEach(r -> records.delete("fin_node", owner, UUID.fromString(r.path("id").asText())));
    audit.record(owner, ActorType.USER, caller, "finance.tree.saved", goal.path("id").asText(),
        Json.obj().put("nodes", in.size()).put("keptShares", !newShares.isEmpty()));
    return view(owner, goal, loader.load(owner));
  }

  /**
   * Records that the user keeps direct shares of {@code instrumentId} in a short goal: the goal's
   * tree is saved (from the template if it had none) and the instrument gets its own node with the
   * date of the choice.
   */
  @Transactional
  public void acknowledgeShares(UUID owner, ObjectNode goal, ObjectNode instrument, Loaded l) {
    String planId = goal.path("id").asText();
    if (store.rows(owner, planId).isEmpty()) saveTemplate(owner, goal, l);
    TreeIndex index = store.index(owner, planId);
    AssetTree.Node at = index.place(instrument);
    String now = Instant.now().toString();
    if (at != null && "instrument".equals(at.kind())) {
      records.update("fin_node", owner, UUID.fromString(at.id()), Json.obj().put("userChoiceAckAt", now)
          .put("userChoiceNote", "Kept by your choice"));
      return;
    }
    ObjectNode v = Json.obj().put("planId", planId).put("kind", "instrument").put("nodeKey", instrument.path("id").asText())
        .put("name", instrument.path("name").asText()).put("instrumentId", instrument.path("id").asText())
        .put("userChoiceAckAt", now).put("userChoiceNote", "Kept by your choice").put("sortOrder", 999);
    if (at != null) v.put("parentId", at.id());
    records.insert("fin_node", owner, v);
  }

  /** Whether the user has confirmed keeping these shares in this goal. */
  public boolean acknowledged(UUID owner, String planId, String instrumentId) {
    return store.rows(owner, planId).stream().anyMatch(r -> instrumentId.equals(r.path("instrumentId").asText(null))
        && r.hasNonNull("userChoiceAckAt"));
  }

  private void saveTemplate(UUID owner, ObjectNode goal, Loaded l) {
    Map<String, String> ids = new HashMap<>();
    for (ObjectNode n : template(goal, l)) {
      ObjectNode v = Json.obj().put("planId", goal.path("id").asText()).put("kind", n.path("kind").asText())
          .put("nodeKey", n.path("key").asText()).put("name", n.path("name").asText())
          .put("targetPct", n.path("targetPct").decimalValue()).put("sortOrder", n.path("sortOrder").asInt())
          .put("inRebalancePool", n.path("inRebalancePool").asBoolean(true));
      if (n.hasNonNull("parentId")) v.put("parentId", ids.get(n.get("parentId").asText()));
      ids.put(n.path("id").asText(), records.insert("fin_node", owner, v).path("id").asText());
    }
  }

  // ── allocation and drift ──────────────────────────────────────────────────

  public record AllocationView(String goalId, String goalName, BigDecimal total, LocalDate priceAsOf,
                               boolean treeSaved, List<ObjectNode> rows, List<ObjectNode> offTree,
                               int outsideBand, List<ObjectNode> shortGoalShares) {}

  public AllocationView allocation(UUID caller, UUID goalId) {
    UUID owner = scopes.own(caller).ownerUserId();
    Loaded l = loader.load(owner);
    List<Position> positions = holdings.positions(owner, l.household().asOf());
    if (goalId == null) return wholePicture(owner, positions);
    ObjectNode goal = goal(owner, goalId);
    String planId = goal.path("id").asText();
    TreeView tree = view(owner, goal, l);
    List<AssetTree.Node> nodes = tree.nodes().stream().map(TreeService::plain).toList();
    TreeIndex index = new TreeIndex(nodes);

    Map<String, BigDecimal> direct = new HashMap<>();
    List<ObjectNode> off = new ArrayList<>();
    List<ObjectNode> shortShares = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    LocalDate oldest = null;
    boolean isShort = isShort(goal, l);
    for (Position p : positions) {
      BigDecimal units = com.jizza.platform.usecase.finance.ledger.Earmarks.of(p.claims(), planId);
      if (units.signum() == 0) continue;
      BigDecimal v = p.valueOf(units);
      total = total.add(v);
      if (p.price() != null && (oldest == null || p.price().date().isBefore(oldest))) oldest = p.price().date();
      AssetTree.Node at = p.instrument() == null ? null : index.place(p.instrument());
      if (at == null) {
        off.add(Json.obj().put("instrumentId", p.instrumentId()).put("name", p.instrument() == null ? "" : p.instrument().path("name").asText())
            .put("value", v));
      } else {
        direct.merge(at.id(), v, BigDecimal::add);
      }
      if (isShort && p.instrument() != null && InstrumentService.DIRECT_SHARES.contains(p.instrument().path("type").asText())) {
        shortShares.add(Json.obj().put("instrumentId", p.instrumentId()).put("name", p.instrument().path("name").asText())
            .put("units", units).put("confirmed", acknowledged(owner, planId, p.instrumentId())));
      }
    }
    for (ObjectNode e : records.list("fin_earmark", owner, Map.of("mode", "amount", "planId", planId))) {
      BigDecimal v = e.path("amount").decimalValue();
      total = total.add(v);
      ObjectNode asset = records.get("fin_asset", owner, UUID.fromString(e.path("assetId").asText())).orElse(null);
      if (asset == null) continue;
      String cls = asset.path("assetClass").asText("other");
      String sub = asset.hasNonNull("subCategory") ? asset.get("subCategory").asText()
          : "cash".equals(cls) ? "liquid" : null;
      AssetTree.Node at = index.place(null, cls, sub);
      if (at == null) off.add(Json.obj().put("assetId", asset.path("id").asText()).put("name", asset.path("name").asText()).put("value", v));
      else direct.merge(at.id(), v, BigDecimal::add);
    }

    // Each node's band: its own rebalance-band rule where one is set, else the 5/25 rule.
    Map<String, AssetTree.Band> bands = new HashMap<>();
    if (tree.saved()) {
      var ruleRows = rules.ruleRows(owner);
      JsonNode kai = rules.kaiDefaults(owner);
      for (AssetTree.Node n : nodes) {
        if ("instrument".equals(n.kind())) continue;
        var eff = RuleResolver.resolve(rules.chain(owner, planId, null, n.id(), index, ruleRows, kai)).fields().get("rebalance_band");
        if (eff != null && "set".equals(eff.state()) && eff.value() != null) bands.put(n.id(), band(eff.value()));
      }
    }
    List<AssetTree.Drift> drift = AssetTree.drift(nodes, direct, total, bands);
    Map<String, ObjectNode> byId = new HashMap<>();
    tree.nodes().forEach(n -> byId.put(n.path("id").asText(), n));
    List<ObjectNode> rows = new ArrayList<>();
    int outside = 0;
    boolean pool = !"emergency".equals(goal.path("type").asText());
    for (AssetTree.Drift d : drift) {
      ObjectNode r = Json.obj().put("id", d.id()).put("parentId", d.parentId()).put("kind", d.kind()).put("key", d.key())
          .put("name", d.name()).put("depth", d.depth()).put("actualPct", d.actualPct()).put("value", d.value())
          .put("status", pool ? d.status() : "no_target".equals(d.status()) ? "no_target" : "inside");
      if (d.targetPct() != null) {
        r.put("targetPct", d.targetPct()).put("bandLow", d.bandLow()).put("bandHigh", d.bandHigh())
            .put("bandMode", d.bandMode()).put("driftPts", d.driftPts());
      }
      ObjectNode parent = d.parentId() == null ? null : byId.get(d.parentId());
      r.put("parentName", parent == null ? null : parent.path("name").asText());
      ObjectNode n = byId.get(d.id());
      if (n != null && n.hasNonNull("userChoiceAckAt")) r.put("yourChoice", true);
      if (pool && ("above".equals(d.status()) || "below".equals(d.status()))) outside++;
      rows.add(r);
    }
    return new AllocationView(planId, goal.path("name").asText(), total, oldest, tree.saved(), rows, off, outside, shortShares);
  }

  /** "All goals": the person's whole picture by asset class, without targets. */
  private AllocationView wholePicture(UUID owner, List<Position> positions) {
    Map<String, BigDecimal> byClass = new LinkedHashMap<>();
    BigDecimal total = BigDecimal.ZERO;
    for (Position p : positions) {
      if (p.value() == null) continue;
      String cls = p.instrument() == null ? "other" : p.instrument().path("assetClass").asText("other");
      byClass.merge(cls, p.value(), BigDecimal::add);
      total = total.add(p.value());
    }
    for (ObjectNode a : records.list("fin_asset", owner, Map.of())) {
      BigDecimal v = a.path("value").decimalValue();
      if (v.signum() <= 0) continue;
      byClass.merge(a.path("assetClass").asText("other"), v, BigDecimal::add);
      total = total.add(v);
    }
    List<ObjectNode> rows = new ArrayList<>();
    for (var e : byClass.entrySet()) {
      BigDecimal pct = total.signum() == 0 ? BigDecimal.ZERO : e.getValue().multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
      rows.add(Json.obj().put("id", "class:" + e.getKey()).put("kind", "asset_class").put("key", e.getKey())
          .put("name", AllocationResolver.CLASS_LABELS.getOrDefault(e.getKey(), "Other")).put("depth", 0)
          .put("actualPct", pct).put("value", e.getValue()).put("status", "no_target"));
    }
    return new AllocationView(null, "All goals", total, null, false, rows, List.of(), 0, List.of());
  }

  static AssetTree.Band band(JsonNode v) {
    return switch (v.path("mode").asText()) {
      case "absolute" -> new AssetTree.Band("absolute", v.path("points").decimalValue(), null, null);
      case "relative" -> new AssetTree.Band("relative", null, v.path("pct").decimalValue(), null);
      default -> new AssetTree.Band("five_twenty_five",
          v.has("absolutePts") ? v.get("absolutePts").decimalValue() : BigDecimal.valueOf(5),
          v.has("relativePct") ? v.get("relativePct").decimalValue() : BigDecimal.valueOf(25),
          v.has("largeFromPct") ? v.get("largeFromPct").decimalValue() : BigDecimal.valueOf(20));
    };
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private ObjectNode clean(JsonNode n) {
    String kind = n.path("kind").asText("");
    if (!List.of("asset_class", "sub_category", "instrument").contains(kind)) {
      throw new ValidationException("A node is an asset_class, sub_category or instrument.");
    }
    ObjectNode v = Json.obj().put("id", n.path("id").asText()).put("kind", kind);
    if (n.hasNonNull("parentId") && !n.get("parentId").asText().isBlank()) v.put("parentId", n.get("parentId").asText());
    else v.putNull("parentId");
    String key;
    String name = n.path("name").asText("").trim();
    switch (kind) {
      case "asset_class" -> {
        key = n.path("key").asText("");
        if (!AllocationResolver.CLASS_LABELS.containsKey(key) && !"other".equals(key)) {
          throw new ValidationException("An asset class is cash, debt, equity, gold or other.");
        }
        if (v.hasNonNull("parentId")) throw new ValidationException("An asset class sits at the top of the tree.");
        if (name.isEmpty()) name = AllocationResolver.CLASS_LABELS.getOrDefault(key, "Other");
      }
      case "sub_category" -> {
        key = n.path("key").asText("");
        if (!AllocationResolver.SUB_LABELS.containsKey(key)) throw new ValidationException("Unknown sub-category " + key + ".");
        if (!v.hasNonNull("parentId")) throw new ValidationException("A sub-category sits under an asset class.");
        if (name.isEmpty()) name = AllocationResolver.SUB_LABELS.get(key);
      }
      default -> {
        String ins = n.path("instrumentId").asText("");
        try {
          UUID.fromString(ins);
        } catch (IllegalArgumentException e) {
          throw new ValidationException("An instrument node needs instrumentId.");
        }
        key = ins;
        v.put("instrumentId", ins);
        if (name.isEmpty()) name = market.instrument(UUID.fromString(ins)).map(i -> i.path("name").asText()).orElse("Holding");
      }
    }
    v.put("key", key).put("name", name.length() > 120 ? name.substring(0, 120) : name);
    if (n.hasNonNull("targetPct")) {
      if (!n.get("targetPct").isNumber()) throw new ValidationException("targetPct is a number.");
      v.put("targetPct", n.get("targetPct").decimalValue().setScale(4, RoundingMode.HALF_UP));
    } else if (!"instrument".equals(kind)) {
      throw new ValidationException(name + " needs a target.");
    }
    if (n.has("inRebalancePool")) v.put("inRebalancePool", n.get("inRebalancePool").asBoolean());
    if (n.has("sortOrder")) v.put("sortOrder", n.get("sortOrder").asInt());
    if (n.hasNonNull("userChoiceNote")) {
      String note = n.get("userChoiceNote").asText();
      v.put("userChoiceNote", note.length() > 200 ? note.substring(0, 200) : note);
    }
    return v;
  }

  private static String pathOf(String id, Map<String, ObjectNode> in, int guard) {
    if (guard > 8) throw new ValidationException("The tree goes too deep, or a node is its own parent.");
    ObjectNode n = in.get(id);
    String parent = n.hasNonNull("parentId") ? n.get("parentId").asText() : null;
    if (parent != null && !in.containsKey(parent)) throw new ValidationException("A node's parent is not in the tree.");
    return (parent == null ? "" : pathOf(parent, in, guard + 1)) + "/" + n.path("key").asText();
  }

  private static int depth(String id, Map<String, ObjectNode> in) {
    int d = 0;
    ObjectNode n = in.get(id);
    while (n != null && n.hasNonNull("parentId") && d < 10) {
      n = in.get(n.get("parentId").asText());
      d++;
    }
    return d;
  }

  private static String savedPath(ObjectNode r, Map<String, ObjectNode> byId, int guard) {
    String parent = r.hasNonNull("parentId") ? r.get("parentId").asText() : null;
    ObjectNode p = parent == null ? null : byId.get(parent);
    return (p == null || guard > 8 ? "" : savedPath(p, byId, guard + 1)) + "/" + r.path("nodeKey").asText();
  }

  private static int savedDepth(ObjectNode r, Map<String, ObjectNode> byId) {
    int d = 0;
    ObjectNode n = r;
    while (n != null && n.hasNonNull("parentId") && d < 10) {
      n = byId.get(n.get("parentId").asText());
      d++;
    }
    return d;
  }

  /** A saved node as the screens get it. */
  static ObjectNode out(ObjectNode r) {
    ObjectNode o = Json.obj().put("id", r.path("id").asText()).put("kind", r.path("kind").asText())
        .put("key", r.path("nodeKey").asText()).put("name", r.path("name").asText())
        .put("sortOrder", r.path("sortOrder").asInt()).put("inRebalancePool", r.path("inRebalancePool").asBoolean(true));
    o.set("parentId", r.hasNonNull("parentId") ? r.get("parentId") : null);
    if (o.get("parentId") == null) o.putNull("parentId");
    if (r.hasNonNull("targetPct")) o.put("targetPct", r.get("targetPct").decimalValue());
    else o.putNull("targetPct");
    if (r.hasNonNull("instrumentId")) o.put("instrumentId", r.get("instrumentId").asText());
    if (r.hasNonNull("userChoiceAckAt")) o.put("userChoiceAckAt", r.get("userChoiceAckAt").asText());
    if (r.hasNonNull("userChoiceNote")) o.put("userChoiceNote", r.get("userChoiceNote").asText());
    return o;
  }

  static AssetTree.Node plain(ObjectNode n) {
    return new AssetTree.Node(n.path("id").asText(), n.hasNonNull("parentId") ? n.get("parentId").asText() : null,
        n.path("kind").asText(), n.path("key").asText(), n.path("name").asText(),
        n.hasNonNull("targetPct") ? n.get("targetPct").decimalValue() : null,
        n.hasNonNull("instrumentId") ? n.get("instrumentId").asText() : null);
  }

  private Map<String, String> names(List<ObjectNode> nodes) {
    Set<UUID> ids = new HashSet<>();
    nodes.forEach(n -> {
      if (n.hasNonNull("instrumentId")) ids.add(UUID.fromString(n.get("instrumentId").asText()));
    });
    Map<String, String> out = new HashMap<>();
    market.instruments(ids).forEach(i -> out.put(i.path("id").asText(), i.path("name").asText()));
    return out;
  }

  ObjectNode goal(UUID owner, UUID id) {
    return records.get("fin_plan", owner, id).filter(g -> !HouseholdLoader.isUnassigned(g))
        .orElseThrow(() -> new NotFoundException("Goal", id));
  }
}

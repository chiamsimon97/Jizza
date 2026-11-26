package com.jizza.platform.usecase.finance.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceMarket;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.health.HouseholdLoader;
import com.jizza.platform.usecase.finance.health.HouseholdLoader.Loaded;
import com.jizza.platform.usecase.finance.ledger.Earmarks.Claim;
import com.jizza.platform.usecase.finance.ledger.Earmarks.Delta;
import com.jizza.platform.usecase.finance.ledger.Earmarks.Result;
import com.jizza.platform.usecase.finance.ledger.HoldingsReader.Position;
import com.jizza.platform.usecase.finance.ledger.TaxLotLedger.Lot;
import com.jizza.platform.usecase.finance.market.InstrumentService;
import com.jizza.platform.usecase.finance.market.PriceService;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import com.jizza.platform.usecase.finance.tree.TreeService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holdings and the earmark editor (spec 5.2, screen 7.6): the merged holdings list with each
 * goal's share, one holding's FIFO lots and earmarks, saving a split of units (or of an amount)
 * across goals, and corporate actions entered by hand. The caller's own data only.
 *
 * <p>A split must add up to exactly what the account holds (409 otherwise). Units never move
 * between people: an earmark is always in the person and account of the lots it claims. Nothing
 * here buys or sells.
 */
@Service
public class HoldingsUseCase {

  private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

  private final FinanceRecords records;
  private final FinanceMarket market;
  private final EarmarkService earmarks;
  private final HoldingsReader reader;
  private final TreeService trees;
  private final HouseholdLoader loader;
  private final FinScopeResolver scopes;
  private final AuditLog audit;

  public HoldingsUseCase(FinanceRecords records, FinanceMarket market, EarmarkService earmarks, HoldingsReader reader,
                         TreeService trees, HouseholdLoader loader, FinScopeResolver scopes, AuditLog audit) {
    this.records = records;
    this.market = market;
    this.earmarks = earmarks;
    this.reader = reader;
    this.trees = trees;
    this.loader = loader;
    this.scopes = scopes;
    this.audit = audit;
  }

  // ── the list ──────────────────────────────────────────────────────────────

  /**
   * The holdings list, merged across accounts: per instrument the units, average cost, price and
   * its date, value, gain, holding period, accounts, and each goal's share. Amount-type assets
   * (FDs, balances, EPF…) follow. "Needs a look" lists what Kai cannot place and never guesses.
   */
  public ObjectNode list(UUID caller, UUID personId, UUID goalId) {
    UUID owner = scopes.own(caller).ownerUserId();
    Loaded l = loader.load(owner);
    LocalDate today = l.household().asOf();
    Map<String, ObjectNode> goals = goals(owner);
    Map<String, ObjectNode> accounts = new HashMap<>();
    records.list("fin_account", owner, Map.of()).forEach(a -> accounts.put(a.path("id").asText(), a));
    Map<String, String> people = new HashMap<>();
    l.household().people().forEach(p -> people.put(p.id(), p.name()));
    String person = personId == null ? null : personId.toString();
    String goal = goalId == null ? null : goalId.toString();
    if (goal != null && !goals.containsKey(goal)) throw new NotFoundException("Goal", goalId);

    List<Position> positions = reader.positions(owner, today).stream()
        .filter(p -> person == null || person.equals(p.personId())).toList();
    Map<String, List<Position>> byInstrument = new LinkedHashMap<>();
    for (Position p : positions) byInstrument.computeIfAbsent(p.instrumentId(), k -> new ArrayList<>()).add(p);

    BigDecimal personTotal = BigDecimal.ZERO;
    for (Position p : positions) if (p.value() != null) personTotal = personTotal.add(p.value());
    ArrayNode rows = Json.obj().arrayNode();
    ArrayNode look = Json.obj().arrayNode();
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal cost = BigDecimal.ZERO;
    boolean costKnown = true;
    LocalDate oldestPrice = null;
    for (var e : byInstrument.entrySet()) {
      List<Position> ps = e.getValue();
      ObjectNode ins = ps.get(0).instrument();
      BigDecimal units = BigDecimal.ZERO;
      BigDecimal value = BigDecimal.ZERO;
      BigDecimal rowCost = BigDecimal.ZERO;
      boolean rowCostKnown = true;
      boolean valued = true;
      LocalDate firstBought = null;
      Map<String, BigDecimal> split = new LinkedHashMap<>();
      Map<String, BigDecimal> splitValue = new LinkedHashMap<>();
      ArrayNode accs = Json.obj().arrayNode();
      List<String> reasons = new ArrayList<>();
      for (Position p : ps) {
        units = units.add(p.units());
        if (p.value() == null) valued = false;
        else value = value.add(p.value());
        if (p.avgCost() == null) rowCostKnown = false;
        else rowCost = rowCost.add(p.avgCost().multiply(p.units()));
        for (Lot lot : p.lots()) {
          if (lot.remaining().signum() > 0 && lot.dateKnown() && lot.acquiredOn() != null
              && (firstBought == null || lot.acquiredOn().isBefore(firstBought))) firstBought = lot.acquiredOn();
        }
        for (Claim c : p.claims()) {
          split.merge(c.planId(), c.units(), BigDecimal::add);
          splitValue.merge(c.planId(), p.valueOf(c.units()), BigDecimal::add);
        }
        ObjectNode a = accounts.get(p.accountId());
        accs.addObject().put("accountId", p.accountId()).put("label", accountLabel(a)).put("personId", p.personId())
            .put("personName", people.get(p.personId())).put("units", p.units()).put("value", p.value());
        reasons.addAll(reasons(owner, p));
        if (p.price() != null && (oldestPrice == null || p.price().date().isBefore(oldestPrice))) oldestPrice = p.price().date();
      }
      if (goal != null && !split.containsKey(goal)) continue;
      ObjectNode r = rows.addObject().put("instrumentId", e.getKey());
      instrumentFields(r, ins);
      r.put("units", units).put("value", valued ? value : null);
      if (rowCostKnown && units.signum() > 0) {
        r.put("avgCost", rowCost.divide(units, 4, RoundingMode.HALF_UP)).put("cost", rowCost.setScale(2, RoundingMode.HALF_UP));
        if (valued) {
          BigDecimal pnl = value.subtract(rowCost.setScale(2, RoundingMode.HALF_UP));
          r.put("pnl", pnl);
          if (rowCost.signum() > 0) r.put("pnlPct", pnl.multiply(BigDecimal.valueOf(100)).divide(rowCost, 2, RoundingMode.HALF_UP));
        }
      }
      Position first = ps.get(0);
      if (first.price() != null) r.put("price", first.price().price()).put("priceDate", first.price().date().toString())
          .put("priceSource", first.price().source());
      r.put("valueSource", ps.stream().allMatch(p -> "price".equals(p.valueSource())) ? "price"
          : ps.stream().anyMatch(p -> "none".equals(p.valueSource())) ? "none" : "cost");
      if (firstBought != null) r.put("holdingDays", ChronoUnit.DAYS.between(firstBought, today));
      if (valued && personTotal.signum() > 0) r.put("pctOfTotal", value.multiply(BigDecimal.valueOf(100)).divide(personTotal, 2, RoundingMode.HALF_UP));
      r.set("accounts", accs);
      ArrayNode sp = r.putArray("split");
      BigDecimal unassigned = BigDecimal.ZERO;
      for (var s : split.entrySet()) {
        ObjectNode g = goals.get(s.getKey());
        boolean isU = g == null || HouseholdLoader.isUnassigned(g);
        if (isU) unassigned = unassigned.add(s.getValue());
        ObjectNode x = sp.addObject().put("goalId", s.getKey()).put("name", isU ? "Unassigned" : g.path("name").asText())
            .put("unassigned", isU).put("units", s.getValue()).put("value", splitValue.get(s.getKey()));
        if (g != null && g.hasNonNull("colour")) x.put("colour", g.get("colour").asText());
      }
      r.put("unassignedUnits", unassigned);
      if (goal != null) r.put("goalUnits", split.get(goal)).put("goalValue", splitValue.get(goal));
      ArrayNode rs = r.putArray("needsLook");
      new LinkedHashSet<>(reasons).forEach(rs::add);
      if (!reasons.isEmpty()) {
        look.addObject().put("instrumentId", e.getKey()).put("name", ins == null ? "" : ins.path("name").asText())
            .put("reason", reasons.get(0));
      }
      if (valued) total = total.add(value);
      if (rowCostKnown) cost = cost.add(rowCost);
      else costKnown = false;
    }

    // Amount-type assets and their goals.
    ArrayNode assets = Json.obj().arrayNode();
    Map<String, List<ObjectNode>> assetMarks = new HashMap<>();
    records.list("fin_earmark", owner, Map.of("mode", "amount"))
        .forEach(m -> assetMarks.computeIfAbsent(m.path("assetId").asText(), k -> new ArrayList<>()).add(m));
    for (ObjectNode a : records.list("fin_asset", owner, Map.of())) {
      if (person != null && !person.equals(a.path("personId").asText())) continue;
      BigDecimal v = a.path("value").decimalValue();
      if (v.signum() <= 0 && "other".equals(a.path("kind").asText())) continue;
      List<ObjectNode> marks = assetMarks.getOrDefault(a.path("id").asText(), List.of());
      if (goal != null && marks.stream().noneMatch(m -> goal.equals(m.path("planId").asText()))) continue;
      ObjectNode x = assets.addObject().put("assetId", a.path("id").asText()).put("name", a.path("name").asText(""))
          .put("kind", a.path("kind").asText()).put("assetClass", a.path("assetClass").asText("other"))
          .put("value", v).put("personId", a.path("personId").asText()).put("personName", people.get(a.path("personId").asText()));
      if (a.hasNonNull("valueAsOf")) x.put("valueAsOf", a.get("valueAsOf").asText());
      ArrayNode sp = x.putArray("split");
      BigDecimal claimed = BigDecimal.ZERO;
      for (ObjectNode m : marks) {
        ObjectNode g = goals.get(m.path("planId").asText());
        boolean isU = g == null || HouseholdLoader.isUnassigned(g);
        sp.addObject().put("goalId", m.path("planId").asText()).put("name", isU ? "Unassigned" : g.path("name").asText())
            .put("unassigned", isU).put("amount", m.path("amount").decimalValue());
        claimed = claimed.add(m.path("amount").decimalValue());
      }
      x.put("unassignedAmount", v.subtract(claimed).max(BigDecimal.ZERO));
    }

    ObjectNode out = Json.obj().put("asOf", today.toString());
    if (oldestPrice != null) {
      out.put("priceAsOf", oldestPrice.toString())
          .put("stalePrices", ChronoUnit.DAYS.between(oldestPrice, today) > PriceService.STALE_DAYS);
    }
    out.put("value", total).put("cost", costKnown ? cost.setScale(2, RoundingMode.HALF_UP) : null);
    if (costKnown) out.put("pnl", total.subtract(cost.setScale(2, RoundingMode.HALF_UP)));
    out.set("rows", rows);
    out.set("assets", assets);
    out.set("needsLook", look);
    ArrayNode gs = out.putArray("goals");
    Map<String, BigDecimal> goalValues = reader.goalValues(owner, positions);
    for (ObjectNode g : goals.values()) {
      if (person != null && HouseholdLoader.isUnassigned(g) && !person.equals(g.path("ownerPersonId").asText())) continue;
      ObjectNode x = gs.addObject().put("id", g.path("id").asText()).put("name", HouseholdLoader.isUnassigned(g) ? "Unassigned" : g.path("name").asText())
          .put("type", g.path("type").asText()).put("unassigned", HouseholdLoader.isUnassigned(g))
          .put("value", goalValues.getOrDefault(g.path("id").asText(), BigDecimal.ZERO));
      if (g.hasNonNull("colour")) x.put("colour", g.get("colour").asText());
      if (g.hasNonNull("ownerPersonId")) x.put("ownerPersonId", g.get("ownerPersonId").asText());
    }
    return out;
  }

  /** Why a holding needs a look: never a guess, always a plain reason. */
  List<String> reasons(UUID owner, Position p) {
    List<String> out = new ArrayList<>();
    if (p.claimed().compareTo(p.units()) != 0) out.add("Its goals no longer add up to the units held.");
    List<ObjectNode> events = records.list("fin_earmark_event", owner, Map.of("accountId", p.accountId(), "instrumentId", p.instrumentId()));
    ObjectNode last = null;
    for (ObjectNode ev : events) if (!"buy".equals(ev.path("kind").asText())) last = ev;
    if (last != null && "external_sell".equals(last.path("kind").asText())) {
      out.add("Units were sold outside Kai. Check which goal the sale came from.");
    }
    if (p.lots().stream().anyMatch(l -> l.remaining().signum() > 0 && !l.dateKnown())) {
      out.add("Some units have no purchase date, so their tax term is unknown. Import the earlier statements or a tradebook.");
    }
    if (p.instrument() != null && !p.instrument().hasNonNull("subCategory")) {
      out.add("Kai can't tell which sub-category this belongs to.");
    }
    if (p.price() == null) out.add("No price yet.");
    return out;
  }

  // ── one holding ───────────────────────────────────────────────────────────

  /** One account's holding of one instrument: its FIFO lots in sale order, earmarks and recent history. */
  public ObjectNode holding(UUID caller, UUID accountId, UUID instrumentId) {
    UUID owner = scopes.own(caller).ownerUserId();
    Loaded l = loader.load(owner);
    ObjectNode account = records.get("fin_account", owner, accountId).orElseThrow(() -> new NotFoundException("Account", accountId));
    ObjectNode ins = market.instrument(instrumentId).orElseThrow(() -> new NotFoundException("Instrument", instrumentId));
    Position p = reader.positions(owner, l.household().asOf()).stream()
        .filter(x -> x.accountId().equals(accountId.toString()) && x.instrumentId().equals(instrumentId.toString()))
        .findFirst().orElseThrow(() -> new NotFoundException("Holding", instrumentId));
    Map<String, ObjectNode> goals = goals(owner);
    ObjectNode out = Json.obj().put("accountId", accountId.toString()).put("accountLabel", accountLabel(account))
        .put("personId", p.personId()).put("instrumentId", instrumentId.toString());
    instrumentFields(out, ins);
    out.put("units", p.units()).put("value", p.value()).put("valueSource", p.valueSource());
    if (p.avgCost() != null) out.put("avgCost", p.avgCost());
    if (p.price() != null) out.put("price", p.price().price()).put("priceDate", p.price().date().toString());
    var rules = earmarks.termRules(owner, ins);
    ArrayNode lots = out.putArray("lots");
    Lot firstLeft = null;
    for (Lot lot : TaxLotLedger.fifo(p.lots())) {
      if (lot.remaining().signum() == 0) continue;
      if (firstLeft == null) firstLeft = lot;
      ObjectNode x = lots.addObject().put("id", lot.id()).put("acquiredOn", lot.acquiredOn() == null ? null : lot.acquiredOn().toString())
          .put("dateKnown", lot.dateKnown()).put("qty", lot.qty()).put("remaining", lot.remaining())
          .put("costPerUnit", lot.costPerUnit()).put("termToday", TaxLotLedger.term(lot, l.household().asOf(), rules.apply(lot)));
      if (lot.costPerUnit() != null && p.price() != null) {
        x.put("gainToday", p.price().price().subtract(lot.costPerUnit()).multiply(lot.remaining()).setScale(2, RoundingMode.HALF_UP));
      }
    }
    if (firstLeft != null) {
      out.put("saleNote", firstLeft.dateKnown() && firstLeft.acquiredOn() != null
          ? "A sale here uses the " + firstLeft.acquiredOn().format(MONTH_YEAR) + " lot first."
          : "A sale here uses the units with no purchase date first.");
    }
    ArrayNode es = out.putArray("earmarks");
    boolean shortGoalCheck = InstrumentService.DIRECT_SHARES.contains(ins.path("type").asText());
    for (Claim c : p.claims()) {
      ObjectNode g = goals.get(c.planId());
      boolean isU = g == null || HouseholdLoader.isUnassigned(g);
      ObjectNode x = es.addObject().put("goalId", c.planId()).put("name", isU ? "Unassigned" : g.path("name").asText())
          .put("unassigned", isU).put("units", c.units()).put("value", p.valueOf(c.units()));
      if (!isU && shortGoalCheck && trees.isShort(g, l)) {
        x.put("shortGoal", true).put("yourChoice", trees.acknowledged(owner, c.planId(), instrumentId.toString()));
      }
    }
    ArrayNode choices = out.putArray("goals");
    for (ObjectNode g : goals.values()) {
      boolean isU = HouseholdLoader.isUnassigned(g);
      if (isU && !p.personId().equals(g.path("ownerPersonId").asText())) continue;
      if (!isU && !List.of("active", "paused", "draft").contains(g.path("status").asText())) continue;
      ObjectNode x = choices.addObject().put("id", g.path("id").asText()).put("name", isU ? "Unassigned" : g.path("name").asText())
          .put("unassigned", isU);
      if (!isU) x.put("short", trees.isShort(g, l)).put("yourChoice", trees.acknowledged(owner, g.path("id").asText(), instrumentId.toString()));
      if (g.hasNonNull("colour")) x.put("colour", g.get("colour").asText());
    }
    out.put("directShares", shortGoalCheck);
    ArrayNode history = out.putArray("history");
    List<ObjectNode> events = new ArrayList<>(records.list("fin_earmark_event", owner,
        Map.of("accountId", accountId.toString(), "instrumentId", instrumentId.toString())));
    events.sort(Comparator.comparing((ObjectNode e) -> e.path("occurredAt").asText()).reversed());
    for (ObjectNode ev : events.subList(0, Math.min(20, events.size()))) {
      ObjectNode g = goals.get(ev.path("planId").asText());
      history.addObject().put("kind", ev.path("kind").asText()).put("goal", g == null || HouseholdLoader.isUnassigned(g) ? "Unassigned" : g.path("name").asText())
          .put("deltaUnits", ev.path("deltaUnits").decimalValue()).put("at", ev.path("occurredAt").asText())
          .put("note", ev.path("note").asText(null));
    }
    ArrayNode rs = out.putArray("needsLook");
    reasons(owner, p).forEach(rs::add);
    return out;
  }

  // ── saving a split ────────────────────────────────────────────────────────

  /**
   * Saves how a holding's units (or an asset's amount) are split across goals. Every goal named
   * must be the caller's; the split must add up to exactly what is held. Putting direct shares in
   * a goal under three years away needs {@code keepShares} (409 with the warning otherwise).
   */
  @Transactional
  public ObjectNode put(UUID caller, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    if (body == null || !body.isObject()) throw new ValidationException("Send the split as an object.");
    if (body.hasNonNull("assetId")) return putAmounts(caller, owner, body);
    UUID accountId = id(body, "accountId");
    UUID instrumentId = id(body, "instrumentId");
    ObjectNode account = records.get("fin_account", owner, accountId).orElseThrow(() -> new NotFoundException("Account", accountId));
    ObjectNode ins = market.instrument(instrumentId).orElseThrow(() -> new NotFoundException("Instrument", instrumentId));
    String personId = account.path("personId").asText();
    Map<String, ObjectNode> goals = goals(owner);
    List<Claim> split = split(body, goals, "units", TaxLotLedger.UNITS);
    BigDecimal held = TaxLotLedger.remaining(EarmarkService.lots(earmarks.lotRows(owner, accountId, instrumentId)));
    if (held.signum() == 0) throw new NotFoundException("Holding", instrumentId);
    String problem = Earmarks.problem(split, held);
    if (problem != null) throw new ConflictException(problem);
    for (Claim c : split) {
      ObjectNode g = goals.get(c.planId());
      if (HouseholdLoader.isUnassigned(g) && !personId.equals(g.path("ownerPersonId").asText())) {
        throw new ValidationException("Units can only stay unassigned with the person who holds them.");
      }
    }
    List<ObjectNode> rows = earmarks.earmarkRows(owner, accountId, instrumentId);
    List<Claim> before = EarmarkService.claims(rows);

    // The short-goal rule.
    Loaded l = loader.load(owner);
    boolean keepShares = body.path("keepShares").asBoolean(false);
    List<ObjectNode> needAck = new ArrayList<>();
    if (InstrumentService.DIRECT_SHARES.contains(ins.path("type").asText())) {
      for (Claim c : split) {
        ObjectNode g = goals.get(c.planId());
        if (HouseholdLoader.isUnassigned(g) || !trees.isShort(g, l)) continue;
        if (c.units().compareTo(Earmarks.of(before, c.planId())) <= 0) continue;
        if (!trees.acknowledged(owner, c.planId(), instrumentId.toString())) needAck.add(g);
      }
    }
    if (!needAck.isEmpty() && !keepShares) throw TreeService.shortGoalWarning();
    for (ObjectNode g : needAck) trees.acknowledgeShares(owner, g, ins, l);

    // Unassigned holds nothing that a goal now claims: the new split is the whole truth.
    List<Delta> deltas = new ArrayList<>();
    Set<String> plans = new LinkedHashSet<>();
    before.forEach(c -> plans.add(c.planId()));
    split.forEach(c -> plans.add(c.planId()));
    for (String p : plans) {
      BigDecimal d = Earmarks.of(split, p).subtract(Earmarks.of(before, p));
      if (d.signum() != 0) deltas.add(new Delta(p, d));
    }
    earmarks.store(owner, personId, accountId.toString(), instrumentId.toString(), rows,
        new Result(Earmarks.clean(split), deltas), "assign", null, null, null);
    audit.record(owner, ActorType.USER, caller, "finance.earmarks.saved", instrumentId.toString(),
        Json.obj().put("goals", split.size()));
    return holding(caller, accountId, instrumentId);
  }

  private ObjectNode putAmounts(UUID caller, UUID owner, JsonNode body) {
    UUID assetId = id(body, "assetId");
    ObjectNode asset = records.get("fin_asset", owner, assetId).orElseThrow(() -> new NotFoundException("Asset", assetId));
    Map<String, ObjectNode> goals = goals(owner);
    List<Claim> split = split(body, goals, "amount", TaxLotLedger.MONEY);
    BigDecimal value = asset.path("value").decimalValue().setScale(TaxLotLedger.UNITS, RoundingMode.HALF_UP);
    BigDecimal sum = Earmarks.total(split);
    if (sum.compareTo(value) != 0) {
      throw new ConflictException("The goals add up to " + com.jizza.platform.usecase.finance.model.Inr.group(sum)
          + "; the asset is worth " + com.jizza.platform.usecase.finance.model.Inr.group(value) + ".");
    }
    List<ObjectNode> rows = earmarks.assetEarmarkRows(owner, assetId);
    List<Claim> before = EarmarkService.amountClaims(rows);
    List<Delta> deltas = new ArrayList<>();
    Set<String> plans = new LinkedHashSet<>();
    before.forEach(c -> plans.add(c.planId()));
    split.forEach(c -> plans.add(c.planId()));
    for (String p : plans) {
      BigDecimal d = Earmarks.of(split, p).subtract(Earmarks.of(before, p));
      if (d.signum() != 0) deltas.add(new Delta(p, d));
    }
    earmarks.storeAmounts(owner, asset, rows, new Result(Earmarks.clean(split), deltas), "assign", null);
    audit.record(owner, ActorType.USER, caller, "finance.earmarks.saved", assetId.toString(), Json.obj().put("goals", split.size()));
    ObjectNode out = Json.obj().put("assetId", assetId.toString()).put("value", asset.path("value").decimalValue());
    ArrayNode sp = out.putArray("split");
    for (ObjectNode m : earmarks.assetEarmarkRows(owner, assetId)) {
      sp.addObject().put("goalId", m.path("planId").asText()).put("amount", m.path("amount").decimalValue());
    }
    return out;
  }

  private List<Claim> split(JsonNode body, Map<String, ObjectNode> goals, String field, int scale) {
    if (!body.path("split").isArray() || body.path("split").isEmpty()) throw new ValidationException("Send the split: [{goalId, " + field + "}].");
    List<Claim> out = new ArrayList<>();
    for (JsonNode s : body.path("split")) {
      UUID g = id(s, "goalId");
      ObjectNode goal = goals.get(g.toString());
      if (goal == null) throw new NotFoundException("Goal", g);
      if (!HouseholdLoader.isUnassigned(goal) && !List.of("active", "paused", "draft").contains(goal.path("status").asText())) {
        throw new ValidationException(goal.path("name").asText() + " is " + goal.path("status").asText() + "; choose a live goal.");
      }
      JsonNode v = s.get(field);
      if (v == null || !v.isNumber()) throw new ValidationException("Each goal needs its " + field + ".");
      BigDecimal d = v.decimalValue();
      if (d.signum() < 0) throw new ValidationException("A goal's " + field + " cannot be negative.");
      if (d.stripTrailingZeros().scale() > scale) throw new ValidationException(field + " go to " + scale + " decimals at most.");
      out.add(new Claim(g.toString(), d.setScale(TaxLotLedger.UNITS, RoundingMode.UNNECESSARY)));
    }
    String dup = Earmarks.problem(out.stream().map(c -> new Claim(c.planId(), BigDecimal.ZERO)).toList(), BigDecimal.ZERO);
    if ("Each goal appears once.".equals(dup)) throw new ValidationException(dup);
    return out;
  }

  // ── corporate actions ─────────────────────────────────────────────────────

  /**
   * A split or bonus the user enters (spec 5.2): every lot of theirs bought before the ex-date is
   * scaled (a split) or joined by a bonus lot dated the ex-date at no cost, and every earmark
   * follows by the same ratio. Mergers, demergers and buybacks are entered from a later phase.
   */
  @Transactional
  public ObjectNode corporateAction(UUID caller, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    if (body == null || !body.isObject()) throw new ValidationException("Send the corporate action.");
    UUID instrumentId = id(body, "instrumentId");
    ObjectNode ins = market.instrument(instrumentId).orElseThrow(() -> new NotFoundException("Instrument", instrumentId));
    String kind = body.path("kind").asText("");
    if (!List.of("split", "bonus").contains(kind)) {
      throw new ValidationException("Enter a split or a bonus here. Mergers, demergers and buybacks come later.");
    }
    LocalDate ex;
    try {
      ex = LocalDate.parse(body.path("exDate").asText(""));
    } catch (RuntimeException e) {
      throw new ValidationException("exDate is a date (YYYY-MM-DD).");
    }
    BigDecimal num = body.path("ratioNum").decimalValue();
    BigDecimal den = body.path("ratioDen").decimalValue();
    if (!body.path("ratioNum").isNumber() || !body.path("ratioDen").isNumber() || num.signum() <= 0 || den.signum() <= 0) {
      throw new ValidationException("Give the ratio as two numbers above zero (5 for 1 is ratioNum 5, ratioDen 1).");
    }
    if ("split".equals(kind) && num.compareTo(den) == 0) throw new ValidationException("A 1 for 1 split changes nothing.");
    for (ObjectNode a : market.corporateActions(owner, instrumentId)) {
      if (a.path("kind").asText().equals(kind) && a.path("exDate").asText().equals(ex.toString())) {
        throw new ConflictException("This " + kind + " on " + ex + " is already recorded.");
      }
    }
    ObjectNode action = market.insertCorporateAction(owner, Json.obj().put("instrumentId", instrumentId.toString())
        .put("kind", kind).put("exDate", ex.toString()).put("ratioNum", num).put("ratioDen", den).put("source", "manual"));

    Map<String, List<ObjectNode>> byAccount = new LinkedHashMap<>();
    for (ObjectNode lot : records.list("fin_holding_lot", owner, Map.of("instrumentId", instrumentId.toString()))) {
      byAccount.computeIfAbsent(lot.path("accountId").asText(), k -> new ArrayList<>()).add(lot);
    }
    int affected = 0;
    String note = ("split".equals(kind) ? "Split " : "Bonus ") + plain(num) + ":" + plain(den);
    for (var e : byAccount.entrySet()) {
      boolean changed = false;
      String personId = e.getValue().get(0).path("personId").asText();
      for (ObjectNode lot : e.getValue()) {
        boolean before = !lot.path("acquiredOnKnown").asBoolean(true) || !lot.hasNonNull("acquiredOn")
            || LocalDate.parse(lot.get("acquiredOn").asText()).isBefore(ex);
        BigDecimal remaining = lot.path("remainingQty").decimalValue();
        if (!before || remaining.signum() == 0) continue;
        changed = true;
        if ("split".equals(kind)) {
          ObjectNode v = Json.obj()
              .put("qty", lot.path("qty").decimalValue().multiply(num).divide(den, TaxLotLedger.UNITS, RoundingMode.DOWN))
              .put("remainingQty", remaining.multiply(num).divide(den, TaxLotLedger.UNITS, RoundingMode.DOWN));
          if (lot.hasNonNull("costPerUnit")) {
            v.put("costPerUnit", lot.get("costPerUnit").decimalValue().multiply(den).divide(num, 4, RoundingMode.HALF_UP));
          }
          records.update("fin_holding_lot", owner, UUID.fromString(lot.path("id").asText()), v);
        } else {
          BigDecimal bonus = remaining.multiply(num).divide(den, TaxLotLedger.UNITS, RoundingMode.DOWN);
          if (bonus.signum() == 0) continue;
          records.insert("fin_holding_lot", owner, Json.obj().put("personId", personId).put("accountId", e.getKey())
              .put("instrumentId", instrumentId.toString()).put("acquiredOn", ex.toString()).put("acquiredOnKnown", true)
              .put("qty", bonus).put("remainingQty", bonus).put("costPerUnit", 0).put("source", "bonus"));
        }
      }
      if (!changed) continue;
      List<ObjectNode> rows = earmarks.earmarkRows(owner, UUID.fromString(e.getKey()), instrumentId);
      BigDecimal held = TaxLotLedger.remaining(EarmarkService.lots(earmarks.lotRows(owner, UUID.fromString(e.getKey()), instrumentId)));
      List<Claim> before = EarmarkService.claims(rows);
      Result r = before.isEmpty() ? Earmarks.reconcile(before, held, earmarks.unassigned(owner, personId)) : Earmarks.scale(before, held);
      earmarks.store(owner, personId, e.getKey(), instrumentId.toString(), rows, r, "corp_action", null, note, null);
      affected += r.claims().size();
    }
    audit.record(owner, ActorType.USER, caller, "finance.corporate_action.entered", action.path("id").asText(),
        Json.obj().put("kind", kind));
    return Json.obj().put("id", action.path("id").asText()).put("instrumentId", instrumentId.toString())
        .put("name", ins.path("name").asText()).put("kind", kind).put("exDate", ex.toString())
        .put("affectedEarmarks", affected);
  }

  // ── purchases and sales typed in ──────────────────────────────────────────

  /**
   * A purchase the user records by hand (no broker file, or a holding promoted from a list): a
   * lot, and the units go to the goal named (else Unassigned). Kai places nothing.
   */
  @Transactional
  public ObjectNode purchase(UUID caller, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    Trade t = trade(owner, body, "pricePerUnit");
    if (t.goalId() != null) {
      ObjectNode g = goals(owner).get(t.goalId());
      Loaded l = loader.load(owner);
      if (InstrumentService.DIRECT_SHARES.contains(t.instrument().path("type").asText()) && trees.isShort(g, l)
          && !trees.acknowledged(owner, t.goalId(), t.instrumentId().toString())) {
        if (!body.path("keepShares").asBoolean(false)) throw TreeService.shortGoalWarning();
        trees.acknowledgeShares(owner, g, t.instrument(), l);
      }
    }
    earmarks.recordBuy(owner, t.personId(), t.accountId().toString(), t.instrumentId().toString(), t.date(), true,
        t.units(), t.money(), "buy", null, t.goalId(), null);
    audit.record(owner, ActorType.USER, caller, "finance.purchase.recorded", t.instrumentId().toString(), null);
    return holding(caller, t.accountId(), t.instrumentId());
  }

  /**
   * A sale the user records by hand. With {@code goalId} it is that goal's sale (its units fall);
   * without, the units come off Unassigned first, then the goals pro rata. The gain is always the
   * FIFO lots' and is attributed to the selling goal.
   */
  @Transactional
  public ObjectNode sale(UUID caller, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    Trade t = trade(owner, body, "proceeds");
    BigDecimal held = TaxLotLedger.remaining(EarmarkService.lots(earmarks.lotRows(owner, t.accountId(), t.instrumentId())));
    if (held.compareTo(t.units()) < 0) {
      throw new ConflictException("The account holds " + plain(held) + " units, fewer than the " + plain(t.units()) + " sold.");
    }
    if (t.goalId() != null) {
      BigDecimal has = Earmarks.of(EarmarkService.claims(earmarks.earmarkRows(owner, t.accountId(), t.instrumentId())), t.goalId());
      if (has.compareTo(t.units()) < 0) {
        throw new ConflictException("That goal has " + plain(has) + " units here, fewer than the " + plain(t.units()) + " sold.");
      }
    }
    var done = earmarks.recordSell(owner, t.personId(), t.accountId().toString(), t.instrumentId().toString(), t.date(),
        t.units(), t.money(), t.goalId(), null, null, null);
    audit.record(owner, ActorType.USER, caller, "finance.sale.recorded", t.instrumentId().toString(), null);
    ObjectNode out = Json.obj();
    ArrayNode parts = out.putArray("parts");
    for (var part : done.sale().parts()) {
      parts.addObject().put("lotId", part.lotId()).put("acquiredOn", part.acquiredOn() == null ? null : part.acquiredOn().toString())
          .put("qty", part.qty()).put("proceeds", part.proceeds()).put("cost", part.cost()).put("gain", part.gain())
          .put("term", part.term());
    }
    out.put("gain", done.sale().gain()).put("proceeds", done.sale().proceeds());
    out.set("holding", holdingOrEmpty(caller, t.accountId(), t.instrumentId()));
    return out;
  }

  private ObjectNode holdingOrEmpty(UUID caller, UUID accountId, UUID instrumentId) {
    try {
      return holding(caller, accountId, instrumentId);
    } catch (NotFoundException gone) {
      return Json.obj().put("accountId", accountId.toString()).put("instrumentId", instrumentId.toString()).put("units", 0);
    }
  }

  record Trade(UUID accountId, String personId, UUID instrumentId, ObjectNode instrument, LocalDate date,
               BigDecimal units, BigDecimal money, String goalId) {}

  private Trade trade(UUID owner, JsonNode body, String moneyField) {
    if (body == null || !body.isObject()) throw new ValidationException("Send the trade as an object.");
    UUID accountId = id(body, "accountId");
    UUID instrumentId = id(body, "instrumentId");
    ObjectNode account = records.get("fin_account", owner, accountId).orElseThrow(() -> new NotFoundException("Account", accountId));
    if (!List.of("demat", "mf_folio", "us_broker", "retirement", "other").contains(account.path("kind").asText())) {
      throw new ValidationException("Units are held in a demat, fund folio or broker account.");
    }
    ObjectNode ins = market.instrument(instrumentId).orElseThrow(() -> new NotFoundException("Instrument", instrumentId));
    LocalDate date;
    try {
      date = LocalDate.parse(body.path("date").asText(""));
    } catch (RuntimeException e) {
      throw new ValidationException("date is a date (YYYY-MM-DD).");
    }
    if (date.isAfter(HouseholdLoader.today())) throw new ValidationException("The date cannot be in the future.");
    JsonNode u = body.get("units");
    if (u == null || !u.isNumber() || u.decimalValue().signum() <= 0) throw new ValidationException("units are above zero.");
    if (u.decimalValue().stripTrailingZeros().scale() > TaxLotLedger.UNITS) throw new ValidationException("Units go to six decimals at most.");
    JsonNode m = body.get(moneyField);
    if (m == null || !m.isNumber() || m.decimalValue().signum() < 0) throw new ValidationException(moneyField + " is zero or more.");
    String goalId = null;
    if (body.hasNonNull("goalId") && !body.get("goalId").asText().isBlank()) {
      UUID g = id(body, "goalId");
      ObjectNode goal = goals(owner).get(g.toString());
      if (goal == null || HouseholdLoader.isUnassigned(goal)) throw new NotFoundException("Goal", g);
      goalId = g.toString();
    }
    return new Trade(accountId, account.path("personId").asText(), instrumentId, ins, date, u.decimalValue(),
        m.decimalValue(), goalId);
  }

  /** The realised gains of a financial year, per sale part, with the goal each belongs to. */
  public ObjectNode realisedGains(UUID caller, String taxYear) {
    UUID owner = scopes.own(caller).ownerUserId();
    String year = taxYear == null || taxYear.isBlank() ? TaxLotLedger.taxYear(HouseholdLoader.today()) : taxYear;
    if (!year.matches("\\d{4}-\\d{2}")) throw new ValidationException("taxYear is like 2026-27.");
    Map<String, ObjectNode> goals = goals(owner);
    List<ObjectNode> rows = records.list("fin_realized_gain", owner, Map.of("taxYear", year));
    Set<String> reversed = new LinkedHashSet<>();
    rows.forEach(r -> {
      if (r.hasNonNull("reversesId")) reversed.add(r.get("reversesId").asText());
    });
    ArrayNode out = Json.obj().arrayNode();
    BigDecimal st = BigDecimal.ZERO;
    BigDecimal lt = BigDecimal.ZERO;
    Map<String, String> names = new HashMap<>();
    for (ObjectNode r : rows) {
      if (r.hasNonNull("reversesId") || reversed.contains(r.path("id").asText())) continue;
      String insId = r.path("instrumentId").asText();
      String name = names.computeIfAbsent(insId, k -> market.instrument(UUID.fromString(k)).map(i -> i.path("name").asText()).orElse(""));
      ObjectNode g = r.hasNonNull("planId") ? goals.get(r.get("planId").asText()) : null;
      out.addObject().put("id", r.path("id").asText()).put("instrumentId", insId).put("name", name)
          .put("soldOn", r.path("soldOn").asText()).put("qty", r.path("qty").decimalValue())
          .put("proceeds", r.path("proceeds").decimalValue()).put("cost", r.hasNonNull("cost") ? r.get("cost").decimalValue() : null)
          .put("gain", r.hasNonNull("gain") ? r.get("gain").decimalValue() : null).put("term", r.path("term").asText(null))
          .put("taxClass", r.path("taxClass").asText()).put("personId", r.path("personId").asText())
          .put("goal", g == null ? null : HouseholdLoader.isUnassigned(g) ? "Unassigned" : g.path("name").asText());
      if (r.hasNonNull("gain")) {
        if ("lt".equals(r.path("term").asText())) lt = lt.add(r.get("gain").decimalValue());
        else if ("st".equals(r.path("term").asText())) st = st.add(r.get("gain").decimalValue());
      }
    }
    ObjectNode res = Json.obj().put("taxYear", year).put("shortTerm", st).put("longTerm", lt);
    res.set("rows", out);
    return res;
  }

  // ── goals that end ────────────────────────────────────────────────────────

  /**
   * An achieved or archived goal lets go of its earmarks: to the successor goal when the user
   * chose one, else to Unassigned. Nothing is sold.
   */
  @Transactional
  public int release(UUID owner, String planId, String successorId) {
    int moved = 0;
    for (ObjectNode e : records.list("fin_earmark", owner, Map.of("planId", planId))) {
      String to = successorId != null ? successorId : earmarks.unassigned(owner, e.path("personId").asText());
      if ("units".equals(e.path("mode").asText())) {
        UUID acc = UUID.fromString(e.path("accountId").asText());
        UUID ins = UUID.fromString(e.path("instrumentId").asText());
        List<ObjectNode> rows = earmarks.earmarkRows(owner, acc, ins);
        Result r = Earmarks.reassign(EarmarkService.claims(rows), planId, to, e.path("units").decimalValue()
            .setScale(TaxLotLedger.UNITS, RoundingMode.UNNECESSARY));
        earmarks.store(owner, e.path("personId").asText(), acc.toString(), ins.toString(), rows, r, "reassign", null,
            "The goal ended", null);
      } else {
        UUID assetId = UUID.fromString(e.path("assetId").asText());
        List<ObjectNode> rows = earmarks.assetEarmarkRows(owner, assetId);
        Result r = Earmarks.reassign(EarmarkService.amountClaims(rows), planId, to, e.path("amount").decimalValue()
            .setScale(TaxLotLedger.UNITS, RoundingMode.UNNECESSARY));
        records.get("fin_asset", owner, assetId).ifPresent(a -> earmarks.storeAmounts(owner, a, rows, r, "reassign", "The goal ended"));
      }
      moved++;
    }
    return moved;
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  Map<String, ObjectNode> goals(UUID owner) {
    Map<String, ObjectNode> out = new LinkedHashMap<>();
    records.list("fin_plan", owner, Map.of()).forEach(g -> out.put(g.path("id").asText(), g));
    return out;
  }

  static void instrumentFields(ObjectNode r, ObjectNode ins) {
    if (ins == null) return;
    r.put("name", ins.path("name").asText()).put("type", ins.path("type").asText())
        .put("assetClass", ins.path("assetClass").asText("other"))
        .put("subCategory", ins.path("subCategory").asText(null)).put("symbol", ins.path("symbol").asText(null))
        .put("isin", ins.path("isin").asText(null)).put("capBucket", ins.path("capBucket").asText(null));
  }

  static String accountLabel(ObjectNode a) {
    if (a == null) return "Account";
    String nick = a.path("nickname").asText("");
    String provider = a.path("provider").asText("");
    String last4 = a.path("last4").asText("");
    String base = !nick.isBlank() ? nick : !provider.isBlank() ? provider : a.path("kind").asText("account").replace('_', ' ');
    return last4.isBlank() || base.contains(last4) ? base : base + " ··" + last4;
  }

  private static UUID id(JsonNode b, String f) {
    try {
      return UUID.fromString(b.path(f).asText(""));
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Send " + f + ".");
    }
  }

  static String plain(BigDecimal v) {
    BigDecimal s = v.stripTrailingZeros();
    return s.scale() < 0 ? s.setScale(0).toPlainString() : s.toPlainString();
  }
}

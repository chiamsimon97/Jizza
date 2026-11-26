package com.jizza.platform.usecase.finance.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceMarket;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.config.FinConfigService;
import com.jizza.platform.usecase.finance.health.HouseholdLoader;
import com.jizza.platform.usecase.finance.ledger.Earmarks.Claim;
import com.jizza.platform.usecase.finance.ledger.Earmarks.Delta;
import com.jizza.platform.usecase.finance.ledger.Earmarks.Result;
import com.jizza.platform.usecase.finance.ledger.TaxLotLedger.Lot;
import com.jizza.platform.usecase.finance.ledger.TaxLotLedger.Sale;
import com.jizza.platform.usecase.finance.ledger.TaxLotLedger.TermRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two ledgers in the database (spec 5.2). Every write goes through here and keeps invariant 1
 * and 2: for each person, account and instrument the goals' units add up to the units the lots
 * still hold, any gap sits in that person's Unassigned goal, and an earmark is always in the
 * person and account of the lots it claims. Every change to a claim writes an append-only event;
 * every sale writes append-only realised-gain rows from the FIFO lots it consumed.
 *
 * <p>Callers have already passed the scope resolver; every method takes the owner's user id.
 * Nothing here logs an amount, a name or a unit count.
 */
@Service
public class EarmarkService {

  public static final String UNASSIGNED = "unassigned";

  private final FinanceRecords records;
  private final FinanceMarket market;
  private final FinConfigService config;

  public EarmarkService(FinanceRecords records, FinanceMarket market, FinConfigService config) {
    this.records = records;
    this.market = market;
    this.config = config;
  }

  /** What one batch of ledger writes changed, so an import can be undone exactly. */
  public static final class Log {
    final Map<String, BigDecimal> lotsBefore = new LinkedHashMap<>();
    final Set<String> createdLots = new LinkedHashSet<>();
    final List<String> gains = new ArrayList<>();
    /** "account|instrument" → the claims before the first change. */
    final Map<String, List<Claim>> claimsBefore = new LinkedHashMap<>();
    final Map<String, String> personOf = new HashMap<>();

    public boolean isEmpty() {
      return lotsBefore.isEmpty() && createdLots.isEmpty() && gains.isEmpty() && claimsBefore.isEmpty();
    }

    /** The holdings touched, as "account|instrument" keys. */
    public Set<String> holdings() {
      return claimsBefore.keySet();
    }

    public ObjectNode toJson() {
      ObjectNode o = Json.obj();
      ObjectNode lots = o.putObject("lotsBefore");
      lotsBefore.forEach((k, v) -> lots.put(k, v));
      ArrayNode created = o.putArray("createdLots");
      createdLots.forEach(created::add);
      ArrayNode g = o.putArray("gains");
      gains.forEach(g::add);
      ArrayNode h = o.putArray("holdings");
      claimsBefore.forEach((k, claims) -> {
        String[] parts = k.split("\\|");
        ObjectNode n = h.addObject().put("accountId", parts[0]).put("instrumentId", parts[1])
            .put("personId", personOf.get(k));
        ArrayNode cs = n.putArray("claims");
        claims.forEach(c -> cs.addObject().put("planId", c.planId()).put("units", c.units()));
      });
      return o;
    }
  }

  // ── reads ─────────────────────────────────────────────────────────────────

  public List<ObjectNode> lotRows(UUID owner, UUID accountId, UUID instrumentId) {
    return records.list("fin_holding_lot", owner, Map.of("accountId", accountId.toString(),
        "instrumentId", instrumentId.toString()));
  }

  public static List<Lot> lots(List<ObjectNode> rows) {
    List<Lot> out = new ArrayList<>();
    for (ObjectNode r : rows) {
      out.add(new Lot(r.path("id").asText(),
          r.hasNonNull("acquiredOn") ? LocalDate.parse(r.get("acquiredOn").asText()) : null,
          r.path("acquiredOnKnown").asBoolean(true), r.path("qty").decimalValue(),
          r.path("remainingQty").decimalValue(),
          r.hasNonNull("costPerUnit") ? r.get("costPerUnit").decimalValue() : null));
    }
    return out;
  }

  public List<ObjectNode> earmarkRows(UUID owner, UUID accountId, UUID instrumentId) {
    return records.list("fin_earmark", owner, Map.of("accountId", accountId.toString(),
        "instrumentId", instrumentId.toString()));
  }

  public static List<Claim> claims(List<ObjectNode> rows) {
    List<Claim> out = new ArrayList<>();
    for (ObjectNode r : rows) {
      if (!"units".equals(r.path("mode").asText())) continue;
      BigDecimal u = r.path("units").decimalValue().setScale(TaxLotLedger.UNITS, RoundingMode.UNNECESSARY);
      if (u.signum() > 0) out.add(new Claim(r.path("planId").asText(), u));
    }
    return out;
  }

  /** The person's Unassigned goal, made the first time it is needed. */
  public String unassigned(UUID owner, String personId) {
    var have = records.list("fin_plan", owner, Map.of("type", UNASSIGNED, "ownerPersonId", personId));
    if (!have.isEmpty()) return have.get(0).path("id").asText();
    ObjectNode g = Json.obj().put("type", UNASSIGNED).put("name", "Unassigned").put("ownerPersonId", personId)
        .put("targetDate", "2099-12-31").put("targetAmountToday", 0).put("priorityTier", "aspirational")
        .put("createdVia", "system").put("status", "active");
    return records.insert("fin_plan", owner, g).path("id").asText();
  }

  /** How long a lot must be held to be long term, from the configuration (never a figure of its own). */
  public Function<Lot, TermRule> termRules(UUID owner, ObjectNode instrument) {
    JsonNode cg = config.value(owner, "tax.capital_gains");
    String taxClass = instrument.path("taxClass").asText("other");
    return lot -> switch (taxClass) {
      case "equity" -> cg.path("equity").hasNonNull("shortTermMonths")
          ? TermRule.months(cg.path("equity").path("shortTermMonths").asInt()) : TermRule.unknown();
      case "specified_debt" -> {
        String from = cg.path("debtFundsBoughtFrom").asText("");
        if (!from.isEmpty() && lot.dateKnown() && lot.acquiredOn() != null
            && !lot.acquiredOn().isBefore(LocalDate.parse(from))) yield TermRule.slab();
        yield TermRule.unknown();
      }
      default -> TermRule.unknown();
    };
  }

  // ── writes ────────────────────────────────────────────────────────────────

  /**
   * Stores new claims for one holding and an event for every change. {@code before} is what was
   * stored; claims at zero lose their row (their events stay).
   */
  public void store(UUID owner, String personId, String accountId, String instrumentId, List<ObjectNode> rowsBefore,
             Result r, String kind, BigDecimal price, String note, Log log) {
    String key = accountId + "|" + instrumentId;
    if (log != null && !log.claimsBefore.containsKey(key)) {
      log.claimsBefore.put(key, claims(rowsBefore));
      log.personOf.put(key, personId);
    }
    BigDecimal avg = TaxLotLedger.averageCost(lots(lotRows(owner, UUID.fromString(accountId), UUID.fromString(instrumentId))));
    Map<String, ObjectNode> existing = new HashMap<>();
    rowsBefore.forEach(e -> existing.put(e.path("planId").asText(), e));
    Map<String, String> rowIds = new HashMap<>();
    Set<String> keep = new LinkedHashSet<>();
    for (Claim c : r.claims()) {
      keep.add(c.planId());
      ObjectNode row = existing.get(c.planId());
      ObjectNode v = Json.obj().put("units", c.units());
      if (avg != null) v.put("attributedCost", avg.multiply(c.units()).setScale(2, RoundingMode.HALF_UP));
      else v.putNull("attributedCost");
      if (row == null) {
        v.put("planId", c.planId()).put("personId", personId).put("accountId", accountId)
            .put("instrumentId", instrumentId).put("mode", "units");
        rowIds.put(c.planId(), records.insert("fin_earmark", owner, v).path("id").asText());
      } else {
        boolean same = row.path("units").decimalValue().compareTo(c.units()) == 0;
        if (!same || avg != null) records.update("fin_earmark", owner, UUID.fromString(row.path("id").asText()), v);
        rowIds.put(c.planId(), row.path("id").asText());
      }
    }
    for (var e : existing.entrySet()) {
      if (keep.contains(e.getKey())) continue;
      rowIds.put(e.getKey(), e.getValue().path("id").asText());
      records.delete("fin_earmark", owner, UUID.fromString(e.getValue().path("id").asText()));
    }
    for (Delta d : r.deltas()) {
      if (d.units().signum() == 0) continue;
      ObjectNode ev = Json.obj().put("planId", d.planId()).put("accountId", accountId).put("instrumentId", instrumentId)
          .put("kind", kind).put("deltaUnits", d.units());
      if (rowIds.containsKey(d.planId())) ev.put("earmarkId", rowIds.get(d.planId()));
      if (price != null) ev.put("price", price);
      if (note != null) ev.put("note", note.length() > 200 ? note.substring(0, 200) : note);
      if (r.deltas().size() == 2 && r.deltas().get(0).units().signum() != r.deltas().get(1).units().signum()) {
        Delta other = r.deltas().get(0) == d ? r.deltas().get(1) : r.deltas().get(0);
        ev.put("counterpartyPlanId", other.planId());
      }
      records.insert("fin_earmark_event", owner, ev);
    }
  }

  /**
   * Brings one holding's claims back to what its lots hold: a gap goes to Unassigned, units sold
   * outside Kai come off Unassigned first, then the goals pro rata.
   */
  public Result reconcile(UUID owner, String personId, String accountId, String instrumentId, Log log) {
    List<ObjectNode> rows = earmarkRows(owner, UUID.fromString(accountId), UUID.fromString(instrumentId));
    List<Claim> before = claims(rows);
    BigDecimal held = TaxLotLedger.remaining(lots(lotRows(owner, UUID.fromString(accountId), UUID.fromString(instrumentId))));
    BigDecimal claimed = Earmarks.total(before);
    if (claimed.compareTo(held) == 0) return new Result(before, List.of());
    String unassigned = unassigned(owner, personId);
    Result r = Earmarks.reconcile(before, held, unassigned);
    String kind = claimed.compareTo(held) > 0 ? "external_sell" : "reconcile";
    store(owner, personId, accountId, instrumentId, rows, r, kind, null,
        "external_sell".equals(kind) ? "Fewer units than the goals claimed" : "New units go to Unassigned", log);
    return r;
  }

  /**
   * The nightly check: every holding and every earmarked asset brought back to what it holds.
   * Returns how many were changed. Each holding runs in its own transaction via {@link #reconcile}.
   */
  public int reconcileAll(UUID owner) {
    Map<String, String> holdings = new java.util.LinkedHashMap<>();
    for (ObjectNode r : records.list("fin_holding_lot", owner, Map.of())) {
      holdings.putIfAbsent(r.path("accountId").asText() + "|" + r.path("instrumentId").asText(), r.path("personId").asText());
    }
    Set<String> assets = new LinkedHashSet<>();
    for (ObjectNode e : records.list("fin_earmark", owner, Map.of())) {
      if ("amount".equals(e.path("mode").asText()) && e.hasNonNull("assetId")) assets.add(e.get("assetId").asText());
      else if (e.hasNonNull("instrumentId")) {
        holdings.putIfAbsent(e.path("accountId").asText() + "|" + e.get("instrumentId").asText(), e.path("personId").asText());
      }
    }
    int changed = 0;
    for (var h : holdings.entrySet()) {
      String[] k = h.getKey().split("\\|");
      if (!reconcile(owner, h.getValue(), k[0], k[1], new Log()).deltas().isEmpty()) changed++;
    }
    for (String a : assets) reconcileAsset(owner, UUID.fromString(a));
    return changed;
  }

  /** New units bought: a lot, and the units go to {@code planId} (else Unassigned). */
  public String recordBuy(UUID owner, String personId, String accountId, String instrumentId, LocalDate date,
                          boolean dateKnown, BigDecimal qty, BigDecimal costPerUnit, String source, String batchId,
                          String planId, Log log) {
    if (qty == null || qty.signum() <= 0) throw new ValidationException("A purchase needs units above zero.");
    String key = accountId + "|" + instrumentId;
    List<ObjectNode> rowsBefore = earmarkRows(owner, UUID.fromString(accountId), UUID.fromString(instrumentId));
    if (log != null && !log.claimsBefore.containsKey(key)) {
      log.claimsBefore.put(key, claims(rowsBefore));
      log.personOf.put(key, personId);
    }
    ObjectNode lot = Json.obj().put("personId", personId).put("accountId", accountId).put("instrumentId", instrumentId)
        .put("acquiredOnKnown", dateKnown).put("qty", units(qty)).put("remainingQty", units(qty)).put("source", source);
    if (date != null) lot.put("acquiredOn", date.toString());
    if (costPerUnit != null) lot.put("costPerUnit", costPerUnit.setScale(4, RoundingMode.HALF_UP));
    if (batchId != null) lot.put("importBatchId", batchId);
    String id = records.insert("fin_holding_lot", owner, lot).path("id").asText();
    if (log != null) log.createdLots.add(id);
    // Bring the claims to the lots first (so an earlier gap is settled), then give the new units.
    reconcile(owner, personId, accountId, instrumentId, log);
    if (planId != null) {
      String unassigned = unassigned(owner, personId);
      List<ObjectNode> rows = earmarkRows(owner, UUID.fromString(accountId), UUID.fromString(instrumentId));
      Result moved = Earmarks.reassign(claims(rows), unassigned, planId, units(qty));
      store(owner, personId, accountId, instrumentId, rows, new Result(moved.claims(), List.of(new Delta(planId, units(qty)))),
          "buy", costPerUnit, null, log);
    }
    return id;
  }

  /** What a sale did: the gain rows and the FIFO parts. */
  public record SaleDone(Sale sale, List<String> gainIds) {}

  /**
   * Units sold. With {@code planId} the sale is that goal's (its claim falls); without, it was
   * sold outside Kai: Unassigned gives first, then the goals pro rata, each with an event, and
   * the holding shows in "Needs a look". The gain always comes from the FIFO lots; a gain row is
   * written only when the proceeds are known.
   */
  public SaleDone recordSell(UUID owner, String personId, String accountId, String instrumentId, LocalDate date,
                             BigDecimal qty, BigDecimal proceeds, String planId, String batchId, String transactionId,
                             Log log) {
    UUID acc = UUID.fromString(accountId);
    UUID ins = UUID.fromString(instrumentId);
    ObjectNode instrument = market.instrument(ins).orElseThrow();
    List<ObjectNode> lotRows = lotRows(owner, acc, ins);
    List<Lot> lots = lots(lotRows);
    BigDecimal q = units(qty);
    BigDecimal held = TaxLotLedger.remaining(lots);
    if (held.compareTo(q) < 0) {
      throw new ValidationException("The account holds fewer units than this sale. Import the earlier purchases first.");
    }
    Sale sale = TaxLotLedger.sell(lots, q, proceeds, date, termRules(owner, instrument));
    Map<String, Lot> byId = new HashMap<>();
    lots.forEach(l -> byId.put(l.id(), l));
    for (Lot after : sale.after()) {
      Lot was = byId.get(after.id());
      if (was.remaining().compareTo(after.remaining()) == 0) continue;
      if (log != null && !log.lotsBefore.containsKey(after.id())) log.lotsBefore.put(after.id(), was.remaining());
      records.update("fin_holding_lot", owner, UUID.fromString(after.id()), Json.obj().put("remainingQty", after.remaining()));
    }
    List<ObjectNode> rows = earmarkRows(owner, acc, ins);
    List<Claim> before = claims(rows);
    // Settle any earlier gap against the lots as they were, then take the sale off.
    List<Claim> settled = Earmarks.reconcile(before, held, unassigned(owner, personId)).claims();
    Result r;
    String kind;
    String gainPlan;
    if (planId != null && Earmarks.of(settled, planId).compareTo(q) >= 0) {
      r = Earmarks.sellFrom(settled, planId, q);
      kind = "sell";
      gainPlan = planId;
    } else {
      r = Earmarks.reduce(settled, q, unassigned(owner, personId));
      kind = "external_sell";
      gainPlan = r.deltas().size() == 1 ? r.deltas().get(0).planId() : null;
    }
    List<Delta> deltas = new ArrayList<>(Earmarks.reconcile(before, held, unassigned(owner, personId)).deltas());
    deltas.addAll(r.deltas());
    BigDecimal price = proceeds == null ? null : proceeds.divide(q, 4, RoundingMode.HALF_UP);
    store(owner, personId, accountId, instrumentId, rows, new Result(r.claims(), deltas), kind, price,
        "external_sell".equals(kind) ? "Sold outside Kai" : null, log);

    List<String> gainIds = new ArrayList<>();
    if (proceeds != null) {
      for (var p : sale.parts()) {
        ObjectNode g = Json.obj().put("personId", personId).put("taxYear", TaxLotLedger.taxYear(date))
            .put("instrumentId", instrumentId).put("lotId", p.lotId()).put("accountId", accountId)
            .put("soldOn", date.toString()).put("qty", p.qty()).put("proceeds", p.proceeds())
            .put("taxClass", instrument.path("taxClass").asText("other"));
        if (p.cost() != null) g.put("cost", p.cost());
        if (p.gain() != null) g.put("gain", p.gain());
        if (p.term() != null) g.put("term", p.term());
        if (gainPlan != null) g.put("planId", gainPlan);
        if (transactionId != null) g.put("transactionId", transactionId);
        if (batchId != null) g.put("importBatchId", batchId);
        String id = records.insert("fin_realized_gain", owner, g).path("id").asText();
        gainIds.add(id);
        if (log != null) log.gains.add(id);
      }
    }
    return new SaleDone(sale, gainIds);
  }

  /**
   * A holdings file or CAS says the account holds {@code units} on {@code asOf}. More than the
   * lots: an opening lot for the difference (its date unknown until the user gives it). Fewer: a
   * sale outside Kai (no proceeds known, so no gain row) and the claims come down.
   */
  public void snapshot(UUID owner, String personId, String accountId, String instrumentId, BigDecimal units,
                       BigDecimal avgCost, LocalDate asOf, String batchId, Log log) {
    BigDecimal target = units(units);
    BigDecimal held = TaxLotLedger.remaining(lots(lotRows(owner, UUID.fromString(accountId), UUID.fromString(instrumentId))));
    int cmp = target.compareTo(held);
    if (cmp > 0) {
      recordBuy(owner, personId, accountId, instrumentId, null, false, target.subtract(held), avgCost,
          "opening_balance", batchId, null, log);
    } else if (cmp < 0) {
      recordSell(owner, personId, accountId, instrumentId, asOf, held.subtract(target), null, null, batchId, null, log);
    } else {
      reconcile(owner, personId, accountId, instrumentId, log);
    }
  }

  /** Undoes what a {@link Log} recorded: claims, lots and gains go back (gains by a reversing row). */
  public void undo(UUID owner, JsonNode log) {
    if (log == null || log.isMissingNode() || log.isNull()) return;
    for (JsonNode g : log.path("gains")) {
      records.get("fin_realized_gain", owner, UUID.fromString(g.asText())).ifPresent(row -> {
        ObjectNode rev = Json.obj();
        for (String f : List.of("personId", "taxYear", "instrumentId", "lotId", "planId", "accountId", "soldOn",
            "term", "taxClass", "importBatchId")) {
          if (row.hasNonNull(f)) rev.set(f, row.get(f));
        }
        for (String f : List.of("qty", "proceeds", "cost", "gain")) {
          if (row.hasNonNull(f)) rev.put(f, row.get(f).decimalValue().negate());
        }
        rev.put("reversesId", row.path("id").asText());
        records.insert("fin_realized_gain", owner, rev);
      });
    }
    log.path("lotsBefore").fields().forEachRemaining(e ->
        records.update("fin_holding_lot", owner, UUID.fromString(e.getKey()),
            Json.obj().put("remainingQty", e.getValue().decimalValue())));
    for (JsonNode h : log.path("holdings")) {
      String accountId = h.path("accountId").asText();
      String instrumentId = h.path("instrumentId").asText();
      List<Claim> back = new ArrayList<>();
      h.path("claims").forEach(c -> back.add(new Claim(c.path("planId").asText(), c.path("units").decimalValue()
          .setScale(TaxLotLedger.UNITS, RoundingMode.UNNECESSARY))));
      // Lots made by the import go before the claims are put back, so the two agree again.
      for (JsonNode id : log.path("createdLots")) {
        records.get("fin_holding_lot", owner, UUID.fromString(id.asText()))
            .filter(l -> l.path("accountId").asText().equals(accountId) && l.path("instrumentId").asText().equals(instrumentId))
            .ifPresent(l -> records.delete("fin_holding_lot", owner, UUID.fromString(id.asText())));
      }
      List<ObjectNode> rows = earmarkRows(owner, UUID.fromString(accountId), UUID.fromString(instrumentId));
      List<Claim> now = claims(rows);
      List<Delta> deltas = new ArrayList<>();
      Set<String> plans = new LinkedHashSet<>();
      now.forEach(c -> plans.add(c.planId()));
      back.forEach(c -> plans.add(c.planId()));
      for (String p : plans) {
        BigDecimal d = Earmarks.of(back, p).subtract(Earmarks.of(now, p));
        if (d.signum() != 0) deltas.add(new Delta(p, d));
      }
      store(owner, h.path("personId").asText(), accountId, instrumentId, rows, new Result(Earmarks.clean(back), deltas),
          "reconcile", null, "Import undone", null);
    }
    for (JsonNode id : log.path("createdLots")) {
      records.get("fin_holding_lot", owner, UUID.fromString(id.asText()))
          .ifPresent(l -> records.delete("fin_holding_lot", owner, UUID.fromString(id.asText())));
    }
  }

  // ── amount-mode earmarks (FDs, balances, EPF…) ────────────────────────────

  public List<ObjectNode> assetEarmarkRows(UUID owner, UUID assetId) {
    return records.list("fin_earmark", owner, Map.of("assetId", assetId.toString()));
  }

  public static List<Claim> amountClaims(List<ObjectNode> rows) {
    List<Claim> out = new ArrayList<>();
    for (ObjectNode r : rows) {
      if (!"amount".equals(r.path("mode").asText())) continue;
      BigDecimal a = r.path("amount").decimalValue().setScale(TaxLotLedger.UNITS, RoundingMode.UNNECESSARY);
      if (a.signum() > 0) out.add(new Claim(r.path("planId").asText(), a));
    }
    return out;
  }

  @org.springframework.context.event.EventListener
  public void onAssetValueChanged(com.jizza.platform.usecase.finance.records.FinRecordsUseCase.AssetValueChanged e) {
    reconcileAsset(e.owner(), e.assetId());
  }

  /** Brings an asset's amount earmarks back to its value (the asset was edited or re-imported). */
  @Transactional
  public void reconcileAsset(UUID owner, UUID assetId) {
    var asset = records.get("fin_asset", owner, assetId);
    if (asset.isEmpty()) return;
    List<ObjectNode> rows = assetEarmarkRows(owner, assetId);
    if (rows.isEmpty()) return;
    BigDecimal value = asset.get().path("value").decimalValue().setScale(TaxLotLedger.UNITS, RoundingMode.HALF_UP);
    List<Claim> before = amountClaims(rows);
    if (Earmarks.total(before).compareTo(value) == 0) return;
    String unassigned = unassigned(owner, asset.get().path("personId").asText());
    Result r = Earmarks.reconcile(before, value, unassigned, TaxLotLedger.MONEY);
    storeAmounts(owner, asset.get(), rows, r, "reconcile", "The value changed");
  }

  public void storeAmounts(UUID owner, ObjectNode asset, List<ObjectNode> rowsBefore, Result r, String kind, String note) {
    Map<String, ObjectNode> existing = new HashMap<>();
    rowsBefore.forEach(e -> existing.put(e.path("planId").asText(), e));
    Set<String> keep = new LinkedHashSet<>();
    String assetId = asset.path("id").asText();
    Map<String, String> rowIds = new HashMap<>();
    for (Claim c : r.claims()) {
      keep.add(c.planId());
      BigDecimal amount = c.units().setScale(2, RoundingMode.HALF_UP);
      ObjectNode row = existing.get(c.planId());
      if (row == null) {
        ObjectNode v = Json.obj().put("planId", c.planId()).put("personId", asset.path("personId").asText())
            .put("assetId", assetId).put("mode", "amount").put("amount", amount);
        if (asset.hasNonNull("accountId")) v.put("accountId", asset.get("accountId").asText());
        rowIds.put(c.planId(), records.insert("fin_earmark", owner, v).path("id").asText());
      } else {
        records.update("fin_earmark", owner, UUID.fromString(row.path("id").asText()), Json.obj().put("amount", amount));
        rowIds.put(c.planId(), row.path("id").asText());
      }
    }
    for (var e : existing.entrySet()) {
      if (keep.contains(e.getKey())) continue;
      rowIds.put(e.getKey(), e.getValue().path("id").asText());
      records.delete("fin_earmark", owner, UUID.fromString(e.getValue().path("id").asText()));
    }
    for (Delta d : r.deltas()) {
      if (d.units().signum() == 0) continue;
      ObjectNode ev = Json.obj().put("planId", d.planId()).put("assetId", assetId).put("kind", kind)
          .put("deltaAmount", d.units().setScale(2, RoundingMode.HALF_UP));
      if (rowIds.containsKey(d.planId())) ev.put("earmarkId", rowIds.get(d.planId()));
      if (note != null) ev.put("note", note);
      records.insert("fin_earmark_event", owner, ev);
    }
  }

  static BigDecimal units(BigDecimal v) {
    return v.setScale(TaxLotLedger.UNITS, RoundingMode.HALF_UP);
  }

  /** Today in India, for the ledgers' dates. */
  static LocalDate today() {
    return HouseholdLoader.today();
  }
}

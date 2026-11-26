package com.jizza.platform.usecase.finance.ledger;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.finance.FinanceMarket;
import com.jizza.engine.api.finance.FinanceMarket.Price;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.platform.usecase.finance.ledger.Earmarks.Claim;
import com.jizza.platform.usecase.finance.ledger.TaxLotLedger.Lot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Reads one owner's holdings with their value (spec 5.2, 7.6): units from the lots, a value from
 * the latest dated price (else the cost, marked as such), and each goal's claim. Read only; the
 * caller has passed the scope resolver.
 */
@Service
public class HoldingsReader {

  private final FinanceRecords records;
  private final FinanceMarket market;

  public HoldingsReader(FinanceRecords records, FinanceMarket market) {
    this.records = records;
    this.market = market;
  }

  /**
   * One person's units of one instrument in one account. {@code valueSource} is price, cost or
   * none; {@code claims} are the goals' units (Unassigned included).
   */
  public record Position(String personId, String accountId, String instrumentId, ObjectNode instrument,
                         BigDecimal units, List<Lot> lots, BigDecimal avgCost, Price price, BigDecimal value,
                         String valueSource, List<Claim> claims) {

    public BigDecimal valueOf(BigDecimal claimUnits) {
      if (value == null || units.signum() == 0) return BigDecimal.ZERO;
      return value.multiply(claimUnits).divide(units, 2, RoundingMode.HALF_UP);
    }

    public BigDecimal claimed() {
      return Earmarks.total(claims);
    }

    public String key() {
      return accountId + "|" + instrumentId;
    }
  }

  /** Every holding with units left, valued on {@code asOf}. */
  public List<Position> positions(UUID owner, LocalDate asOf) {
    Map<String, List<ObjectNode>> lotsBy = new LinkedHashMap<>();
    for (ObjectNode l : records.list("fin_holding_lot", owner, Map.of())) {
      lotsBy.computeIfAbsent(l.path("accountId").asText() + "|" + l.path("instrumentId").asText(), k -> new ArrayList<>()).add(l);
    }
    if (lotsBy.isEmpty()) return List.of();
    Map<String, List<ObjectNode>> marksBy = new HashMap<>();
    for (ObjectNode e : records.list("fin_earmark", owner, Map.of("mode", "units"))) {
      marksBy.computeIfAbsent(e.path("accountId").asText() + "|" + e.path("instrumentId").asText(), k -> new ArrayList<>()).add(e);
    }
    Set<UUID> ids = new HashSet<>();
    lotsBy.keySet().forEach(k -> ids.add(UUID.fromString(k.split("\\|")[1])));
    Map<String, ObjectNode> instruments = new HashMap<>();
    market.instruments(ids).forEach(i -> instruments.put(i.path("id").asText(), i));
    Map<UUID, Price> prices = market.latestPrices(ids, asOf, owner);
    List<Position> out = new ArrayList<>();
    for (var e : lotsBy.entrySet()) {
      List<Lot> lots = EarmarkService.lots(e.getValue());
      BigDecimal units = TaxLotLedger.remaining(lots);
      if (units.signum() == 0) continue;
      ObjectNode first = e.getValue().get(0);
      String instrumentId = first.path("instrumentId").asText();
      Price price = prices.get(UUID.fromString(instrumentId));
      BigDecimal avg = TaxLotLedger.averageCost(lots);
      BigDecimal value;
      String source;
      if (price != null) {
        value = price.price().multiply(units).setScale(2, RoundingMode.HALF_UP);
        source = "price";
      } else if (avg != null) {
        value = avg.multiply(units).setScale(2, RoundingMode.HALF_UP);
        source = "cost";
      } else {
        value = null;
        source = "none";
      }
      out.add(new Position(first.path("personId").asText(), first.path("accountId").asText(), instrumentId,
          instruments.get(instrumentId), units, lots, avg, price, value, source,
          EarmarkService.claims(marksBy.getOrDefault(e.getKey(), List.of()))));
    }
    return out;
  }

  /**
   * Each goal's earmarked value: its units at today's value plus its amount earmarks. Goals with
   * nothing earmarked are absent, so a goal keeps the value its owner typed until it has some.
   */
  public Map<String, BigDecimal> goalValues(UUID owner, List<Position> positions) {
    Map<String, BigDecimal> out = new HashMap<>();
    for (Position p : positions) {
      for (Claim c : p.claims()) out.merge(c.planId(), p.valueOf(c.units()), BigDecimal::add);
    }
    for (ObjectNode e : records.list("fin_earmark", owner, Map.of("mode", "amount"))) {
      out.merge(e.path("planId").asText(), e.path("amount").decimalValue(), BigDecimal::add);
    }
    return out;
  }
}

package com.jizza.platform.usecase.finance.lists;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceMarket;
import com.jizza.engine.api.finance.FinanceMarket.Price;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.health.HouseholdLoader;
import com.jizza.platform.usecase.finance.ledger.HoldingsUseCase;
import com.jizza.platform.usecase.finance.records.FinRecordsUseCase;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lists (screen 7.11): the Watchlist and Next to consider. The promote path keeps the notes:
 * Watchlist → Next to consider → Holding, where the last step records a purchase the user made
 * themselves. Kai never adds an instrument here on its own and never names a fund.
 */
@Service
public class ListsUseCase {

  static final int MAX_ITEMS = 500;

  private final FinanceRecords records;
  private final FinanceMarket market;
  private final FinRecordsUseCase forms;
  private final HoldingsUseCase holdings;
  private final FinScopeResolver scopes;
  private final AuditLog audit;

  public ListsUseCase(FinanceRecords records, FinanceMarket market, FinRecordsUseCase forms, HoldingsUseCase holdings,
                      FinScopeResolver scopes, AuditLog audit) {
    this.records = records;
    this.market = market;
    this.forms = forms;
    this.holdings = holdings;
    this.scopes = scopes;
    this.audit = audit;
  }

  /** The items of one list (or both), each with its instrument, price and change since the price before. */
  public List<ObjectNode> list(UUID caller, String type) {
    UUID owner = scopes.own(caller).ownerUserId();
    if (type != null && !type.isBlank() && !List.of("watch", "next").contains(type)) {
      throw new ValidationException("type is watch or next.");
    }
    List<ObjectNode> items = records.list("fin_list_item", owner, type == null || type.isBlank() ? Map.of() : Map.of("listType", type));
    Set<UUID> ids = new HashSet<>();
    items.forEach(i -> ids.add(UUID.fromString(i.path("instrumentId").asText())));
    Map<String, ObjectNode> ins = new HashMap<>();
    market.instruments(ids).forEach(i -> ins.put(i.path("id").asText(), i));
    var today = HouseholdLoader.today();
    Map<UUID, Price> now = market.latestPrices(ids, today, owner);
    Map<String, ObjectNode> goals = new HashMap<>();
    records.list("fin_plan", owner, Map.of()).forEach(g -> goals.put(g.path("id").asText(), g));
    List<ObjectNode> out = new ArrayList<>();
    for (ObjectNode i : items) {
      ObjectNode o = i.deepCopy();
      ObjectNode instrument = ins.get(i.path("instrumentId").asText());
      if (instrument != null) {
        o.put("name", instrument.path("name").asText()).put("type", instrument.path("type").asText())
            .put("symbol", instrument.path("symbol").asText(null)).put("subCategory", instrument.path("subCategory").asText(null));
      }
      Price p = now.get(UUID.fromString(i.path("instrumentId").asText()));
      if (p != null) {
        o.put("price", p.price()).put("priceDate", p.date().toString());
        Price before = market.latestPrices(List.of(p.instrumentId()), p.date().minusDays(1), owner).get(p.instrumentId());
        if (before != null && before.price().signum() > 0) {
          o.put("changePct", p.price().subtract(before.price()).multiply(BigDecimal.valueOf(100))
              .divide(before.price(), 2, RoundingMode.HALF_UP));
        }
      }
      ObjectNode g = i.hasNonNull("planId") ? goals.get(i.get("planId").asText()) : null;
      if (g != null) o.put("goalName", g.path("name").asText());
      if (i.hasNonNull("nodeId")) {
        records.get("fin_node", owner, UUID.fromString(i.get("nodeId").asText())).ifPresent(n -> o.put("nodeName", n.path("name").asText()));
      }
      out.add(o);
    }
    out.sort((a, b) -> Integer.compare(b.path("priority").asInt(), a.path("priority").asInt()));
    return out;
  }

  @Transactional
  public ObjectNode create(UUID caller, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    if (records.list("fin_list_item", owner, Map.of()).size() >= MAX_ITEMS) {
      throw new ValidationException("Your lists hold " + MAX_ITEMS + " items already. Remove some first.");
    }
    ObjectNode v = clean(owner, body, true);
    ObjectNode row = records.insert("fin_list_item", owner, v);
    audit.record(owner, ActorType.USER, caller, "finance.list.added", row.path("id").asText(), null);
    return row;
  }

  @Transactional
  public ObjectNode update(UUID caller, UUID id, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    item(owner, id);
    ObjectNode v = clean(owner, body, false);
    ObjectNode row = v.isEmpty() ? item(owner, id) : records.update("fin_list_item", owner, id, v).orElseThrow();
    audit.record(owner, ActorType.USER, caller, "finance.list.updated", id.toString(), null);
    return row;
  }

  @Transactional
  public void delete(UUID caller, UUID id) {
    UUID owner = scopes.own(caller).ownerUserId();
    if (!records.delete("fin_list_item", owner, id)) throw new NotFoundException("List item", id);
    audit.record(owner, ActorType.USER, caller, "finance.list.removed", id.toString(), null);
  }

  /**
   * One step along the promote path. A watch item moves to Next to consider with its notes. A Next
   * item becomes a holding: the body is the purchase the user made (account, date, units, price,
   * goal), recorded as a lot; the item then leaves the list.
   */
  @Transactional
  public ObjectNode promote(UUID caller, UUID id, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode item = item(owner, id);
    if ("watch".equals(item.path("listType").asText())) {
      ObjectNode row = records.update("fin_list_item", owner, id, Json.obj().put("listType", "next")).orElseThrow();
      audit.record(owner, ActorType.USER, caller, "finance.list.promoted", id.toString(), Json.obj().put("to", "next"));
      return Json.obj().put("to", "next").set("item", row);
    }
    if (body == null || !body.isObject()) throw new ValidationException("Send the purchase you made: account, date, units and price.");
    ObjectNode trade = ((ObjectNode) body).deepCopy();
    trade.put("instrumentId", item.path("instrumentId").asText());
    if (!trade.hasNonNull("goalId") && item.hasNonNull("planId")) trade.put("goalId", item.get("planId").asText());
    ObjectNode holding = holdings.purchase(caller, trade);
    records.delete("fin_list_item", owner, id);
    audit.record(owner, ActorType.USER, caller, "finance.list.promoted", id.toString(), Json.obj().put("to", "holding"));
    ObjectNode out = Json.obj().put("to", "holding");
    out.set("holding", holding);
    return out;
  }

  private ObjectNode item(UUID owner, UUID id) {
    return records.get("fin_list_item", owner, id).orElseThrow(() -> new NotFoundException("List item", id));
  }

  private ObjectNode clean(UUID owner, JsonNode b, boolean creating) {
    if (b == null || !b.isObject()) throw new ValidationException("Send the item as an object.");
    ObjectNode v = Json.obj();
    if (b.has("listType") || creating) {
      String t = b.path("listType").asText("");
      if (!List.of("watch", "next").contains(t)) throw new ValidationException("listType is watch or next.");
      v.put("listType", t);
    }
    if (b.has("instrumentId") || creating) {
      if (!creating) throw new ValidationException("An item's instrument cannot change. Add a new item instead.");
      UUID ins = uuid(b, "instrumentId");
      if (ins == null) throw new ValidationException("Choose the instrument.");
      market.instrument(ins).orElseThrow(() -> new NotFoundException("Instrument", ins));
      v.put("instrumentId", ins.toString());
    }
    if (b.has("personId")) {
      UUID p = uuid(b, "personId");
      if (p != null) forms.requirePerson(owner, p);
      if (p == null) v.putNull("personId");
      else v.put("personId", p.toString());
    }
    if (b.has("planId")) {
      UUID p = uuid(b, "planId");
      if (p != null) records.get("fin_plan", owner, p).filter(g -> !HouseholdLoader.isUnassigned(g))
          .orElseThrow(() -> new NotFoundException("Goal", p));
      if (p == null) v.putNull("planId");
      else v.put("planId", p.toString());
    }
    if (b.has("nodeId")) {
      UUID n = uuid(b, "nodeId");
      if (n != null) records.get("fin_node", owner, n).orElseThrow(() -> new NotFoundException("Tree node", n));
      if (n == null) v.putNull("nodeId");
      else v.put("nodeId", n.toString());
    }
    if (b.has("notes")) {
      String notes = b.path("notes").asText("");
      if (notes.length() > 500) throw new ValidationException("Notes are at most 500 characters.");
      v.put("notes", notes);
    }
    if (b.has("priority")) {
      int p = b.path("priority").asInt(0);
      if (p < 0 || p > 100) throw new ValidationException("priority is 0 to 100.");
      v.put("priority", p);
    }
    for (String f : List.of("targetEntry", "plannedAmount")) {
      if (!b.has(f)) continue;
      if (b.get(f).isNull()) {
        v.putNull(f);
        continue;
      }
      if (!b.get(f).isNumber() || b.get(f).decimalValue().signum() < 0) throw new ValidationException(f + " is a number, zero or more.");
      v.put(f, b.get(f).decimalValue());
    }
    if (b.has("conviction")) {
      if (b.get("conviction").isNull()) v.putNull("conviction");
      else {
        int c = b.get("conviction").asInt();
        if (c < 1 || c > 5) throw new ValidationException("conviction is 1 to 5.");
        v.put("conviction", c);
      }
    }
    return v;
  }

  private static UUID uuid(JsonNode b, String f) {
    JsonNode v = b.get(f);
    if (v == null || v.isNull() || v.asText().isBlank()) return null;
    try {
      return UUID.fromString(v.asText());
    } catch (IllegalArgumentException e) {
      throw new ValidationException(f + " is not an id.");
    }
  }
}

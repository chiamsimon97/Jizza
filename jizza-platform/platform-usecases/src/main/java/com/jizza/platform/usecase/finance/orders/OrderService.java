package com.jizza.platform.usecase.finance.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceMarket;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.health.HouseholdLoader;
import com.jizza.platform.usecase.finance.ledger.EarmarkService;
import com.jizza.platform.usecase.finance.ledger.TaxLotLedger;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import com.jizza.platform.usecase.finance.sync.BrokerSyncService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Orders the person places from Kai (part D). Kai fills in an order and shows its tax effect from
 * the person's own lots; the person changes what they like and confirms it with their password,
 * one order at a time. It goes out only through a connected broker server that offers order
 * placing, and only its {@code place_order} tool. Kai never places an order on its own, never
 * names a fund to buy, and the tax lots change only when the next holdings sync shows the trade.
 * Nothing here logs an amount.
 */
@Service
public class OrderService {

  private static final Set<String> HOLDING_ACCOUNTS = Set.of("demat", "us_broker", "other");
  private static final Set<String> SIDES = Set.of("buy", "sell");
  private static final Set<String> TYPES = Set.of("market", "limit");
  private static final Pattern ORDER_ID = Pattern.compile("(?i)\"?order_id\"?\\s*[:=]\\s*\"?([A-Za-z0-9_-]{4,64})");
  private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9&-]{1,30}");

  private final FinanceRecords records;
  private final FinanceMarket market;
  private final FinScopeResolver scopes;
  private final EarmarkService earmarks;
  private final BrokerSyncService brokers;
  private final AuditLog audit;

  public OrderService(FinanceRecords records, FinanceMarket market, FinScopeResolver scopes, EarmarkService earmarks,
                      BrokerSyncService brokers, AuditLog audit) {
    this.records = records;
    this.market = market;
    this.scopes = scopes;
    this.earmarks = earmarks;
    this.brokers = brokers;
    this.audit = audit;
  }

  /** What the person asks for. {@code limitPrice} only with a limit order. */
  public record OrderInput(UUID accountId, UUID instrumentId, String side, BigDecimal quantity, String orderType,
                           BigDecimal limitPrice, UUID goalId) {}

  /** One lot a sale would use, in the order a sale uses them, with its gain at today's price. */
  public record LotUse(String acquiredOn, BigDecimal quantity, BigDecimal cost, BigDecimal gain, String term) {}

  /**
   * The prepared order: what it is, about what it comes to, its tax effect for a sale, and whether
   * a connected broker can place it ({@code canPlace}; else {@code whyNot} and the broker's site).
   */
  public record Preview(UUID accountId, UUID instrumentId, String instrumentName, String symbol, String exchange,
                        String side, BigDecimal quantity, String orderType, BigDecimal limitPrice, BigDecimal price,
                        String priceDate, BigDecimal estimate, BigDecimal held, List<LotUse> lots,
                        BigDecimal shortTermGain, BigDecimal longTermGain, BigDecimal unknownTermGain,
                        boolean canPlace, String placeVia, String whyNot, String brokerUrl) {}

  /** What came back from the broker. */
  public record Placed(UUID orderId, String status, String brokerOrderId, String message) {}

  public Preview prepare(UUID caller, OrderInput in) {
    UUID owner = scopes.own(caller).ownerUserId();
    return preview(owner, in);
  }

  /**
   * Sends the order the person confirmed. The password has been checked by the caller just
   * before, every time. The order and the broker's reply are kept; the lots are not touched.
   */
  public Placed place(UUID caller, OrderInput in) {
    UUID owner = scopes.own(caller).ownerUserId();
    Preview p = preview(owner, in);
    if (!p.canPlace()) throw new ValidationException(p.whyNot());
    BrokerSyncService.OrderRoute route = brokers.orderRoute(owner, in.accountId());
    ObjectNode args = Json.obj().put("variety", "regular").put("exchange", p.exchange())
        .put("tradingsymbol", p.symbol()).put("transaction_type", p.side().toUpperCase(Locale.ROOT))
        .put("quantity", p.quantity().intValueExact()).put("product", "CNC")
        .put("order_type", p.orderType().toUpperCase(Locale.ROOT)).put("validity", "DAY");
    if ("limit".equals(p.orderType())) args.put("price", p.limitPrice());

    ObjectNode row = Json.obj().put("accountId", in.accountId().toString()).put("instrumentId", in.instrumentId().toString())
        .put("serverId", route.serverId().toString()).put("side", p.side()).put("quantity", p.quantity())
        .put("orderType", p.orderType()).put("status", "sending");
    if (p.limitPrice() != null) row.put("limitPrice", p.limitPrice());
    if (in.goalId() != null) row.put("planId", in.goalId().toString());
    ObjectNode saved = records.insert("fin_order", owner, row);
    UUID id = UUID.fromString(saved.path("id").asText());

    ToolResult r = brokers.placeOrder(owner, route, args);
    String text = r.ok() ? text(r.output()) : (r.error() == null ? "The broker refused the order." : r.error());
    String brokerId = null;
    if (r.ok()) {
      Matcher m = ORDER_ID.matcher(text);
      if (m.find()) brokerId = m.group(1);
    }
    boolean sent = r.ok() && !looksRefused(text);
    ObjectNode patch = Json.obj().put("status", sent ? "sent" : "rejected").put("brokerReply", clip(text, 500));
    if (brokerId != null) patch.put("brokerOrderId", brokerId);
    records.update("fin_order", owner, id, patch);
    audit.record(owner, ActorType.USER, caller, sent ? "finance.order.sent" : "finance.order.rejected", id.toString(),
        Json.obj().put("side", p.side()).put("instrumentId", in.instrumentId().toString()));
    String message = sent
        ? route.serverName() + " took the order" + (brokerId == null ? "." : " (" + brokerId + ").")
            + " Your holdings change when the next sync shows it filled."
        : route.serverName() + " didn't take the order: " + clip(text, 200);
    return new Placed(id, sent ? "sent" : "rejected", brokerId, message);
  }

  /** The person's orders, newest first. */
  public List<ObjectNode> list(UUID caller) {
    UUID owner = scopes.own(caller).ownerUserId();
    List<ObjectNode> rows = new ArrayList<>(records.list("fin_order", owner, Map.of()));
    rows.sort((a, b) -> b.path("createdAt").asText().compareTo(a.path("createdAt").asText()));
    return rows;
  }

  // ── The preview ─────────────────────────────────────────────────────────────

  private Preview preview(UUID owner, OrderInput in) {
    if (in == null || in.accountId() == null || in.instrumentId() == null) {
      throw new ValidationException("Choose the account and what to buy or sell.");
    }
    String side = in.side() == null ? "" : in.side().toLowerCase(Locale.ROOT);
    if (!SIDES.contains(side)) throw new ValidationException("Choose buy or sell.");
    String type = in.orderType() == null ? "market" : in.orderType().toLowerCase(Locale.ROOT);
    if (!TYPES.contains(type)) throw new ValidationException("Choose a market or limit order.");
    BigDecimal qty = in.quantity();
    if (qty == null || qty.signum() <= 0) throw new ValidationException("The quantity is above zero.");
    if (qty.stripTrailingZeros().scale() > 0) throw new ValidationException("Shares are bought and sold in whole units.");
    if (qty.compareTo(BigDecimal.valueOf(1_000_000)) > 0) throw new ValidationException("That quantity is too large.");
    BigDecimal limit = null;
    if (type.equals("limit")) {
      if (in.limitPrice() == null || in.limitPrice().signum() <= 0) throw new ValidationException("A limit order needs a price above zero.");
      limit = in.limitPrice();
    }
    ObjectNode account = records.get("fin_account", owner, in.accountId())
        .orElseThrow(() -> new NotFoundException("Account", in.accountId()));
    if (!HOLDING_ACCOUNTS.contains(account.path("kind").asText())) {
      throw new ValidationException("Orders go through a demat or broker account.");
    }
    if (in.goalId() != null) {
      var goal = records.get("fin_plan", owner, in.goalId()).orElse(null);
      if (goal == null || HouseholdLoader.isUnassigned(goal)) throw new NotFoundException("Goal", in.goalId());
    }
    ObjectNode ins = market.instrument(in.instrumentId()).orElseThrow(() -> new NotFoundException("Instrument", in.instrumentId()));
    String name = ins.path("name").asText("");
    String symbol = ins.hasNonNull("symbol") ? ins.get("symbol").asText()
        : SYMBOL.matcher(name).matches() ? name : null;
    String exchange = ins.path("exchange").asText("NSE");

    LocalDate today = HouseholdLoader.today();
    var price = market.latestPrices(List.of(in.instrumentId()), today, owner).get(in.instrumentId());
    BigDecimal unit = limit != null ? limit : price == null ? null : price.price();
    BigDecimal estimate = unit == null ? null : unit.multiply(qty).setScale(2, RoundingMode.HALF_UP);

    var lots = EarmarkService.lots(earmarks.lotRows(owner, in.accountId(), in.instrumentId()));
    BigDecimal held = TaxLotLedger.remaining(lots);
    List<LotUse> uses = new ArrayList<>();
    BigDecimal st = null;
    BigDecimal lt = null;
    BigDecimal unknown = null;
    if (side.equals("sell")) {
      if (held.compareTo(qty) < 0) {
        throw new ValidationException("The account holds " + held.stripTrailingZeros().toPlainString()
            + " units, fewer than the " + qty.stripTrailingZeros().toPlainString() + " to sell.");
      }
      var sale = TaxLotLedger.sell(lots, qty, estimate, today, earmarks.termRules(owner, ins));
      for (var part : sale.parts()) {
        uses.add(new LotUse(part.acquiredOn() == null ? null : part.acquiredOn().toString(), part.qty(), part.cost(),
            part.gain(), part.term()));
        if (part.gain() == null) continue;
        if ("st".equals(part.term())) st = add(st, part.gain());
        else if ("lt".equals(part.term())) lt = add(lt, part.gain());
        else unknown = add(unknown, part.gain());
      }
    }

    String whyNot = null;
    BrokerSyncService.OrderRoute route = brokers.orderRoute(owner, in.accountId());
    if ("mf".equals(ins.path("type").asText())) whyNot = "Mutual fund orders go through your fund platform, not the share order tool.";
    else if (symbol == null) whyNot = "Kai doesn't know this one's trading symbol, so it can't fill in the order.";
    else if (!route.available()) whyNot = route.reason();
    return new Preview(in.accountId(), in.instrumentId(), name, symbol, exchange, side, qty, type, limit,
        price == null ? null : price.price(), price == null ? null : price.date().toString(), estimate, held, uses,
        st, lt, unknown, whyNot == null, route.serverName(), whyNot, "https://kite.zerodha.com/");
  }

  private static BigDecimal add(BigDecimal a, BigDecimal b) {
    return a == null ? b : a.add(b);
  }

  private static String text(JsonNode out) {
    if (out == null || out.isNull()) return "";
    return out.isTextual() ? out.asText() : out.toString();
  }

  /** Kite answers a refused order with words rather than an error flag at times. */
  static boolean looksRefused(String text) {
    String t = text.toLowerCase(Locale.ROOT);
    return !t.contains("order_id") && (t.contains("error") || t.contains("rejected") || t.contains("insufficient")
        || t.contains("not allowed") || t.contains("log in") || t.contains("login"));
  }

  private static String clip(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }
}

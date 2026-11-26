package com.jizza.platform.usecase.finance.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.imports.Statement.Holding;
import com.jizza.platform.usecase.finance.imports.Statement.Unreadable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Holdings read from a connected broker's tools rather than a file: Zerodha's Kite MCP server
 * answers {@code get_holdings} and {@code get_mf_holdings} with Kite Connect's own JSON (a bare
 * array, or {@code {"data": [...]}} when paged). Read the same way as a holdings file, so the
 * review, the snapshot into the tax ledger and the undo are the file import's.
 */
final class SourceHoldings {

  private SourceHoldings() {}

  static Statement kite(JsonNode shares, JsonNode funds, LocalDate today) {
    List<Holding> out = new ArrayList<>();
    List<Unreadable> unreadable = new ArrayList<>();
    int line = 0;
    for (JsonNode h : rows(shares)) {
      line++;
      String symbol = Parsing.clip(h.path("tradingsymbol").asText(""), 120);
      BigDecimal qty = num(h, "quantity").add(num(h, "t1_quantity"));
      BigDecimal price = positive(h, "last_price", "close_price");
      if (symbol.isEmpty()) unreadable.add(new Unreadable(line, "No symbol"));
      else if (qty.signum() <= 0) continue; // sold today, or only pledged elsewhere: nothing held
      else if (price == null) unreadable.add(new Unreadable(line, "No price"));
      else {
        out.add(new Holding(null, symbol, isin(h.path("isin").asText(null)), BrokerFileImporter.assetClass(symbol),
            sub(symbol), qty, qty.multiply(price).setScale(2, RoundingMode.HALF_UP), today, positive(h, "average_price")));
      }
    }
    for (JsonNode h : rows(funds)) {
      line++;
      String isin = isin(h.path("tradingsymbol").asText(null));
      String name = Parsing.clip(h.path("fund").asText(""), 120);
      BigDecimal qty = num(h, "quantity");
      BigDecimal price = positive(h, "last_price");
      if (name.isEmpty() && isin == null) unreadable.add(new Unreadable(line, "No fund name"));
      else if (qty.signum() <= 0) continue;
      else if (price == null) unreadable.add(new Unreadable(line, "No price"));
      else {
        out.add(new Holding(null, name.isEmpty() ? isin : name, isin, null, "mf", qty,
            qty.multiply(price).setScale(2, RoundingMode.HALF_UP), today, positive(h, "average_price")));
      }
    }
    return new Statement(List.of(), out, unreadable, List.of(), null, null);
  }

  /**
   * The cash left in the trading account, from Kite's {@code get_margins}: the equity segment's
   * live balance (or its cash, or its net). Null when the answer has none, so nothing is guessed.
   */
  static Statement.Balance kiteCash(JsonNode margins, LocalDate today) {
    JsonNode n = margins;
    if (n != null && n.isTextual()) {
      try {
        n = Json.mapper().readTree(n.asText());
      } catch (Exception notJson) {
        return null;
      }
    }
    if (n == null || !n.isObject()) return null;
    if (n.has("data")) n = n.get("data");
    JsonNode equity = n.path("equity");
    if (!equity.isObject()) return null;
    JsonNode available = equity.path("available");
    for (JsonNode v : List.of(available.path("live_balance"), available.path("cash"), equity.path("net"))) {
      if (v.isNumber() || v.isTextual()) {
        BigDecimal amount = num(v);
        if (amount != null && amount.signum() >= 0) return new Statement.Balance(amount.setScale(2, RoundingMode.HALF_UP), today);
      }
    }
    return null;
  }

  private static BigDecimal num(JsonNode v) {
    if (v.isNumber()) return v.decimalValue();
    try {
      return new BigDecimal(v.asText().trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** The items of a tool's answer: an array, a {@code data} array, or JSON text holding either. */
  static List<JsonNode> rows(JsonNode answer) {
    JsonNode n = answer;
    if (n != null && n.isTextual()) {
      try {
        n = Json.mapper().readTree(n.asText());
      } catch (Exception notJson) {
        return List.of();
      }
    }
    if (n != null && n.isObject() && n.has("data")) n = n.get("data");
    List<JsonNode> out = new ArrayList<>();
    if (n != null && n.isArray()) n.forEach(x -> { if (x.isObject()) out.add(x); });
    return out;
  }

  private static BigDecimal num(JsonNode h, String k) {
    JsonNode v = h.path(k);
    if (v.isNumber()) return v.decimalValue();
    if (v.isTextual()) {
      try {
        return new BigDecimal(v.asText().trim());
      } catch (NumberFormatException e) {
        return BigDecimal.ZERO;
      }
    }
    return BigDecimal.ZERO;
  }

  private static BigDecimal positive(JsonNode h, String... keys) {
    for (String k : keys) {
      BigDecimal v = num(h, k);
      if (v.signum() > 0) return v;
    }
    return null;
  }

  private static String isin(String raw) {
    String s = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
    return s.matches("[A-Z]{2}[A-Z0-9]{9}\\d") ? s : null;
  }

  private static String sub(String symbol) {
    String n = symbol.toUpperCase(Locale.ROOT);
    if (n.startsWith("SGB")) return "sgb";
    if (n.contains("BEES") || n.contains("ETF")) return "etf";
    return "shares";
  }
}

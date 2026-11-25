package com.jizza.platform.usecase.finance.imports;

import com.jizza.engine.api.error.ValidationException;
import com.jizza.platform.usecase.finance.imports.HeaderFinder.Found;
import com.jizza.platform.usecase.finance.imports.HeaderFinder.Role;
import com.jizza.platform.usecase.finance.imports.Statement.Columns;
import com.jizza.platform.usecase.finance.imports.Statement.Holding;
import com.jizza.platform.usecase.finance.imports.Statement.Txn;
import com.jizza.platform.usecase.finance.imports.Statement.Unreadable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A broker's holdings CSV (one row per share, ETF or bond held today) or tradebook CSV (one row per
 * trade). Columns are found from their header words, so the common exports from Indian brokers
 * work without a broker-specific parser.
 */
final class BrokerFileImporter {

  private BrokerFileImporter() {}

  private static final List<String> NAMES = List.of("instrument", "symbol", "tradingsymbol",
      "trading symbol", "scrip", "scrip name", "stock", "stock name", "security", "security name",
      "company", "company name", "name");

  private static final List<Role> HOLDINGS = List.of(
      Role.of("isin", List.of("isin", "isin code"), List.of("isin")),
      Role.of("name", NAMES, List.of("instrument", "symbol", "scrip", "security", "stock")),
      Role.of("qty", List.of("qty", "quantity", "quantity available", "qty available", "available qty",
          "holding qty", "holdings", "units", "shares", "balance qty", "total qty", "net qty"), List.of("quantity", "qty")),
      Role.of("value", List.of("cur val", "current value", "market value", "present value", "value",
          "valuation", "closing value"), List.of("current value", "market value", "cur val")),
      Role.of("price", List.of("ltp", "last price", "last traded price", "close price", "closing price",
          "previous closing price", "current price", "market price", "cmp"), List.of("ltp", "closing price", "close price")),
      Role.of("avg", List.of("avg cost", "avg price", "average price", "average cost", "buy avg",
          "avg buy price", "buy average"), List.of("avg", "average")));

  private static final List<Role> TRADES = List.of(
      Role.of("isin", List.of("isin", "isin code"), List.of("isin")),
      Role.of("name", NAMES, List.of("instrument", "symbol", "scrip", "security", "stock")),
      Role.of("date", List.of("trade date", "date", "order execution time", "execution time", "trade time"),
          List.of("trade date", "date")),
      Role.of("type", List.of("trade type", "type", "buy sell", "side", "transaction type", "action",
          "b s", "buy or sell"), List.of("trade type", "buy sell")),
      Role.of("qty", List.of("quantity", "qty", "traded qty", "trade qty"), List.of("quantity", "qty")),
      Role.of("price", List.of("price", "trade price", "rate", "avg price", "traded price", "average price"),
          List.of("price", "trade price")),
      Role.of("tradeId", List.of("trade id", "trade no", "trade number", "order id", "order no"),
          List.of("trade id", "trade no")));

  static Statement holdings(byte[] bytes, LocalDate today, int maxRows) {
    List<List<String>> rows = Csv.read(bytes, maxRows + HeaderFinder.SEARCH_ROWS);
    Found f = HeaderFinder.find(rows, HOLDINGS, r -> r.contains("name") && r.contains("qty")
        && (r.contains("value") || r.contains("price")));
    if (f == null || !(f.index().containsKey("name") && f.index().containsKey("qty")
        && (f.index().containsKey("value") || f.index().containsKey("price")))) {
      throw new ValidationException("Kai could not find the name, quantity and value columns of this holdings file.");
    }
    List<Holding> out = new ArrayList<>();
    List<Unreadable> unreadable = new ArrayList<>();
    for (int r = f.headerRow() + 1; r < rows.size(); r++) {
      List<String> row = rows.get(r);
      if (row.stream().allMatch(String::isBlank)) continue;
      String name = Parsing.clip(f.cell(row, "name"), 120);
      if (name.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith("total")) continue;
      try {
        Parsing.Amount qty = Parsing.amount(f.cell(row, "qty"));
        Parsing.Amount value = Parsing.amount(f.cell(row, "value"));
        Parsing.Amount price = Parsing.amount(f.cell(row, "price"));
        if (qty == null || qty.value().signum() <= 0) {
          unreadable.add(new Unreadable(r + 1, "No quantity"));
          continue;
        }
        BigDecimal worth = value != null ? value.value().abs()
            : price != null ? qty.value().multiply(price.value().abs()).setScale(2, RoundingMode.HALF_UP) : null;
        if (worth == null) {
          unreadable.add(new Unreadable(r + 1, "No price or value"));
          continue;
        }
        String isin = isin(f.cell(row, "isin"));
        out.add(new Holding(null, name, isin, assetClass(name), subCategory(name), qty.value(), worth, today));
      } catch (NumberFormatException bad) {
        unreadable.add(new Unreadable(r + 1, "A figure Kai can't read"));
      }
    }
    if (out.isEmpty() && unreadable.isEmpty()) throw new ValidationException("This holdings file has no rows.");
    return new Statement(List.of(), out, unreadable, List.of(), null,
        new Columns(f.headerRow(), f.headers(), f.mapping(), true, List.of()));
  }

  static Statement tradebook(byte[] bytes, int maxRows) {
    List<List<String>> rows = Csv.read(bytes, maxRows + HeaderFinder.SEARCH_ROWS);
    Set<String> need = Set.of("name", "date", "type", "qty", "price");
    Found f = HeaderFinder.find(rows, TRADES, r -> r.containsAll(need));
    if (f == null || !f.index().keySet().containsAll(need)) {
      throw new ValidationException("Kai could not find the symbol, date, buy or sell, quantity and price columns of this tradebook.");
    }
    List<Txn> txns = new ArrayList<>();
    List<Unreadable> unreadable = new ArrayList<>();
    for (int r = f.headerRow() + 1; r < rows.size(); r++) {
      List<String> row = rows.get(r);
      if (row.stream().allMatch(String::isBlank)) continue;
      int line = r + 1;
      String name = Parsing.clip(f.cell(row, "name"), 120);
      LocalDate date = Parsing.date(f.cell(row, "date"));
      String side = Parsing.words(f.cell(row, "type"));
      String kind = side.equals("buy") || side.equals("b") || side.equals("purchase") ? "buy"
          : side.equals("sell") || side.equals("s") || side.equals("sale") ? "sell" : null;
      if (name.isEmpty() && date == null) continue;
      if (date == null) {
        unreadable.add(new Unreadable(line, "A date Kai can't read"));
        continue;
      }
      if (kind == null) {
        unreadable.add(new Unreadable(line, "Kai can't tell if this is a buy or a sell"));
        continue;
      }
      try {
        Parsing.Amount qty = Parsing.amount(f.cell(row, "qty"));
        Parsing.Amount price = Parsing.amount(f.cell(row, "price"));
        if (qty == null || price == null || qty.value().signum() == 0) {
          unreadable.add(new Unreadable(line, "No quantity or price"));
          continue;
        }
        BigDecimal units = qty.value().abs();
        BigDecimal amount = units.multiply(price.value().abs()).setScale(2, RoundingMode.HALF_UP);
        String tradeId = Parsing.clip(f.cell(row, "tradeId"), 40);
        String ref = (isin(f.cell(row, "isin")) == null ? name : isin(f.cell(row, "isin")))
            + (tradeId.isEmpty() ? "" : "#" + tradeId);
        txns.add(new Txn(line, date, amount, "buy".equals(kind) ? "out" : "in", name, kind,
            "buy".equals(kind) ? units : units.negate(), price.value().abs(), ref, null, true));
      } catch (NumberFormatException bad) {
        unreadable.add(new Unreadable(line, "A figure Kai can't read"));
      }
    }
    if (txns.isEmpty() && unreadable.isEmpty()) throw new ValidationException("This tradebook has no trades.");
    return Statement.of(txns, unreadable, null, new Columns(f.headerRow(), f.headers(), f.mapping(), true, List.of()));
  }

  private static String isin(String raw) {
    String s = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
    return s.matches("[A-Z]{2}[A-Z0-9]{9}\\d") ? s : null;
  }

  /** Gold ETFs and sovereign gold bonds count as gold, debt ETFs as debt, the rest as shares. */
  static String assetClass(String name) {
    String n = name.toUpperCase(Locale.ROOT);
    if (n.startsWith("SGB") || n.contains("GOLD") || n.contains("SILVER")) return "gold";
    if (n.contains("LIQUID") || n.contains("GILT") || n.contains("BOND") || n.contains("BHARAT BOND")) return "debt";
    return "equity";
  }

  private static String subCategory(String name) {
    String n = name.toUpperCase(Locale.ROOT);
    if (n.startsWith("SGB")) return "sgb";
    if (n.contains("BEES") || n.contains("ETF")) return "etf";
    return "shares";
  }
}

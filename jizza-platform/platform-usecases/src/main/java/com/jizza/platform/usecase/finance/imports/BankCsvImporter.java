package com.jizza.platform.usecase.finance.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.platform.usecase.finance.imports.HeaderFinder.Found;
import com.jizza.platform.usecase.finance.imports.HeaderFinder.Role;
import com.jizza.platform.usecase.finance.imports.Statement.Balance;
import com.jizza.platform.usecase.finance.imports.Statement.Columns;
import com.jizza.platform.usecase.finance.imports.Statement.Txn;
import com.jizza.platform.usecase.finance.imports.Statement.Unreadable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Any bank's statement saved as CSV. Kai finds the header row and the date, description, debit,
 * credit (or one signed amount, with or without a Dr/Cr column) and balance columns from the
 * header words that Indian banks use; HDFC, SBI and Canara layouts are found this way and so is
 * any bank that names its columns plainly. When Kai cannot tell, the person chooses the columns,
 * and that choice is kept for the account's next statement.
 */
final class BankCsvImporter {

  private BankCsvImporter() {}

  static final Set<String> ROLES = Set.of("date", "description", "debit", "credit", "amount", "drCr", "balance");

  private static final List<Role> FIND = List.of(
      Role.of("date", List.of("txn date", "transaction date", "tran date", "date", "trans date",
          "posting date", "value date", "value dt", "txn posted date"), List.of("transaction date", "txn date")),
      Role.of("description", List.of("narration", "description", "particulars", "transaction details",
          "details", "transaction remarks", "remarks", "transaction description"), List.of("narration", "description", "particular")),
      Role.of("drCr", List.of("dr cr", "cr dr", "debit credit", "credit debit", "dr cr indicator",
          "type", "txn type", "transaction type"), List.of()),
      Role.of("debit", List.of("dr", "debit", "withdrawal", "withdrawals", "debits"),
          List.of("withdrawal", "debit", "dr amount", "amount debited")),
      Role.of("credit", List.of("cr", "credit", "deposit", "deposits", "credits"),
          List.of("deposit", "credit", "cr amount", "amount credited")),
      Role.of("balance", List.of("balance", "closing balance", "running balance", "available balance"),
          List.of("balance", "closing balance")),
      Role.of("amount", List.of("amount", "transaction amount", "txn amount", "amount inr", "amount rs"),
          List.of("amount", "transaction amount")));

  static Statement parse(byte[] bytes, JsonNode mapping, int maxRows) {
    return parse(bytes, mapping, null, maxRows);
  }

  /**
   * {@code chosen} is the person's column choice for this file; {@code remembered} is the one they
   * made for this account before, used only when Kai cannot find the columns itself.
   */
  static Statement parse(byte[] bytes, JsonNode chosen, JsonNode remembered, int maxRows) {
    List<List<String>> rows = Csv.read(bytes, maxRows + HeaderFinder.SEARCH_ROWS);
    if (rows.isEmpty()) throw new ValidationException("The file is empty.");
    Found f;
    if (chosen != null && chosen.isObject() && chosen.has("headerRow")) {
      f = HeaderFinder.chosen(rows, chosen, ROLES);
    } else {
      f = HeaderFinder.find(rows, FIND, BankCsvImporter::enough);
      if ((f == null || !enough(f.index().keySet())) && remembered != null) {
        var again = HeaderFinder.locate(rows, remembered);
        if (again != null) f = HeaderFinder.chosen(rows, again, ROLES);
      }
    }
    boolean sure = f != null && enough(f.index().keySet());
    if (!sure) {
      int from = f == null ? 0 : f.headerRow();
      var columns = new Columns(f == null ? 0 : f.headerRow(), f == null ? List.of() : f.headers(),
          f == null ? null : f.mapping(), false, HeaderFinder.sample(rows, 0, Math.max(8, from + 6)));
      return Statement.of(List.of(), List.of(), null, columns);
    }
    List<Txn> txns = new ArrayList<>();
    List<Unreadable> unreadable = new ArrayList<>();
    List<Txn> withBalance = new ArrayList<>();
    List<BigDecimal> balances = new ArrayList<>();
    for (int r = f.headerRow() + 1; r < rows.size(); r++) {
      List<String> row = rows.get(r);
      int line = r + 1;
      if (row.stream().allMatch(String::isBlank)) continue;
      LocalDate date = Parsing.date(f.cell(row, "date"));
      Parsing.Amount debit;
      Parsing.Amount credit;
      Parsing.Amount amount;
      Parsing.Amount balance;
      try {
        debit = Parsing.amount(f.cell(row, "debit"));
        credit = Parsing.amount(f.cell(row, "credit"));
        amount = Parsing.amount(f.cell(row, "amount"));
      } catch (NumberFormatException bad) {
        if (date != null) unreadable.add(new Unreadable(line, "An amount Kai can't read"));
        continue;
      }
      try {
        balance = Parsing.amount(f.cell(row, "balance"));
      } catch (NumberFormatException bad) {
        balance = null;
      }
      boolean anyAmount = nonZero(debit) || nonZero(credit) || nonZero(amount);
      if (date == null) {
        // Opening-balance lines, totals and footers have no date; only flag rows that look like money moving.
        if (anyAmount && !Parsing.clip(f.cell(row, "date"), 40).isEmpty()) {
          unreadable.add(new Unreadable(line, "A date Kai can't read"));
        }
        continue;
      }
      String direction;
      BigDecimal value;
      if (nonZero(debit) && nonZero(credit)) {
        unreadable.add(new Unreadable(line, "Both a debit and a credit"));
        continue;
      } else if (nonZero(debit)) {
        direction = "out";
        value = debit.value().abs();
      } else if (nonZero(credit)) {
        direction = "in";
        value = credit.value().abs();
      } else if (nonZero(amount)) {
        String side = side(f.cell(row, "drCr"), amount);
        if (side == null) {
          unreadable.add(new Unreadable(line, "Kai can't tell if this is money in or out"));
          continue;
        }
        direction = side;
        value = amount.value().abs();
      } else {
        unreadable.add(new Unreadable(line, "No amount"));
        continue;
      }
      String description = Parsing.clip(f.cell(row, "description"), 500);
      Txn t = new Txn(line, date, value, direction, description,
          "in".equals(direction) ? "income" : "spend", null, null, null, null, true);
      txns.add(t);
      if (balance != null) {
        BigDecimal b = balance.value();
        if ("dr".equals(balance.drCr())) b = b.abs().negate();
        withBalance.add(t);
        balances.add(b);
      }
    }
    return Statement.of(txns, unreadable, closing(withBalance, balances),
        new Columns(f.headerRow(), f.headers(), f.mapping(), true, List.of()));
  }

  private static boolean enough(Set<String> roles) {
    return roles.contains("date") && roles.contains("description")
        && ((roles.contains("debit") && roles.contains("credit")) || roles.contains("amount"));
  }

  private static boolean nonZero(Parsing.Amount a) {
    return a != null && a.value().signum() != 0;
  }

  /** "in" or "out" for a single amount column: its Dr/Cr column, its own Dr/Cr suffix, or its sign. */
  static String side(String drCrCell, Parsing.Amount amount) {
    String w = Parsing.words(drCrCell);
    if (!w.isEmpty()) {
      if (w.equals("dr") || w.equals("d") || w.startsWith("debit") || w.startsWith("withdraw")) return "out";
      if (w.equals("cr") || w.equals("c") || w.startsWith("credit") || w.startsWith("deposit")) return "in";
      return null;
    }
    if (amount.drCr() != null) return amount.drCr().equals("dr") ? "out" : "in";
    return amount.value().signum() < 0 ? "out" : "in";
  }

  /**
   * The balance after the last transaction: the row with the latest date, and among rows of that
   * date the last one in the file's own order (statements run oldest first or newest first).
   */
  private static Balance closing(List<Txn> rows, List<BigDecimal> balances) {
    if (rows.isEmpty()) return null;
    boolean newestFirst = rows.get(0).date().isAfter(rows.get(rows.size() - 1).date());
    LocalDate latest = rows.stream().map(Txn::date).max(LocalDate::compareTo).orElseThrow();
    int pick = -1;
    for (int i = 0; i < rows.size(); i++) {
      if (!rows.get(i).date().equals(latest)) continue;
      if (newestFirst) {
        pick = i;
        break;
      }
      pick = i;
    }
    return new Balance(balances.get(pick), latest);
  }
}

package com.jizza.platform.usecase.finance.imports;

import com.jizza.engine.api.error.ValidationException;
import com.jizza.platform.usecase.finance.imports.Statement.Balance;
import com.jizza.platform.usecase.finance.imports.Statement.Txn;
import com.jizza.platform.usecase.finance.imports.Statement.Unreadable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Any bank's PDF statement, after PDFBox has turned it into text. A PDF has no columns, so a
 * blank withdrawal or deposit cell simply disappears from the line. Kai therefore reads each
 * transaction as a date, words, and then the amount and the balance after it, and tells money in
 * from money out by how the balance moved. The first row needs the opening balance (or its own
 * Dr/Cr mark) for that. A row whose balance does not add up, or whose side Kai cannot tell, is
 * listed and never guessed. Narrations that wrap onto the next lines are joined back on.
 */
final class BankPdfImporter {

  private BankPdfImporter() {}

  private static final String MONTH = "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*";
  private static final String DATE = "(?:\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}|\\d{1,2}[ -]" + MONTH + "[ ,-]*\\d{2,4})";
  private static final Pattern STARTS_WITH_DATE = Pattern.compile("(?i)^(" + DATE + ")\\b\\s*(.*)$");
  private static final Pattern A_DATE = Pattern.compile("(?i)(?<![\\w/.-])" + DATE + "(?![\\w/-])");
  /** A money figure: always two decimals, so cheque and reference numbers are never taken for one. */
  private static final String MONEY = "\\(?-?(?:₹|INR|Rs\\.?)?\\s?\\d[\\d,]*\\.\\d{2}\\)?(?:\\s?\\(?(?:Cr|Dr|CR|DR)\\)?\\.?)?";
  private static final Pattern TAIL = Pattern.compile("(?:\\s+|^)(" + MONEY + ")\\s*$");
  private static final Pattern OPENING = Pattern.compile("(?i)(?:opening\\s+balance|balance\\s+b/?f|brought\\s+forward)\\b[^\\d(-]*(" + MONEY + ")?");
  private static final Pattern FIRST_MONEY = Pattern.compile("^\\s*(" + MONEY + ")(?:\\s|$)");
  /** Page furniture that ends a wrapped narration: headers, footers, totals and summaries. */
  private static final Pattern FURNITURE = Pattern.compile("(?i)\\b(page\\s*(no\\.?)?\\s*\\d|page\\s+\\d+\\s+of|statement|opening\\s+balance|closing\\s+bal|"
      + "total|withdrawal|deposit\\s+amt|narration|particulars|balance\\s+b/?f|brought\\s+forward|carried\\s+forward|"
      + "generated\\s+on|account\\s+(no|number)|customer\\s+id|ifsc|micr|branch|computer\\s+generated|"
      + "does\\s+not\\s+require|registered\\s+office|abbreviations|dr\\s+count|cr\\s+count)\\b");
  private static final int MAX_WRAP = 3;

  /** One transaction as written, before Kai knows its side. */
  private record Row(int line, LocalDate date, String description, List<Parsing.Amount> money) {}

  static Statement parse(String text, int maxRows) {
    String[] lines = text == null ? new String[0] : text.split("\\R");
    List<Row> rows = new ArrayList<>();
    List<Unreadable> unreadable = new ArrayList<>();
    BigDecimal opening = null;

    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      if (line.isEmpty()) continue;
      if (opening == null) {
        Matcher o = OPENING.matcher(line);
        if (o.find()) {
          opening = o.group(1) != null ? signed(o.group(1))
              : i + 1 < lines.length ? firstMoney(lines[i + 1]) : null;
          continue;
        }
      }
      Matcher start = STARTS_WITH_DATE.matcher(line);
      if (!start.matches()) continue;
      LocalDate date = Parsing.date(start.group(1));
      if (date == null) continue;
      if (rows.size() >= maxRows) throw new Csv.TooLong();

      // The transaction runs on until the next dated line, a blank, or page furniture.
      List<String> block = new ArrayList<>();
      block.add(start.group(2));
      int j = i + 1;
      while (j < lines.length && block.size() <= MAX_WRAP) {
        String next = lines[j].strip();
        if (next.isEmpty() || STARTS_WITH_DATE.matcher(next).matches() || FURNITURE.matcher(next).find()) break;
        block.add(next);
        j++;
      }
      // The figures are at the end of whichever line holds them (the first, or the last of a wrap).
      int at = -1;
      List<Parsing.Amount> money = List.of();
      String rest = null;
      for (int k = 0; k < block.size(); k++) {
        List<String> tail = new ArrayList<>();
        String s = block.get(k);
        Matcher m;
        while ((m = TAIL.matcher(s)).find()) {
          tail.add(0, m.group(1));
          s = s.substring(0, m.start());
        }
        if (tail.size() >= 2) {
          at = k;
          money = amounts(tail);
          rest = s;
          break;
        }
      }
      if (at < 0 || money == null) {
        // A dated line with no amount and balance is a heading or a note, unless it has figures.
        if (TAIL.matcher(line).find()) unreadable.add(new Unreadable(i + 1, "Kai can't find this row's amount and balance"));
        continue;
      }
      List<String> words = new ArrayList<>(block);
      words.set(at, rest);
      String description = A_DATE.matcher(String.join(" ", words)).replaceAll(" ");
      rows.add(new Row(i + 1, date, Parsing.clip(description, 500), money));
      i = j - 1;
    }

    if (rows.isEmpty()) {
      if (!unreadable.isEmpty()) return Statement.of(List.of(), unreadable, null, null);
      throw new ValidationException("Kai couldn't find any transactions in this PDF. If it is a scanned picture,"
          + " download the statement as CSV (or Excel saved as CSV) instead.");
    }

    // Work along the balance in time order; statements can run newest first.
    boolean newestFirst = rows.get(0).date().isAfter(rows.get(rows.size() - 1).date());
    List<Row> ordered = new ArrayList<>(rows);
    if (newestFirst) Collections.reverse(ordered);
    List<Txn> txns = new ArrayList<>();
    BigDecimal before = opening;
    Row last = null;
    BigDecimal lastBalance = null;
    for (Row r : ordered) {
      List<Parsing.Amount> m = r.money();
      BigDecimal balance = value(m.get(m.size() - 1));
      String direction = null;
      BigDecimal amount = null;
      if (m.size() == 3) {
        // Withdrawal, deposit and balance all written, one of the first two as 0.00.
        BigDecimal out = m.get(0).value().abs();
        BigDecimal in = m.get(1).value().abs();
        if (out.signum() != 0 && in.signum() == 0) { direction = "out"; amount = out; }
        else if (in.signum() != 0 && out.signum() == 0) { direction = "in"; amount = in; }
      } else if (m.size() == 2) {
        amount = m.get(0).value().abs();
        if (amount.signum() != 0) {
          if (before != null) {
            if (before.subtract(amount).compareTo(balance) == 0) direction = "out";
            else if (before.add(amount).compareTo(balance) == 0) direction = "in";
          }
          if (direction == null && m.get(0).drCr() != null) direction = "dr".equals(m.get(0).drCr()) ? "out" : "in";
        }
      }
      if (direction == null || amount == null || amount.signum() == 0) {
        unreadable.add(new Unreadable(r.line(), before == null && m.size() == 2
            ? "Kai can't tell if this is money in or out (no opening balance found)"
            : m.size() > 3 ? "Kai can't read this row's figures" : "The balance doesn't add up on this row"));
      } else {
        txns.add(new Txn(r.line(), r.date(), amount, direction, r.description(),
            "in".equals(direction) ? "income" : "spend", null, null, null, null, true));
      }
      before = balance;
      last = r;
      lastBalance = balance;
    }
    txns.sort((a, b) -> Integer.compare(a.line(), b.line()));
    unreadable.sort((a, b) -> Integer.compare(a.line(), b.line()));
    return Statement.of(txns, unreadable, last == null ? null : new Balance(lastBalance, last.date()), null);
  }

  private static List<Parsing.Amount> amounts(List<String> raw) {
    List<Parsing.Amount> out = new ArrayList<>();
    for (String s : raw) {
      try {
        Parsing.Amount a = Parsing.amount(s);
        if (a == null) return null;
        out.add(a);
      } catch (NumberFormatException bad) {
        return null;
      }
    }
    return out;
  }

  /** A balance with its sign: overdrawn when marked Dr or written negative. */
  private static BigDecimal value(Parsing.Amount a) {
    return "dr".equals(a.drCr()) ? a.value().abs().negate() : a.value();
  }

  private static BigDecimal signed(String raw) {
    try {
      Parsing.Amount a = Parsing.amount(raw);
      return a == null ? null : value(a);
    } catch (NumberFormatException bad) {
      return null;
    }
  }

  private static BigDecimal firstMoney(String line) {
    Matcher m = FIRST_MONEY.matcher(line.strip());
    return m.find() ? signed(m.group(1)) : null;
  }
}

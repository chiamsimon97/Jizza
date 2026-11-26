package com.jizza.platform.usecase.finance.imports;

import com.jizza.engine.api.error.ValidationException;
import com.jizza.platform.usecase.finance.imports.Statement.Group;
import com.jizza.platform.usecase.finance.imports.Statement.Holding;
import com.jizza.platform.usecase.finance.imports.Statement.Txn;
import com.jizza.platform.usecase.finance.imports.Statement.Unreadable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The consolidated account statement (CAS) that CAMS and KFintech send for all of a person's
 * mutual funds, after PDFBox has turned it into text. Reads each folio, each scheme with its
 * ISIN, the transactions (amount, units, NAV) and the closing units and value. Lines Kai cannot
 * read are listed, never guessed.
 */
final class CasImporter {

  private CasImporter() {}

  private static final String NUM = "\\(?-?[\\d,]+\\.\\d+\\)?";
  private static final Pattern FOLIO = Pattern.compile("(?i)Folio\\s*(?:No\\.?|Number)?\\s*[:.]?\\s*([0-9][0-9A-Za-z/ -]{2,30}?)(?=\\s{2,}|\\s+PAN|\\s+KYC|\\s+Nominee|\\s*$)");
  private static final Pattern ISIN = Pattern.compile("ISIN\\s*:?\\s*([A-Z]{2}[A-Z0-9]{9}\\d)");
  private static final Pattern AMC = Pattern.compile("^([A-Za-z0-9&.,'() -]{3,80}\\bMutual Fund)\\s*$");
  private static final Pattern TXN = Pattern.compile("^(\\d{1,2}-[A-Za-z]{3}-\\d{4})\\s+(.+?)\\s+((?:" + NUM + "\\s+)*" + NUM + ")\\s*$");
  private static final Pattern CLOSING_UNITS = Pattern.compile("(?i)Closing\\s+Unit\\s+Balance\\s*:?\\s*(" + NUM + ")");
  private static final Pattern NAV = Pattern.compile("(?i)NAV\\s+on\\s+(\\d{1,2}-[A-Za-z]{3}-\\d{4})\\s*:?\\s*(?:INR|Rs\\.?|₹)?\\s*(" + NUM + ")");
  private static final Pattern VALUE = Pattern.compile("(?i)(?:Market|Valuation)\\s+Value\\s+on\\s+(\\d{1,2}-[A-Za-z]{3}-\\d{4})\\s*:?\\s*(?:INR|Rs\\.?|₹)?\\s*(" + NUM + ")");

  static Statement parse(String text, int maxRows) {
    List<Txn> txns = new ArrayList<>();
    List<Holding> holdings = new ArrayList<>();
    List<Unreadable> unreadable = new ArrayList<>();
    Map<String, Group> groups = new LinkedHashMap<>();
    String provider = null;
    String folio = null;
    String scheme = null;
    String isin = null;
    String[] lines = text.split("\\R");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      int no = i + 1;
      if (line.isEmpty()) continue;
      Matcher m;
      if ((m = AMC.matcher(line)).matches()) {
        provider = Parsing.clip(m.group(1), 60);
        continue;
      }
      if ((m = FOLIO.matcher(line)).find()) {
        folio = m.group(1).replaceAll("\\s+", "").strip();
        String key = "folio:" + folio;
        String p = provider;
        String f = folio;
        groups.computeIfAbsent(key, k -> new Group(k, p, last4(f)));
        scheme = null;
        isin = null;
        continue;
      }
      if ((m = ISIN.matcher(line)).find()) {
        isin = m.group(1);
        scheme = schemeName(line.substring(0, m.start()));
        if (scheme.isEmpty()) scheme = isin;
        continue;
      }
      Matcher closing = CLOSING_UNITS.matcher(line);
      if (closing.find()) {
        if (scheme == null || folio == null) {
          unreadable.add(new Unreadable(no, "A closing balance with no scheme above it"));
          continue;
        }
        BigDecimal units = number(closing.group(1));
        Matcher value = VALUE.matcher(line);
        Matcher nav = NAV.matcher(line);
        // The value line sometimes wraps onto the next line of text.
        String both = line + (i + 1 < lines.length ? " " + lines[i + 1].strip() : "");
        if (!value.find()) value = VALUE.matcher(both);
        if (!nav.find()) nav = NAV.matcher(both);
        BigDecimal worth = null;
        LocalDate asOf = null;
        if (value.find(0)) {
          worth = number(value.group(2));
          asOf = Parsing.date(value.group(1));
        } else if (nav.find(0)) {
          worth = units.multiply(number(nav.group(2))).setScale(2, RoundingMode.HALF_UP);
          asOf = Parsing.date(nav.group(1));
        }
        if (worth == null) {
          unreadable.add(new Unreadable(no, "A closing balance with no value"));
          continue;
        }
        holdings.add(new Holding("folio:" + folio, Parsing.clip(scheme, 120), isin, assetClass(scheme),
            subCategory(scheme), units, worth.abs(), asOf, null));
        continue;
      }
      if ((m = TXN.matcher(line)).matches()) {
        if (txns.size() >= maxRows) throw new Csv.TooLong();
        LocalDate date = Parsing.date(m.group(1));
        String desc = Parsing.clip(m.group(2), 300);
        String[] nums = m.group(3).trim().split("\\s+");
        if (date == null || scheme == null || folio == null) {
          unreadable.add(new Unreadable(no, "A transaction with no scheme above it"));
          continue;
        }
        Txn t = nums.length == 4 ? unitsLine(no, date, desc, nums, folio, isin)
            : nums.length == 1 ? amountLine(no, date, desc, number(nums[0]), folio, isin) : null;
        if (t == null) unreadable.add(new Unreadable(no, "Kai can't read this line's figures"));
        else txns.add(t);
      }
    }
    if (groups.isEmpty() || (holdings.isEmpty() && txns.isEmpty())) {
      throw new ValidationException("This does not look like a CAMS or KFintech consolidated account statement (CAS).");
    }
    return new Statement(txns, holdings, unreadable, List.copyOf(groups.values()), null, null);
  }

  /** "01-Jan-2025  Purchase - SIP   5,000.00  123.456  40.5000  1,234.567": amount, units, NAV, units after. */
  private static Txn unitsLine(int no, LocalDate date, String desc, String[] nums, String folio, String isin) {
    BigDecimal amount = number(nums[0]);
    BigDecimal units = number(nums[1]);
    BigDecimal nav = number(nums[2]);
    if (units.signum() == 0) return null;
    String kind = kindOf(desc, units.signum() > 0);
    boolean known = kind != null;
    if (kind == null) kind = units.signum() > 0 ? "buy" : "sell";
    return new Txn(no, date, amount.abs(), direction(kind), desc, kind, units, nav, isin, "folio:" + folio, known);
  }

  /** A line with only an amount: stamp duty and taxes, or a dividend paid out. */
  private static Txn amountLine(int no, LocalDate date, String desc, BigDecimal amount, String folio, String isin) {
    String d = desc.toLowerCase(Locale.ROOT);
    String kind;
    if (d.contains("stamp duty") || d.contains("stt") || d.contains("tds") || d.contains("tax deducted")) kind = "fee";
    else if (d.contains("dividend") || d.contains("idcw")) kind = "dividend";
    else return null;
    return new Txn(no, date, amount.abs(), direction(kind), desc, kind, null, null, isin, "folio:" + folio, true);
  }

  /** The transaction kind from its description; null when the words don't say. */
  static String kindOf(String description, boolean unitsIn) {
    String d = description.toLowerCase(Locale.ROOT);
    if (d.contains("systematic withdrawal") || d.matches(".*\\bswp\\b.*")) return unitsIn ? null : "swp";
    if (d.contains("switch") || d.contains("systematic transfer") || d.matches(".*\\bstp\\b.*")) {
      return unitsIn ? "stp_in" : "stp_out";
    }
    if (d.contains("reinvest")) return unitsIn ? "dividend" : null;
    if (d.contains("redemption") || d.contains("redeem") || d.contains("repurchase")) return unitsIn ? null : "sell";
    if (d.contains("systematic investment") || d.contains("systematic purchase") || d.matches(".*\\bsip\\b.*")) {
      return unitsIn ? "sip" : null;
    }
    if (d.contains("purchase") || d.contains("investment") || d.contains("allotment") || d.contains("nfo")) {
      return unitsIn ? "buy" : null;
    }
    if (d.contains("reversal") || d.contains("rejection")) return null;
    return null;
  }

  /** Money from the person's side: buying units is money out, selling is money in. */
  static String direction(String kind) {
    return switch (kind) {
      case "buy", "sip", "stp_in", "fee" -> "out";
      default -> "in";
    };
  }

  private static String schemeName(String before) {
    String s = before.strip();
    s = s.replaceFirst("^[A-Z0-9]{2,12}\\s*-\\s*", "");
    s = s.replaceAll("[-–(\\s]+$", "");
    return s.strip();
  }

  /** The broad class from the scheme's own name; Phase 2's instruments refine it. */
  static String assetClass(String scheme) {
    String s = scheme.toLowerCase(Locale.ROOT);
    if (s.contains("gold") || s.contains("silver")) return "gold";
    for (String w : List.of("liquid", "overnight", "money market", "gilt", "debt", "bond", "corporate",
        "banking and psu", "banking & psu", "credit risk", "duration", "floater", "fixed maturity", "treasury")) {
      if (s.contains(w)) return "debt";
    }
    for (String w : List.of("hybrid", "balanced", "asset allocation", "multi asset", "arbitrage", "equity savings")) {
      if (s.contains(w)) return "other";
    }
    return "equity";
  }

  private static String subCategory(String scheme) {
    return "other".equals(assetClass(scheme)) ? "mutual_fund_hybrid" : "mutual_fund";
  }

  private static BigDecimal number(String raw) {
    Parsing.Amount a = Parsing.amount(raw);
    return a == null ? BigDecimal.ZERO : a.value();
  }

  static String last4(String folio) {
    String digits = folio.replaceAll("[^0-9A-Za-z]", "");
    int slash = folio.indexOf('/');
    if (slash > 0) digits = folio.substring(0, slash).replaceAll("[^0-9A-Za-z]", "");
    return digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
  }
}

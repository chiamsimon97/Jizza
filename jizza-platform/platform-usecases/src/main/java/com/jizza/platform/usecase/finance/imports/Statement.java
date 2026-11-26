package com.jizza.platform.usecase.finance.imports;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** What an importer read from one file, before anything is saved. */
record Statement(List<Txn> txns, List<Holding> holdings, List<Unreadable> unreadable, List<Group> groups,
                 Balance balance, Columns columns) {

  /**
   * One transaction. {@code direction} is money from the person's side: "out" is paid by them (a
   * spend, a purchase of units), "in" is received (a credit, a redemption). {@code group} is the
   * folio a CAS row belongs to; {@code kindKnown} is false when Kai could not tell the kind.
   */
  record Txn(int line, LocalDate date, BigDecimal amount, String direction, String description,
             String kind, BigDecimal units, BigDecimal price, String ref, String group, boolean kindKnown) {}

  /**
   * A holding at the statement's date. {@code group} as for {@link Txn}; {@code avgCost} is the
   * broker's average buy price when the file has one.
   */
  record Holding(String group, String name, String isin, String assetClass, String subCategory,
                 BigDecimal units, BigDecimal value, LocalDate asOf, BigDecimal avgCost) {}

  /** A line that looked like data but could not be read; it is listed and never imported. */
  record Unreadable(int line, String reason) {}

  /** A folio in a CAS: becomes (or matches) one mutual-fund account. */
  record Group(String key, String provider, String last4) {}

  /** The closing balance of a bank statement. */
  record Balance(BigDecimal amount, LocalDate date) {}

  /**
   * How the columns of a CSV were read. {@code sure} is false when Kai could not find the columns
   * it needs, and the person has to choose them. {@code sample} holds the first rows for that.
   */
  record Columns(int headerRow, List<String> headers, ObjectNode mapping, boolean sure, List<List<String>> sample) {}

  static Statement of(List<Txn> txns, List<Unreadable> unreadable, Balance balance, Columns columns) {
    return new Statement(txns, List.of(), unreadable, List.of(), balance, columns);
  }
}

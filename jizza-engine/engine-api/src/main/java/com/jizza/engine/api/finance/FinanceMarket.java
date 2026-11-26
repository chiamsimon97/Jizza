package com.jizza.engine.api.finance;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Kai Portfolio's shared market data (Phase 2): instruments, dated prices, exchange rates and
 * corporate actions. An instrument and its price are the same for everyone, so these rows have
 * no owner; a corporate action a user enters by hand is theirs ({@code userId} set) and is read
 * back only for them. Rows travel as JSON objects with camelCase names, like
 * {@link FinanceRecords}.
 */
public interface FinanceMarket {

  /** A price on the date it is for. */
  record Price(UUID instrumentId, LocalDate date, BigDecimal price, String source) {}

  /** Instruments whose name, symbol or ISIN contains {@code q}, at most {@code limit}. */
  List<ObjectNode> searchInstruments(String q, int limit);

  Optional<ObjectNode> instrument(UUID id);

  List<ObjectNode> instruments(Collection<UUID> ids);

  Optional<ObjectNode> instrumentByIsin(String isin);

  Optional<ObjectNode> instrumentBySymbol(String exchange, String symbol);

  /** Instruments with an AMFI scheme code, for the daily NAV fetch. */
  List<ObjectNode> instrumentsWithAmfiCode();

  /** Funds known by ISIN but not yet by AMFI code, so the NAV fetch can match and fill them in. */
  List<ObjectNode> fundsWithoutAmfiCode();

  ObjectNode insertInstrument(ObjectNode values);

  Optional<ObjectNode> updateInstrument(UUID id, ObjectNode values);

  /** Whether any account other than {@code owner} holds, lists or places this instrument. */
  boolean usedByOthers(UUID instrumentId, UUID owner);

  /** Saves prices, replacing a price already stored for the same instrument and date. */
  int putPrices(List<Price> prices);

  /** Prices from one user's own file or typing: they value only that user's holdings. */
  int putOwnPrices(UUID owner, List<Price> prices);

  /** The user's own price for that exact date, if they have one. */
  Optional<Price> ownPrice(UUID owner, UUID instrumentId, LocalDate date);

  void deleteOwnPrice(UUID owner, UUID instrumentId, LocalDate date);

  /** The latest price on or before {@code asOf} for each instrument that has one. */
  Map<UUID, Price> latestPrices(Collection<UUID> instrumentIds, LocalDate asOf);

  /**
   * The latest price on or before {@code asOf} from the public feed or {@code owner}'s own; on the
   * same date the public price wins.
   */
  Map<UUID, Price> latestPrices(Collection<UUID> instrumentIds, LocalDate asOf, UUID owner);

  /** Saves an exchange rate such as USD/INR for a date. */
  void putFxRate(String pair, LocalDate date, BigDecimal rate, String source);

  /** The latest rate on or before {@code asOf}. */
  Optional<BigDecimal> fxRate(String pair, LocalDate asOf);

  /** Shared (detected) actions plus the ones this user entered, oldest first. */
  List<ObjectNode> corporateActions(UUID userId, UUID instrumentId);

  ObjectNode insertCorporateAction(UUID userId, ObjectNode values);
}

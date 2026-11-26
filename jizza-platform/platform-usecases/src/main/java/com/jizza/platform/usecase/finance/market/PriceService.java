package com.jizza.platform.usecase.finance.market;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceMarket;
import com.jizza.engine.api.finance.FinanceMarket.Price;
import com.jizza.engine.api.finance.PriceFeed;
import com.jizza.engine.api.finance.PriceFeed.Nav;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.health.HouseholdLoader;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Prices (spec 5.15, Phase 2). Every price has the date it is for. Mutual fund NAVs come from
 * AMFI's public daily file, read through the platform's guarded client; share and ETF prices come
 * from the user's own broker files (the close price or value in a holdings file) or are typed in.
 * Nothing about the user is ever sent anywhere to get a price.
 */
@Service
public class PriceService {

  /** A price older than this many days is shown as stale. */
  public static final int STALE_DAYS = 5;

  private final FinanceMarket market;
  private final PriceFeed feed;
  private final FinScopeResolver scopes;
  private final AuditLog audit;

  public PriceService(FinanceMarket market, PriceFeed feed, FinScopeResolver scopes, AuditLog audit) {
    this.market = market;
    this.feed = feed;
    this.scopes = scopes;
    this.audit = audit;
  }

  public record Refresh(int fetched, int matched, int saved, LocalDate navDate) {}

  /**
   * Today's NAVs from AMFI for every instrument Kai knows by AMFI code or ISIN; an instrument
   * matched by ISIN learns its AMFI code for next time.
   */
  public Refresh refreshNavs() {
    List<Nav> navs = feed.amfiNavs();
    Map<String, Nav> byCode = new HashMap<>();
    Map<String, Nav> byIsin = new HashMap<>();
    LocalDate latest = null;
    for (Nav n : navs) {
      byCode.put(n.amfiCode(), n);
      if (n.isinGrowth() != null) byIsin.put(n.isinGrowth(), n);
      if (n.isinReinvest() != null) byIsin.put(n.isinReinvest(), n);
      if (latest == null || n.date().isAfter(latest)) latest = n.date();
    }
    List<Price> prices = new ArrayList<>();
    for (ObjectNode i : market.instrumentsWithAmfiCode()) {
      Nav n = byCode.get(i.path("amfiCode").asText());
      if (n != null) prices.add(new Price(UUID.fromString(i.path("id").asText()), n.date(), n.nav(), "amfi"));
    }
    // Funds known only by ISIN (added from a CAS): match, and keep the code.
    int matched = prices.size();
    for (ObjectNode i : market.fundsWithoutAmfiCode()) {
      Nav n = byIsin.get(i.path("isin").asText());
      if (n == null) continue;
      UUID id = UUID.fromString(i.path("id").asText());
      market.updateInstrument(id, Json.obj().put("amfiCode", n.amfiCode()));
      prices.add(new Price(id, n.date(), n.nav(), "amfi"));
      matched++;
    }
    int saved = market.putPrices(prices);
    return new Refresh(navs.size(), matched, saved, latest);
  }

  private final java.util.concurrent.atomic.AtomicReference<java.time.Instant> lastAsked = new java.util.concurrent.atomic.AtomicReference<>();

  /**
   * "Refresh prices" pressed by a user. The AMFI file is the same for everyone, so a press within
   * 15 minutes of the last one is turned away rather than fetching it again.
   */
  public Refresh refreshNow(UUID caller) {
    scopes.own(caller);
    java.time.Instant now = java.time.Instant.now();
    java.time.Instant last = lastAsked.get();
    if (last != null && last.isAfter(now.minus(java.time.Duration.ofMinutes(15)))) {
      throw new com.jizza.engine.api.error.RateLimitedException("Prices were refreshed a few minutes ago. Try again in a quarter of an hour.");
    }
    lastAsked.set(now);
    return refreshNavs();
  }

  /** A price the user types in for a date (a share with no broker file yet, say). */
  public Price putManual(UUID caller, UUID instrumentId, LocalDate date, BigDecimal price) {
    UUID owner = scopes.own(caller).ownerUserId();
    market.instrument(instrumentId).orElseThrow(() -> new com.jizza.engine.api.error.NotFoundException("Instrument", instrumentId));
    if (price == null || price.signum() <= 0) throw new ValidationException("A price is above zero.");
    LocalDate d = date == null ? HouseholdLoader.today() : date;
    if (d.isAfter(HouseholdLoader.today())) throw new ValidationException("A price cannot be for a future date.");
    Price p = new Price(instrumentId, d, price.setScale(4, RoundingMode.HALF_UP), "manual");
    market.putOwnPrices(owner, List.of(p));
    audit.record(owner, ActorType.USER, caller, "finance.price.entered", instrumentId.toString(), null);
    return p;
  }

  /**
   * A price read from the user's own file (value ÷ units), dated as the file is. Theirs alone; a
   * public price for the same day still wins when values are read. Returns what undo needs to put
   * back (the price it replaced, or none), or null when nothing was saved.
   */
  public ObjectNode fromImport(UUID owner, UUID instrumentId, LocalDate date, BigDecimal units, BigDecimal value) {
    if (units == null || value == null || units.signum() <= 0 || value.signum() <= 0 || date == null) return null;
    BigDecimal p = value.divide(units, 4, RoundingMode.HALF_UP);
    if (p.signum() <= 0) return null;
    ObjectNode undo = Json.obj().put("instrumentId", instrumentId.toString()).put("date", date.toString());
    market.ownPrice(owner, instrumentId, date).ifPresent(was -> undo.put("price", was.price()).put("source", was.source()));
    market.putOwnPrices(owner, List.of(new Price(instrumentId, date, p, "import")));
    return undo;
  }

  /** Puts back the prices an import replaced, newest change first. */
  public void undoImport(UUID owner, com.fasterxml.jackson.databind.JsonNode changes) {
    List<com.fasterxml.jackson.databind.JsonNode> list = new ArrayList<>();
    changes.forEach(list::add);
    java.util.Collections.reverse(list);
    for (var c : list) {
      UUID id = UUID.fromString(c.path("instrumentId").asText());
      LocalDate d = LocalDate.parse(c.path("date").asText());
      if (c.hasNonNull("price")) market.putOwnPrices(owner, List.of(new Price(id, d, c.get("price").decimalValue(), c.path("source").asText("import"))));
      else market.deleteOwnPrice(owner, id, d);
    }
  }

  public Map<UUID, Price> latest(UUID owner, Collection<UUID> ids, LocalDate asOf) {
    return market.latestPrices(ids, asOf, owner);
  }
}

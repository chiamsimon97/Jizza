package com.jizza.platform.usecase.finance.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceMarket;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.builder.AllocationResolver;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Instruments (spec 5.15, Phase 2): shares, ETFs, mutual funds and the rest, shared by everyone.
 * An instrument is found by ISIN, else by exchange and symbol, else by its exact name; an import
 * that meets a new one adds it with a class and sub-category read from its name, which the user
 * can correct. Kai never names a fund in a suggestion; an instrument here is only what the user
 * already holds or watches.
 */
@Service
public class InstrumentService {

  public static final Set<String> TYPES = Set.of("stock", "etf", "mf", "bond", "sgb", "reit", "us_stock", "us_etf", "other");
  public static final Set<String> CLASSES = Set.of("cash", "debt", "equity", "gold", "other");
  /** Direct shares: what a Short goal's template keeps out. */
  public static final Set<String> DIRECT_SHARES = Set.of("stock", "us_stock");

  private final FinanceMarket market;
  private final FinScopeResolver scopes;
  private final AuditLog audit;

  public InstrumentService(FinanceMarket market, FinScopeResolver scopes, AuditLog audit) {
    this.market = market;
    this.scopes = scopes;
    this.audit = audit;
  }

  public List<ObjectNode> search(UUID caller, String q) {
    scopes.own(caller);
    if (q == null || q.trim().length() < 2) return List.of();
    UUID owner = scopes.own(caller).ownerUserId();
    return market.searchInstruments(q.trim(), 20).stream().map(i -> shown(i, owner)).toList();
  }

  public ObjectNode get(UUID id) {
    return market.instrument(id).orElseThrow(() -> new NotFoundException("Instrument", id));
  }

  /** An instrument as a user sees it: whether they added it, never who else did. */
  static ObjectNode shown(ObjectNode i, UUID owner) {
    ObjectNode o = i.deepCopy();
    boolean mine = owner.toString().equals(o.path("addedBy").asText(null));
    o.remove("addedBy");
    return o.put("yours", mine);
  }

  /** Adds an instrument the user typed in (to watch it, or to record a holding by hand). */
  @Transactional
  public ObjectNode create(UUID caller, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode v = clean(body, true);
    if (v.hasNonNull("isin") && market.instrumentByIsin(v.get("isin").asText()).isPresent()) {
      throw new ConflictException("That ISIN is already listed. Search for it instead.");
    }
    if (v.hasNonNull("symbol") && v.hasNonNull("exchange")
        && market.instrumentBySymbol(v.get("exchange").asText(), v.get("symbol").asText()).isPresent()) {
      throw new ConflictException("That symbol is already listed. Search for it instead.");
    }
    fillClass(v);
    v.put("addedBy", owner.toString());
    ObjectNode row = market.insertInstrument(v);
    audit.record(owner, ActorType.USER, caller, "finance.instrument.added", row.path("id").asText(), null);
    return shown(row, owner);
  }

  /** Corrects an instrument's class, sub-category, cap bucket or tax class. */
  @Transactional
  public ObjectNode update(UUID caller, UUID id, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode current = get(id);
    if (!owner.toString().equals(current.path("addedBy").asText(null))) {
      // Shared with everyone who holds it: one user's correction would move other people's plans.
      throw new NotFoundException("Instrument", id);
    }
    if (market.usedByOthers(id, owner)) {
      throw new ConflictException("Other people hold this instrument now, so it can no longer be changed.");
    }
    ObjectNode v = clean(body, false);
    for (String locked : List.of("isin", "symbol", "exchange", "type")) {
      if (v.has(locked)) throw new ValidationException("An instrument's " + locked + " cannot change.");
    }
    if (v.has("capBucket")) v.put("bucketAsOf", java.time.LocalDate.now().toString());
    ObjectNode row = v.isEmpty() ? get(id) : market.updateInstrument(id, v).orElseThrow(() -> new NotFoundException("Instrument", id));
    audit.record(owner, ActorType.USER, caller, "finance.instrument.updated", id.toString(), null);
    return shown(row, owner);
  }

  /**
   * The instrument an import names: by ISIN, else by symbol (a broker's trading symbol, on NSE),
   * else by the exact name; added when none matches.
   */
  @Transactional
  public ObjectNode resolve(String isin, String name, String typeHint, String assetClassHint) {
    String cleanIsin = isin == null ? null : isin.trim().toUpperCase(Locale.ROOT);
    if (cleanIsin != null && !cleanIsin.matches("[A-Z]{2}[A-Z0-9]{9}\\d")) cleanIsin = null;
    if (cleanIsin != null) {
      var found = market.instrumentByIsin(cleanIsin);
      if (found.isPresent()) return found.get();
    }
    String n = name == null ? "" : name.trim();
    String symbol = symbolLike(n) ? n.toUpperCase(Locale.ROOT) : null;
    if (cleanIsin == null && symbol != null) {
      var found = market.instrumentBySymbol("NSE", symbol);
      if (found.isPresent()) return found.get();
    }
    if (cleanIsin == null && !n.isEmpty()) {
      var exact = market.searchInstruments(n, 5).stream()
          .filter(i -> i.path("name").asText().equalsIgnoreCase(n) && !i.hasNonNull("isin")).findFirst();
      if (exact.isPresent()) return exact.get();
    }
    ObjectNode v = Json.obj().put("name", n.isEmpty() ? (cleanIsin == null ? "Unnamed holding" : cleanIsin) : clip(n, 160))
        .put("type", typeHint == null ? guessType(cleanIsin, n) : typeHint);
    if (cleanIsin != null) v.put("isin", cleanIsin);
    if (symbol != null && cleanIsin == null) v.put("symbol", symbol).put("exchange", "NSE");
    if (assetClassHint != null && CLASSES.contains(assetClassHint)) v.put("assetClass", assetClassHint);
    fillClass(v);
    return market.insertInstrument(v);
  }

  /** A share code: letters, digits and a few marks, no spaces, at most 20 long. */
  static boolean symbolLike(String n) {
    return n.matches("[A-Za-z0-9&.\\-]{1,20}") && n.chars().anyMatch(Character::isLetter);
  }

  static String guessType(String isin, String name) {
    String n = name.toUpperCase(Locale.ROOT);
    if (isin != null && isin.startsWith("INF")) return n.contains("ETF") || n.contains("BEES") ? "etf" : "mf";
    if (n.startsWith("SGB")) return "sgb";
    if (n.contains("ETF") || n.contains("BEES")) return "etf";
    if (n.contains("FUND") || n.contains("SCHEME")) return "mf";
    return "stock";
  }

  /**
   * The class, sub-category and tax class read from the type and name when not given. A
   * sub-category is a tree key (large_cap_index, mid_cap…); an instrument Kai cannot place is
   * left without one and shows in "Needs a look".
   */
  static void fillClass(ObjectNode v) {
    String type = v.path("type").asText("other");
    String n = v.path("name").asText("").toLowerCase(Locale.ROOT);
    String cls = v.hasNonNull("assetClass") ? v.get("assetClass").asText() : null;
    String sub = v.hasNonNull("subCategory") ? v.get("subCategory").asText() : null;
    if (type.equals("sgb") || n.contains("gold") || n.contains("silver")) {
      cls = cls == null ? "gold" : cls;
      sub = sub == null ? "gold" : sub;
    } else if (type.equals("mf") || type.equals("etf")) {
      String[] guess = fundClass(n);
      if (cls == null) cls = guess[0];
      if (sub == null) sub = guess[1];
    } else if (type.equals("stock") || type.equals("us_stock") || type.equals("reit")) {
      if (cls == null) cls = type.equals("reit") ? "other" : "equity";
      if (sub == null && v.hasNonNull("capBucket")) sub = capSub(v.get("capBucket").asText());
    } else if (type.equals("bond")) {
      if (cls == null) cls = "debt";
    }
    v.put("assetClass", cls == null ? "other" : cls);
    if (sub != null) v.put("subCategory", sub);
    if (!v.hasNonNull("taxClass")) {
      String tax = switch (v.path("assetClass").asText()) {
        case "equity" -> "equity";
        case "debt", "cash" -> type.equals("mf") || type.equals("etf") ? "specified_debt" : "other";
        default -> "other";
      };
      v.put("taxClass", tax);
    }
  }

  /** {class, sub-category} of a fund from the words in its name; sub-category null when unclear. */
  static String[] fundClass(String n) {
    if (has(n, "liquid", "overnight", "money market")) return new String[] {"cash", "liquid"};
    if (has(n, "arbitrage", "ultra short", "ultra-short", "low duration")) return new String[] {"debt", "arbitrage_ultra_short"};
    if (has(n, "balanced advantage", "multi asset", "multi-asset", "dynamic asset", "hybrid", "equity savings")) {
      return new String[] {"other", "balanced_multi_asset"};
    }
    if (has(n, "gilt", "bond", "duration", "banking and psu", "banking & psu", "corporate", "credit risk",
        "floater", "treasury", "debt", "income")) return new String[] {"debt", "short_duration"};
    if (has(n, "small cap", "smallcap")) return new String[] {"equity", "small_cap"};
    if (has(n, "mid cap", "midcap")) return new String[] {"equity", "mid_cap"};
    if (has(n, "flexi cap", "flexicap", "multi cap", "multicap")) return new String[] {"equity", "flexi_cap"};
    if (has(n, "large cap", "largecap", "bluechip", "nifty 50", "nifty50", "sensex", "nifty next 50", "index")) {
      return new String[] {"equity", "large_cap_index"};
    }
    return new String[] {"equity", null};
  }

  /** A share's sub-category from its AMFI cap bucket. */
  public static String capSub(String bucket) {
    return switch (bucket == null ? "" : bucket) {
      case "large" -> "large_cap_index";
      case "mid" -> "mid_cap";
      case "small" -> "small_cap";
      default -> null;
    };
  }

  private static boolean has(String n, String... words) {
    for (String w : words) if (n.contains(w)) return true;
    return false;
  }

  private static ObjectNode clean(JsonNode body, boolean creating) {
    if (body == null || !body.isObject()) throw new ValidationException("Send the instrument as an object.");
    ObjectNode v = Json.obj();
    String name = text(body, "name", 160);
    if (creating && (name == null || name.isBlank())) throw new ValidationException("Give the instrument a name.");
    if (name != null) v.put("name", name);
    String type = text(body, "type", 10);
    if (type != null) {
      if (!TYPES.contains(type)) throw new ValidationException("type must be one of " + String.join(", ", TYPES) + ".");
      v.put("type", type);
    } else if (creating) {
      v.put("type", guessType(text(body, "isin", 12), name));
    }
    String isin = text(body, "isin", 12);
    if (isin != null && !isin.isBlank()) {
      isin = isin.toUpperCase(Locale.ROOT);
      if (!isin.matches("[A-Z]{2}[A-Z0-9]{9}\\d")) throw new ValidationException("That is not an ISIN (12 characters, like INE040A01034).");
      v.put("isin", isin);
    }
    String symbol = text(body, "symbol", 40);
    if (symbol != null && !symbol.isBlank()) v.put("symbol", symbol.toUpperCase(Locale.ROOT));
    String exchange = text(body, "exchange", 10);
    if (exchange != null && !exchange.isBlank()) v.put("exchange", exchange.toUpperCase(Locale.ROOT));
    String cls = text(body, "assetClass", 10);
    if (cls != null) {
      if (!CLASSES.contains(cls)) throw new ValidationException("assetClass must be cash, debt, equity, gold or other.");
      v.put("assetClass", cls);
    }
    String sub = text(body, "subCategory", 40);
    if (sub != null) {
      if (!sub.isEmpty() && !AllocationResolver.SUB_LABELS.containsKey(sub)) {
        throw new ValidationException("subCategory must be one of " + String.join(", ", AllocationResolver.SUB_LABELS.keySet()) + ".");
      }
      if (sub.isEmpty()) v.putNull("subCategory");
      else v.put("subCategory", sub);
    }
    String cap = text(body, "capBucket", 6);
    if (cap != null) {
      if (!Set.of("large", "mid", "small", "").contains(cap)) throw new ValidationException("capBucket is large, mid or small.");
      if (cap.isEmpty()) v.putNull("capBucket");
      else v.put("capBucket", cap);
    }
    String tax = text(body, "taxClass", 14);
    if (tax != null) {
      if (!Set.of("equity", "specified_debt", "other").contains(tax)) throw new ValidationException("taxClass is equity, specified_debt or other.");
      v.put("taxClass", tax);
    }
    String amfi = text(body, "amfiCode", 12);
    if (amfi != null && !amfi.isBlank()) {
      if (!amfi.matches("\\d{3,8}")) throw new ValidationException("An AMFI code is digits only.");
      v.put("amfiCode", amfi);
    }
    String currency = text(body, "currency", 3);
    if (currency != null && !currency.isBlank()) {
      if (!currency.matches("[A-Za-z]{3}")) throw new ValidationException("Currency is three letters, like INR.");
      v.put("currency", currency.toUpperCase(Locale.ROOT));
    }
    return v;
  }

  private static String text(JsonNode body, String field, int max) {
    JsonNode v = body.get(field);
    if (v == null || v.isNull()) return null;
    String s = v.asText().trim();
    if (s.length() > max) throw new ValidationException(field + " is at most " + max + " characters.");
    return s;
  }

  static String clip(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }

  /** The labels of the sub-categories, for the screens. */
  public static Map<String, String> subLabels() {
    return AllocationResolver.SUB_LABELS;
  }
}

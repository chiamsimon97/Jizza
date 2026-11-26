package com.jizza.platform.usecase.finance.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.finance.StatementFiles;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.cashflow.FinCashFlowUseCase;
import com.jizza.platform.usecase.finance.imports.Statement.Group;
import com.jizza.platform.usecase.finance.imports.Statement.Holding;
import com.jizza.platform.usecase.finance.imports.Statement.Txn;
import com.jizza.platform.usecase.finance.records.FinRecordsUseCase;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.jizza.platform.usecase.finance.ledger.EarmarkService;
import com.jizza.platform.usecase.finance.ledger.TaxLotLedger;
import com.jizza.platform.usecase.finance.market.InstrumentService;
import com.jizza.platform.usecase.finance.market.PriceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Statement imports (5.15, Phase 1c): a file is stored, read into a batch the person reviews, and
 * only then committed as transactions, balances and holdings; a committed batch can be undone and
 * every figure it changed goes back. The same file is refused twice, and the same transaction is
 * never counted twice. Nothing here logs an amount, a name or a file's contents, and a PDF's
 * password is used for the one read and then dropped.
 */
@Service
public class ImportService {

  /** The most transactions one file may bring in. */
  static final int MAX_ROWS = 5000;

  static final Set<String> KINDS = Set.of("bank_csv", "bank_pdf", "cas_pdf", "broker_holdings", "broker_tradebook");
  private static final Set<String> BANK_ACCOUNTS = Set.of("savings", "current", "credit_card", "wallet", "deposit", "other");
  private static final Set<String> BROKER_ACCOUNTS = Set.of("demat", "us_broker", "other");
  private static final Set<String> BALANCE_ACCOUNTS = Set.of("savings", "current");

  private final FinanceRecords records;
  private final FinRecordsUseCase forms;
  private final FinScopeResolver scopes;
  private final StatementFiles files;
  private final FinCashFlowUseCase cashFlow;
  private final AuditLog audit;
  private final EarmarkService ledger;
  private final InstrumentService instruments;
  private final PriceService prices;

  public ImportService(FinanceRecords records, FinRecordsUseCase forms, FinScopeResolver scopes,
                       StatementFiles files, FinCashFlowUseCase cashFlow, AuditLog audit,
                       EarmarkService ledger, InstrumentService instruments, PriceService prices) {
    this.records = records;
    this.forms = forms;
    this.scopes = scopes;
    this.files = files;
    this.cashFlow = cashFlow;
    this.audit = audit;
    this.ledger = ledger;
    this.instruments = instruments;
    this.prices = prices;
  }

  /** One upload. {@code password} is for a locked PDF and is never kept. */
  public record Upload(String kind, UUID personId, UUID accountId, JsonNode mapping, String password,
                       String filename, String contentType, byte[] bytes) {}

  /** The person's choices on the review screen: a category per row, and rows to make rules from. */
  public record Choices(Map<String, UUID> categories, Set<Integer> always) {}

  // ── Upload and preview ──────────────────────────────────────────────────────

  @Transactional
  public ObjectNode upload(UUID caller, Upload u) {
    return upload(scopes.own(caller).ownerUserId(), caller, u, null);
  }

  /**
   * A statement Kai found in the person's email (part B). Returns empty when this file is already
   * here, waiting for review or saved, so checking the mailbox again changes nothing.
   */
  @Transactional
  public java.util.Optional<ObjectNode> fromMail(UUID owner, Upload u, String sourceLabel) {
    if (u.bytes() == null || u.bytes().length == 0) return java.util.Optional.empty();
    String hash = Parsing.sha256(u.bytes());
    boolean have = records.list("fin_import_batch", owner, Map.of("contentHash", hash)).stream()
        .map(b -> b.path("status").asText())
        .anyMatch(st -> st.equals("committed") || st.equals("parsed") || st.equals("needs_review"));
    if (have) return java.util.Optional.empty();
    return java.util.Optional.of(upload(owner, null, u, sourceLabel));
  }

  private ObjectNode upload(UUID owner, UUID caller, Upload u, String source) {
    String kind = u.kind() == null ? "" : u.kind();
    if (!KINDS.contains(kind)) throw new ValidationException("Choose what kind of file this is.");
    if (u.bytes() == null || u.bytes().length == 0) throw new ValidationException("The file is empty.");

    ObjectNode account = null;
    UUID personId;
    if (kind.equals("cas_pdf")) {
      if (u.accountId() != null) throw new ValidationException("A CAS covers all of a person's funds; leave the account empty.");
      personId = u.personId() == null ? forms.selfId(owner) : u.personId();
      forms.requirePerson(owner, personId);
    } else {
      if (u.accountId() == null) throw new ValidationException("Choose the account this file is for.");
      account = records.get("fin_account", owner, u.accountId())
          .orElseThrow(() -> new NotFoundException("Account", u.accountId()));
      String accountKind = account.path("kind").asText();
      boolean fits = isBank(kind) ? BANK_ACCOUNTS.contains(accountKind) : BROKER_ACCOUNTS.contains(accountKind);
      if (!fits) {
        throw new ValidationException(isBank(kind)
            ? "A bank statement goes with a bank, card or wallet account."
            : "A broker file goes with a demat or broker account.");
      }
      personId = UUID.fromString(account.path("personId").asText());
      if (u.personId() != null && !u.personId().equals(personId)) {
        throw new ValidationException("That account belongs to someone else in your household.");
      }
    }

    String hash = Parsing.sha256(u.bytes());
    for (ObjectNode earlier : records.list("fin_import_batch", owner, Map.of("contentHash", hash))) {
      if ("committed".equals(earlier.path("status").asText())) {
        throw new ConflictException("You imported this file on " + earlier.path("createdAt").asText().substring(0, 10)
            + ". Undo that import first if you want to bring it in again.");
      }
      // Not saved yet, failed or undone: this upload takes its place (a new column choice, say).
      discard(owner, earlier);
    }

    Statement st = parse(owner, kind, account, u);
    ObjectNode stats = preview(owner, kind, account, personId, st);
    stats.put("fileName", Parsing.clip(u.filename() == null ? "statement" : u.filename(), 120));
    if (source != null) stats.put("source", Parsing.clip(source, 80));
    UUID fileId = files.store(owner, u.filename(), u.contentType(), u.bytes());

    boolean sure = st.columns() == null || st.columns().sure();
    ObjectNode batch = Json.obj()
        .put("personId", personId.toString())
        .put("kind", kind)
        .put("fileId", fileId.toString())
        .put("contentHash", hash)
        .put("status", sure ? "parsed" : "needs_review");
    if (account != null) batch.put("accountId", account.path("id").asText());
    if (st.columns() != null && st.columns().mapping() != null) batch.set("mapping", st.columns().mapping());
    if (!sure) batch.put("error", "Kai couldn't tell which column is which. Choose the columns to go on.");
    batch.set("stats", stats);
    ObjectNode saved = records.insert("fin_import_batch", owner, batch);
    audit.record(owner, caller == null ? ActorType.SYSTEM : ActorType.USER, caller,
        caller == null ? "finance.import.mailed" : "finance.import.parsed", saved.path("id").asText(),
        Json.obj().put("kind", kind));
    return saved;
  }

  // ── From a connected source ─────────────────────────────────────────────────

  /**
   * What a sync from a connected source made: the batch to review, or none when the holdings are
   * the same as the last saved sync for this account.
   */
  public record Synced(ObjectNode batch, boolean changed, int holdings) {}

  /**
   * Holdings read from a connected broker (its own tools' answers), as a holdings batch the person
   * reviews and saves like an uploaded holdings file. {@code owner} has been resolved by the caller.
   * An earlier unsaved sync for the account is replaced. Nothing is logged but counts.
   */
  @Transactional
  public Synced fromSource(UUID owner, UUID accountId, String sourceLabel, JsonNode shares, JsonNode funds) {
    return fromSource(owner, accountId, sourceLabel, shares, funds, null);
  }

  /**
   * As above, with the broker's {@code get_margins} answer: the cash left in the trading account
   * comes along as the account's balance, saved with the holdings. A sync whose holdings match the
   * last saved one still lands when that cash differs from the balance saved for the account.
   */
  @Transactional
  public Synced fromSource(UUID owner, UUID accountId, String sourceLabel, JsonNode shares, JsonNode funds,
                           JsonNode margins) {
    ObjectNode account = records.get("fin_account", owner, accountId)
        .orElseThrow(() -> new NotFoundException("Account", accountId));
    if (!BROKER_ACCOUNTS.contains(account.path("kind").asText())) {
      throw new ValidationException("A broker sync goes with a demat or broker account.");
    }
    Statement st = SourceHoldings.kite(shares, funds, LocalDate.now());
    if (st.holdings().isEmpty() && st.unreadable().isEmpty()) {
      throw new ValidationException(sourceLabel + " sent no holdings, so nothing was changed.");
    }
    List<String> lines = new ArrayList<>();
    for (Holding h : st.holdings()) {
      lines.add((h.isin() == null ? h.name() : h.isin()) + "|" + h.units().stripTrailingZeros().toPlainString());
    }
    lines.sort(null);
    String syncKey = Parsing.sha256(accountId + "|" + String.join(";", lines));

    String lastAt = "";
    String lastKey = null;
    for (ObjectNode b : records.list("fin_import_batch", owner, Map.of("accountId", accountId.toString(), "kind", "broker_holdings"))) {
      String status = b.path("status").asText();
      if (!b.path("stats").has("syncKey")) continue;
      if ("parsed".equals(status)) discard(owner, b);
      else if ("committed".equals(status) && b.path("createdAt").asText().compareTo(lastAt) > 0) {
        lastAt = b.path("createdAt").asText();
        lastKey = b.path("stats").path("syncKey").asText();
      }
    }
    Statement.Balance cash = SourceHoldings.kiteCash(margins, LocalDate.now());
    boolean cashMoved = cash != null && records.list("fin_asset", owner,
            Map.of("accountId", accountId.toString(), "kind", "savings_balance")).stream()
        .noneMatch(a -> a.path("value").decimalValue().compareTo(cash.amount()) == 0);
    if (syncKey.equals(lastKey) && st.unreadable().isEmpty() && !cashMoved) {
      return new Synced(null, false, st.holdings().size());
    }

    UUID personId = UUID.fromString(account.path("personId").asText());
    ObjectNode stats = preview(owner, "broker_holdings", account, personId, st);
    stats.put("fileName", Parsing.clip(sourceLabel, 80) + " sync").put("source", Parsing.clip(sourceLabel, 80))
        .put("syncKey", syncKey);
    if (cash != null) stats.putObject("balance").put("amount", cash.amount()).put("date", cash.date().toString());
    ObjectNode batch = Json.obj()
        .put("personId", personId.toString())
        .put("kind", "broker_holdings")
        .put("accountId", accountId.toString())
        .put("contentHash", Parsing.sha256(syncKey + "|" + OffsetDateTime.now()))
        .put("status", "parsed");
    batch.set("stats", stats);
    ObjectNode saved = records.insert("fin_import_batch", owner, batch);
    audit.record(owner, ActorType.SYSTEM, null, "finance.import.synced", saved.path("id").asText(),
        Json.obj().put("kind", "broker_holdings").put("holdings", st.holdings().size()));
    return new Synced(saved, true, st.holdings().size());
  }

  /** A bank statement, as CSV or as the PDF the bank mails. */
  static boolean isBank(String kind) {
    return "bank_csv".equals(kind) || "bank_pdf".equals(kind);
  }

  private Statement parse(UUID owner, String kind, ObjectNode account, Upload u) {
    try {
      return switch (kind) {
        case "bank_csv" -> BankCsvImporter.parse(u.bytes(), u.mapping(), remembered(owner, account, kind), MAX_ROWS);
        case "bank_pdf" -> BankPdfImporter.parse(files.pdfText(u.bytes(), u.password()), MAX_ROWS);
        case "cas_pdf" -> CasImporter.parse(files.pdfText(u.bytes(), u.password()), MAX_ROWS);
        case "broker_holdings" -> BrokerFileImporter.holdings(u.bytes(), u.mapping(), remembered(owner, account, kind),
            LocalDate.now(), MAX_ROWS);
        case "broker_tradebook" -> BrokerFileImporter.tradebook(u.bytes(), u.mapping(), remembered(owner, account, kind), MAX_ROWS);
        default -> throw new ValidationException("Choose what kind of file this is.");
      };
    } catch (Csv.TooLong tooLong) {
      throw new ValidationException("This file has more than " + MAX_ROWS + " rows. Import a shorter period.");
    }
  }

  /** The columns used for this account's last saved file of this kind, if any. */
  private JsonNode remembered(UUID owner, ObjectNode account, String kind) {
    if (account == null) return null;
    return records.list("fin_import_batch", owner, Map.of("accountId", account.path("id").asText(), "kind", kind))
        .stream().filter(b -> "committed".equals(b.path("status").asText()))
        .reduce((a, b) -> b).map(b -> b.path("mapping")).filter(m -> m.has("headerRow")).orElse(null);
  }

  /** The review: each row as new, already imported or needing a look, and what else will change. */
  private ObjectNode preview(UUID owner, String kind, ObjectNode account, UUID personId, Statement st) {
    ObjectNode stats = Json.obj();
    ArrayNode groups = stats.putArray("groups");
    Map<String, String> groupAccount = new HashMap<>();
    for (Group g : st.groups()) {
      String existing = folioAccount(owner, personId, g);
      groupAccount.put(g.key(), existing);
      ObjectNode n = groups.addObject().put("key", g.key()).put("last4", g.last4());
      n.put("provider", g.provider());
      n.put("accountId", existing);
    }

    Categoriser cat = isBank(kind) ? categoriser(owner) : null;
    Map<UUID, String> buckets = isBank(kind) ? buckets(owner) : Map.of();
    Map<String, Set<String>> known = new HashMap<>();
    Map<String, Integer> seen = new HashMap<>();
    ArrayNode rows = stats.putArray("rows");
    int fresh = 0;
    int dupes = 0;
    int look = 0;
    int i = 0;
    for (Txn t : st.txns()) {
      String accountId = account != null ? account.path("id").asText() : groupAccount.get(t.group());
      String base = String.join("|", t.group() == null ? "" : t.group(), t.date().toString(),
          t.amount().stripTrailingZeros().toPlainString(), t.direction(),
          t.description().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "), t.ref() == null ? "" : t.ref(),
          t.units() == null ? "" : t.units().stripTrailingZeros().toPlainString());
      int occurrence = seen.merge(base, 1, Integer::sum);
      String key = Parsing.sha256(base + "|" + occurrence);
      boolean duplicate = accountId != null && known.computeIfAbsent(accountId, a -> keys(owner, a)).contains(key);

      String txnKind = t.kind();
      UUID category = null;
      if (cat != null) {
        category = cat.categorise(t.description(), t.direction());
        txnKind = bankKind(t, category == null ? null : buckets.get(category));
      }
      boolean needsLook = cat != null ? category == null : !t.kindKnown();

      ObjectNode r = rows.addObject().put("i", i++).put("line", t.line()).put("date", t.date().toString())
          .put("amount", t.amount()).put("direction", t.direction()).put("description", t.description())
          .put("kind", txnKind).put("needsLook", needsLook).put("duplicate", duplicate).put("key", key);
      if (t.units() != null) r.put("units", t.units());
      if (t.price() != null) r.put("price", t.price());
      if (t.units() != null && (kind.equals("cas_pdf") || kind.equals("broker_tradebook"))) {
        String ref = t.ref() == null ? "" : t.ref().split("#")[0];
        if (ref.matches("[A-Z]{2}[A-Z0-9]{9}\\d")) r.put("isin", ref);
        if (kind.equals("broker_tradebook")) r.put("symbol", Parsing.clip(t.description(), 60));
      }
      if (t.group() != null) r.put("group", t.group());
      if (category != null) r.put("categoryId", category.toString());
      if (duplicate) dupes++;
      else {
        fresh++;
        if (needsLook) look++;
      }
    }

    ArrayNode unreadable = stats.putArray("unreadable");
    st.unreadable().forEach(x -> unreadable.addObject().put("line", x.line()).put("reason", x.reason()));
    ArrayNode holdings = stats.putArray("holdings");
    for (Holding h : st.holdings()) {
      ObjectNode n = holdings.addObject().put("name", h.name()).put("assetClass", h.assetClass())
          .put("subCategory", h.subCategory()).put("units", h.units()).put("value", h.value());
      n.put("group", h.group());
      n.put("isin", h.isin());
      n.put("asOf", h.asOf() == null ? null : h.asOf().toString());
      if (h.avgCost() != null) n.put("avgCost", h.avgCost());
    }
    if (st.balance() != null && account != null && BALANCE_ACCOUNTS.contains(account.path("kind").asText())) {
      stats.putObject("balance").put("amount", st.balance().amount()).put("date", st.balance().date().toString());
    }
    if (st.columns() != null) {
      ObjectNode c = stats.putObject("columns").put("headerRow", st.columns().headerRow()).put("sure", st.columns().sure());
      ArrayNode h = c.putArray("headers");
      st.columns().headers().forEach(x -> h.add(Parsing.clip(x, 60)));
      ArrayNode sample = c.putArray("sample");
      st.columns().sample().forEach(row -> {
        ArrayNode a = sample.addArray();
        row.forEach(a::add);
      });
    }
    stats.putObject("counts").put("rows", st.txns().size()).put("new", fresh).put("duplicates", dupes)
        .put("needsLook", look).put("unreadable", st.unreadable().size()).put("holdings", st.holdings().size());
    return stats;
  }

  /** The kind of a bank row: a transfer or income when its category says so, else money in or out. */
  private static String bankKind(Txn t, String bucket) {
    if ("transfer".equals(bucket)) return "transfer";
    List<String> words = Categoriser.tokens(t.description());
    if ("out".equals(t.direction()) && words.contains("emi")) return "emi";
    if ("in".equals(t.direction()) && words.contains("interest")) return "interest";
    return "in".equals(t.direction()) ? "income" : "spend";
  }

  private Categoriser categoriser(UUID owner) {
    Map<String, UUID> system = new HashMap<>();
    for (ObjectNode c : cashFlow.categories(owner)) {
      if (c.hasNonNull("systemKey")) system.put(c.path("systemKey").asText(), UUID.fromString(c.path("id").asText()));
    }
    return new Categoriser(records.list("fin_category_rule", owner, Map.of()), system);
  }

  private Map<UUID, String> buckets(UUID owner) {
    Map<UUID, String> out = new HashMap<>();
    records.list("fin_category", owner, Map.of())
        .forEach(c -> out.put(UUID.fromString(c.path("id").asText()), c.path("bucket").asText()));
    return out;
  }

  private Set<String> keys(UUID owner, String accountId) {
    Set<String> out = new HashSet<>();
    records.list("fin_transaction", owner, Map.of("accountId", accountId))
        .forEach(t -> out.add(t.path("dedupeKey").asText()));
    return out;
  }

  /** The person's mutual-fund account for this folio, if they have one. */
  private String folioAccount(UUID owner, UUID personId, Group g) {
    return records.list("fin_account", owner, Map.of("personId", personId.toString(), "kind", "mf_folio")).stream()
        .filter(a -> g.last4().equalsIgnoreCase(a.path("last4").asText()))
        .filter(a -> g.provider() == null || !a.hasNonNull("provider")
            || g.provider().equalsIgnoreCase(a.path("provider").asText()))
        .map(a -> a.path("id").asText()).findFirst().orElse(null);
  }

  // ── Reading batches ─────────────────────────────────────────────────────────

  /** The owner's imports, newest first, without their rows. */
  @Transactional(readOnly = true)
  public List<ObjectNode> list(UUID caller, UUID accountId) {
    UUID owner = scopes.own(caller).ownerUserId();
    Map<String, Object> where = accountId == null ? Map.of() : Map.of("accountId", accountId.toString());
    List<ObjectNode> out = new ArrayList<>();
    for (ObjectNode b : records.list("fin_import_batch", owner, where)) {
      ObjectNode s = (ObjectNode) b.path("stats");
      for (String heavy : List.of("rows", "holdings", "unreadable", "undo", "columns", "groups")) s.remove(heavy);
      out.add(0, b);
    }
    return out;
  }

  @Transactional(readOnly = true)
  public ObjectNode get(UUID caller, UUID id) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode b = batch(owner, id);
    ((ObjectNode) b.path("stats")).remove("undo");
    return b;
  }

  private ObjectNode batch(UUID owner, UUID id) {
    return records.get("fin_import_batch", owner, id).orElseThrow(() -> new NotFoundException("Import", id));
  }

  // ── Commit ──────────────────────────────────────────────────────────────────

  @Transactional
  public ObjectNode commit(UUID caller, UUID id, Choices choices) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode b = batch(owner, id);
    switch (b.path("status").asText()) {
      case "parsed" -> { }
      case "needs_review" -> throw new ValidationException("Choose the columns first.");
      case "committed" -> throw new ConflictException("This import is already saved.");
      default -> throw new ValidationException("This import was undone. Upload the file again to import it.");
    }
    String kind = b.path("kind").asText();
    UUID personId = UUID.fromString(b.path("personId").asText());
    ObjectNode stats = (ObjectNode) b.path("stats");
    ObjectNode undo = Json.obj();
    ArrayNode createdAccounts = undo.putArray("createdAccounts");
    ArrayNode createdAssets = undo.putArray("createdAssets");
    ArrayNode changedAssets = undo.putArray("changedAssets");
    ArrayNode pricesSet = Json.obj().arrayNode();

    ObjectNode account = b.hasNonNull("accountId")
        ? records.get("fin_account", owner, UUID.fromString(b.path("accountId").asText()))
            .orElseThrow(() -> new ValidationException("The account for this import was removed."))
        : null;

    // CAS folios become accounts the first time they are seen.
    Map<String, String> groupAccount = new HashMap<>();
    for (JsonNode g : stats.path("groups")) {
      String existing = g.hasNonNull("accountId") && records.get("fin_account", owner,
          UUID.fromString(g.path("accountId").asText())).isPresent() ? g.path("accountId").asText() : null;
      if (existing == null) {
        ObjectNode a = Json.obj().put("personId", personId.toString()).put("kind", "mf_folio")
            .put("source", "statement").put("last4", g.path("last4").asText())
            .put("nickname", "Folio ending " + g.path("last4").asText());
        if (g.hasNonNull("provider")) a.put("provider", Parsing.clip(g.path("provider").asText(), 60));
        existing = records.insert("fin_account", owner, a).path("id").asText();
        createdAccounts.add(existing);
      }
      groupAccount.put(g.path("key").asText(), existing);
    }

    // Categories the person chose, checked as theirs; "always" rows become rules.
    Map<UUID, String> buckets = buckets(owner);
    Map<Integer, UUID> chosen = new HashMap<>();
    if (choices != null && choices.categories() != null) {
      choices.categories().forEach((k, v) -> {
        if (v == null) return;
        if (!buckets.containsKey(v)) throw new NotFoundException("Category", v);
        chosen.put(Integer.parseInt(k), v);
      });
    }
    ArrayNode rows = (ArrayNode) stats.path("rows");
    if (choices != null && choices.always() != null) {
      for (int i : choices.always()) {
        if (i < 0 || i >= rows.size() || !chosen.containsKey(i)) continue;
        rule(owner, rows.get(i).path("description").asText(), chosen.get(i));
      }
    }
    Categoriser cat = isBank(kind) && choices != null && choices.always() != null && !choices.always().isEmpty()
        ? categoriser(owner) : null;

    Map<String, Set<String>> known = new HashMap<>();
    int imported = 0;
    List<Object[]> unitRows = new ArrayList<>();
    for (JsonNode r : rows) {
      String accountId = account != null ? account.path("id").asText() : groupAccount.get(r.path("group").asText());
      if (accountId == null) continue;
      String key = r.path("key").asText();
      Set<String> have = known.computeIfAbsent(accountId, a -> keys(owner, a));
      if (!have.add(key)) continue;
      int i = r.path("i").asInt();
      UUID category = chosen.get(i);
      if (category == null && cat != null) category = cat.categorise(r.path("description").asText(), r.path("direction").asText());
      if (category == null && r.hasNonNull("categoryId")) category = UUID.fromString(r.path("categoryId").asText());
      String txnKind = r.path("kind").asText();
      if (isBank(kind)) {
        String bucket = category == null ? null : buckets.get(category);
        if ("transfer".equals(bucket)) txnKind = "transfer";
        else if ("transfer".equals(txnKind)) txnKind = "in".equals(r.path("direction").asText()) ? "income" : "spend";
      }
      boolean needsLook = isBank(kind) ? category == null
          : r.path("needsLook").asBoolean() && !chosen.containsKey(i);
      ObjectNode t = Json.obj().put("accountId", accountId).put("personId", personId.toString())
          .put("importBatchId", id.toString()).put("txnDate", r.path("date").asText())
          .put("amount", r.path("amount").decimalValue()).put("direction", r.path("direction").asText())
          .put("description", r.path("description").asText()).put("kind", txnKind)
          .put("dedupeKey", key).put("needsMapping", needsLook);
      if (category != null) t.put("categoryId", category.toString());
      if (r.hasNonNull("units")) t.put("units", r.path("units").decimalValue());
      if (r.hasNonNull("price")) t.put("price", r.path("price").decimalValue());
      ObjectNode savedTxn = records.insert("fin_transaction", owner, t);
      if (r.hasNonNull("units") && r.get("units").decimalValue().signum() != 0 && !isBank(kind)) {
        unitRows.add(new Object[] {r, accountId, savedTxn.path("id").asText()});
      }
      imported++;
    }

    // Balances and holdings, each previous figure kept for undo.
    if (stats.has("balance") && account != null) {
      balance(owner, account, stats.path("balance"), createdAssets, changedAssets);
    }
    // Phase 2: units go into the tax ledger (lots, FIFO sales) and the goals' earmarks.
    EarmarkService.Log log = new EarmarkService.Log();
    Map<String, String> instrumentOf = new HashMap<>();
    for (JsonNode h : stats.path("holdings")) {
      if (h.hasNonNull("isin") && h.hasNonNull("name")) instrumentOf.putIfAbsent("isin:" + h.get("isin").asText(), h.get("name").asText());
    }
    unitRows.sort(Comparator.comparing((Object[] x) -> ((JsonNode) x[0]).path("date").asText())
        .thenComparing(x -> ((JsonNode) x[0]).path("units").decimalValue().signum() > 0 ? 0 : 1)
        .thenComparing(x -> ((JsonNode) x[0]).path("line").asInt()));
    for (Object[] x : unitRows) {
      JsonNode r = (JsonNode) x[0];
      String isin = r.path("isin").asText(null);
      String name = isin != null ? instrumentOf.getOrDefault("isin:" + isin, isin) : r.path("symbol").asText(r.path("description").asText());
      ObjectNode ins = instruments.resolve(isin, name, kind.equals("cas_pdf") ? "mf" : null, null);
      unitsTxn(owner, personId, (String) x[1], ins.path("id").asText(), r, (String) x[2], id.toString(), log);
    }
    if (stats.path("holdings").size() > 0) {
      Map<String, List<JsonNode>> byAccount = new HashMap<>();
      for (JsonNode h : stats.path("holdings")) {
        String accountId = account != null ? account.path("id").asText() : groupAccount.get(h.path("group").asText());
        if (accountId != null) byAccount.computeIfAbsent(accountId, a -> new ArrayList<>()).add(h);
      }
      boolean snapshot = kind.equals("broker_holdings");
      byAccount.forEach((accountId, hs) -> holdingsToLedger(owner, personId, accountId, hs, snapshot,
          kind.equals("cas_pdf") ? "mf" : null, id.toString(), log, changedAssets, pricesSet));
    }
    if (!log.isEmpty()) undo.set("ledger", log.toJson());
    if (!pricesSet.isEmpty()) undo.set("prices", pricesSet);

    stats.set("undo", undo);
    ((ObjectNode) stats.path("counts")).put("imported", imported);
    ObjectNode done = records.update("fin_import_batch", owner, id, Json.obj().put("status", "committed")
        .putNull("error").set("stats", stats)).orElseThrow();
    cashFlow.snapshot(owner);
    audit.record(owner, ActorType.USER, caller, "finance.import.committed", id.toString(),
        Json.obj().put("kind", kind).put("rows", imported));
    ((ObjectNode) done.path("stats")).remove("undo");
    return done;
  }

  private void balance(UUID owner, ObjectNode account, JsonNode bal, ArrayNode created, ArrayNode changed) {
    BigDecimal amount = bal.path("amount").decimalValue();
    if (amount.signum() < 0) return;
    String date = bal.path("date").asText();
    String accountId = account.path("id").asText();
    var existing = records.list("fin_asset", owner, Map.of("accountId", accountId, "kind", "savings_balance"));
    if (!existing.isEmpty()) {
      ObjectNode a = existing.get(0);
      if (a.hasNonNull("valueAsOf") && a.path("valueAsOf").asText().compareTo(date) > 0) return;
      change(owner, a, amount, date, changed);
      return;
    }
    String provider = account.path("provider").asText("");
    ObjectNode a = Json.obj().put("personId", account.path("personId").asText()).put("accountId", accountId)
        .put("kind", "savings_balance").put("assetClass", "cash")
        .put("name", provider.isBlank() ? "Savings balance" : Parsing.clip(provider, 100) + " balance")
        .put("value", amount).put("valueAsOf", date).put("isEmergency", account.path("isEmergency").asBoolean());
    created.add(records.insert("fin_asset", owner, a).path("id").asText());
  }

  static final Set<String> BUYS = Set.of("buy", "sip", "stp_in", "dividend");
  static final Set<String> SELLS = Set.of("sell", "swp", "stp_out");

  /**
   * One transaction with units: a purchase becomes a lot, a sale consumes the oldest lots. A sale
   * of units the earlier statements never showed being bought first gets an opening lot (date
   * unknown) so the ledger stays whole; it shows in "Needs a look".
   */
  private void unitsTxn(UUID owner, UUID personId, String accountId, String instrumentId, JsonNode r, String txnId,
                        String batchId, EarmarkService.Log log) {
    BigDecimal units = r.path("units").decimalValue().abs();
    LocalDate date = LocalDate.parse(r.path("date").asText());
    String kind = r.path("kind").asText();
    boolean buy = BUYS.contains(kind) || (!SELLS.contains(kind) && r.path("units").decimalValue().signum() > 0);
    if (buy) {
      BigDecimal cost = r.hasNonNull("price") ? r.get("price").decimalValue()
          : r.path("amount").decimalValue().divide(units, 4, java.math.RoundingMode.HALF_UP);
      String source = switch (kind) {
        case "sip" -> "sip";
        case "stp_in" -> "stp_in";
        case "dividend" -> "dividend_reinvest";
        default -> "buy";
      };
      ledger.recordBuy(owner, personId.toString(), accountId, instrumentId, date, true, units, cost, source, batchId, null, log);
    } else {
      BigDecimal held = TaxLotLedger.remaining(EarmarkService.lots(ledger.lotRows(owner, UUID.fromString(accountId),
          UUID.fromString(instrumentId))));
      if (held.compareTo(units) < 0) {
        ledger.recordBuy(owner, personId.toString(), accountId, instrumentId, null, false, units.subtract(held), null,
            "opening_balance", batchId, null, log);
      }
      ledger.recordSell(owner, personId.toString(), accountId, instrumentId, date, units, r.path("amount").decimalValue(),
          null, batchId, txnId, log);
    }
  }

  /**
   * A statement's holdings: each becomes (or matches) an instrument, the ledger is brought to the
   * units the statement shows on its date, and its value gives a dated price. A broker's holdings
   * file is the whole account, so instruments it no longer lists have been sold. Figures that
   * Phase 1c kept as plain assets for the same holdings are set to nothing (kept for undo), so
   * nothing counts twice.
   */
  private void holdingsToLedger(UUID owner, UUID personId, String accountId, List<JsonNode> hs, boolean snapshot,
                                String typeHint, String batchId, EarmarkService.Log log, ArrayNode changed,
                                ArrayNode pricesSet) {
    Set<String> listed = new HashSet<>();
    List<ObjectNode> oldAssets = records.list("fin_asset", owner, Map.of("accountId", accountId, "kind", "other"));
    for (JsonNode h : hs) {
      String name = h.path("name").asText();
      ObjectNode ins = instruments.resolve(h.path("isin").asText(null), name, typeHint, h.path("assetClass").asText(null));
      String instrumentId = ins.path("id").asText();
      listed.add(instrumentId);
      LocalDate asOf = h.hasNonNull("asOf") ? LocalDate.parse(h.get("asOf").asText()) : LocalDate.now();
      BigDecimal units = h.path("units").decimalValue();
      ledger.snapshot(owner, personId.toString(), accountId, instrumentId, units,
          h.hasNonNull("avgCost") ? h.get("avgCost").decimalValue() : null, asOf, batchId, log);
      ObjectNode priceUndo = prices.fromImport(owner, UUID.fromString(instrumentId), asOf, units, h.path("value").decimalValue());
      if (priceUndo != null) pricesSet.add(priceUndo);
      for (ObjectNode a : oldAssets) {
        if (name.equalsIgnoreCase(a.path("name").asText()) && a.path("value").decimalValue().signum() != 0) {
          change(owner, a, BigDecimal.ZERO, asOf.toString(), changed);
        }
      }
    }
    if (!snapshot) return;
    Set<String> inAccount = new HashSet<>();
    for (ObjectNode lot : records.list("fin_holding_lot", owner, Map.of("accountId", accountId))) {
      if (lot.path("remainingQty").decimalValue().signum() > 0) inAccount.add(lot.path("instrumentId").asText());
    }
    for (String gone : inAccount) {
      if (listed.contains(gone)) continue;
      ledger.snapshot(owner, personId.toString(), accountId, gone, BigDecimal.ZERO, null, LocalDate.now(), batchId, log);
    }
  }

  private void change(UUID owner, ObjectNode asset, BigDecimal value, String asOf, ArrayNode changed) {
    ObjectNode before = changed.addObject().put("id", asset.path("id").asText());
    before.set("value", asset.path("value"));
    before.set("valueAsOf", asset.path("valueAsOf").isMissingNode() ? null : asset.get("valueAsOf"));
    records.update("fin_asset", owner, UUID.fromString(asset.path("id").asText()),
        Json.obj().put("value", value).put("valueAsOf", asOf));
  }

  // ── Undo ────────────────────────────────────────────────────────────────────

  /** Undoes a saved import (every figure goes back), or discards one not saved yet. */
  @Transactional
  public ObjectNode undo(UUID caller, UUID id) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode b = batch(owner, id);
    String status = b.path("status").asText();
    if (status.equals("undone")) throw new ConflictException("This import was already undone.");
    if (!status.equals("committed")) {
      discard(owner, b);
      audit.record(owner, ActorType.USER, caller, "finance.import.discarded", id.toString(), null);
      return Json.obj().put("id", id.toString()).put("status", "discarded");
    }
    ObjectNode stats = (ObjectNode) b.path("stats");
    JsonNode undo = stats.path("undo");
    Set<String> mine = touched(undo);
    OffsetDateTime at = OffsetDateTime.parse(b.path("createdAt").asText());
    for (ObjectNode later : records.list("fin_import_batch", owner, Map.of("status", "committed"))) {
      if (later.path("id").asText().equals(id.toString())) continue;
      if (!OffsetDateTime.parse(later.path("createdAt").asText()).isAfter(at)) continue;
      Set<String> theirs = touched(later.path("stats").path("undo"));
      theirs.retainAll(mine);
      if (!theirs.isEmpty()) {
        throw new ConflictException("A later import (" + later.path("stats").path("fileName").asText("a file")
            + ") changed the same balances or holdings. Undo that one first.");
      }
    }

    ledger.undo(owner, undo.path("ledger"));
    prices.undoImport(owner, undo.path("prices"));
    records.deleteWhere("fin_transaction", owner, Map.of("importBatchId", id.toString()));
    for (JsonNode c : undo.path("changedAssets")) {
      ObjectNode v = Json.obj();
      v.set("value", c.path("value"));
      if (c.hasNonNull("valueAsOf")) v.set("valueAsOf", c.get("valueAsOf"));
      else v.putNull("valueAsOf");
      records.update("fin_asset", owner, UUID.fromString(c.path("id").asText()), v);
    }
    for (JsonNode a : undo.path("createdAssets")) records.delete("fin_asset", owner, UUID.fromString(a.asText()));
    for (JsonNode a : undo.path("createdAccounts")) {
      String accountId = a.asText();
      boolean used = !records.list("fin_transaction", owner, Map.of("accountId", accountId)).isEmpty()
          || !records.list("fin_asset", owner, Map.of("accountId", accountId)).isEmpty();
      if (!used) records.delete("fin_account", owner, UUID.fromString(accountId));
    }
    stats.remove("undo");
    ObjectNode counts = (ObjectNode) stats.path("counts");
    counts.put("imported", 0);
    if (b.hasNonNull("fileId")) files.delete(owner, UUID.fromString(b.path("fileId").asText()));
    ObjectNode done = records.update("fin_import_batch", owner, id, Json.obj().put("status", "undone")
        .putNull("fileId").set("stats", stats)).orElseThrow();
    cashFlow.snapshot(owner);
    audit.record(owner, ActorType.USER, caller, "finance.import.undone", id.toString(), null);
    return done;
  }

  private static Set<String> touched(JsonNode undo) {
    Set<String> out = new HashSet<>();
    undo.path("createdAssets").forEach(n -> out.add(n.asText()));
    undo.path("changedAssets").forEach(n -> out.add(n.path("id").asText()));
    undo.path("createdAccounts").forEach(n -> out.add(n.asText()));
    undo.path("ledger").path("holdings").forEach(h -> out.add("holding:" + h.path("accountId").asText() + "|"
        + h.path("instrumentId").asText()));
    return out;
  }

  /** Drops a batch that was never saved, with its stored file. */
  private void discard(UUID owner, ObjectNode b) {
    if ("committed".equals(b.path("status").asText())) throw new ConflictException("Undo this import instead.");
    if (b.hasNonNull("fileId")) files.delete(owner, UUID.fromString(b.path("fileId").asText()));
    records.delete("fin_import_batch", owner, UUID.fromString(b.path("id").asText()));
  }

  // ── Transactions ────────────────────────────────────────────────────────────

  /** The owner's transactions, newest first, for one account and month when given. */
  @Transactional(readOnly = true)
  public List<ObjectNode> transactions(UUID caller, UUID accountId, String month) {
    UUID owner = scopes.own(caller).ownerUserId();
    if (month != null && !month.isBlank() && !month.matches("\\d{4}-\\d{2}")) {
      throw new ValidationException("Month is YYYY-MM.");
    }
    Map<String, Object> where = accountId == null ? Map.of() : Map.of("accountId", accountId.toString());
    return records.list("fin_transaction", owner, where).stream()
        .filter(t -> month == null || month.isBlank() || t.path("txnDate").asText().startsWith(month))
        .sorted(Comparator.comparing((ObjectNode t) -> t.path("txnDate").asText()).reversed())
        .limit(2000).toList();
  }

  public record Recategorised(ObjectNode transaction, int alsoChanged) {}

  /**
   * Puts a transaction in a category. With {@code always}, rows like it get the same category
   * from now on, and rows like it that still need a look get it now.
   */
  @Transactional
  public Recategorised recategorise(UUID caller, UUID id, UUID categoryId, boolean always) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode t = records.get("fin_transaction", owner, id).orElseThrow(() -> new NotFoundException("Transaction", id));
    if (categoryId == null) throw new ValidationException("Choose a category.");
    Map<UUID, String> buckets = buckets(owner);
    if (!buckets.containsKey(categoryId)) throw new NotFoundException("Category", categoryId);
    ObjectNode updated = records.update("fin_transaction", owner, id, recategorised(t, categoryId, buckets.get(categoryId)))
        .orElseThrow();
    int also = 0;
    if (always) {
      String match = rule(owner, t.path("description").asText(), categoryId);
      if (!match.isEmpty()) {
        Set<String> want = Set.of(match.split(" "));
        for (ObjectNode other : records.list("fin_transaction", owner, Map.of("needsMapping", true))) {
          if (other.path("id").asText().equals(id.toString())) continue;
          if (!other.path("direction").asText().equals(t.path("direction").asText())) continue;
          if (!Set.copyOf(Categoriser.tokens(other.path("description").asText())).containsAll(want)) continue;
          records.update("fin_transaction", owner, UUID.fromString(other.path("id").asText()),
              recategorised(other, categoryId, buckets.get(categoryId)));
          also++;
        }
      }
    }
    audit.record(owner, ActorType.USER, caller, "finance.transaction.categorised", id.toString(),
        Json.obj().put("always", always));
    return new Recategorised(updated, also);
  }

  private static ObjectNode recategorised(ObjectNode t, UUID categoryId, String bucket) {
    ObjectNode v = Json.obj().put("categoryId", categoryId.toString()).put("needsMapping", false);
    String kind = t.path("kind").asText();
    if ("transfer".equals(bucket)) v.put("kind", "transfer");
    else if ("transfer".equals(kind)) v.put("kind", "in".equals(t.path("direction").asText()) ? "income" : "spend");
    return v;
  }

  /** Saves "always this category for rows like this one"; returns the words it matches on. */
  private String rule(UUID owner, String description, UUID categoryId) {
    String match = Categoriser.matchText(description);
    if (match.isEmpty()) return match;
    var existing = records.list("fin_category_rule", owner, Map.of("matchText", match));
    if (existing.isEmpty()) {
      records.insert("fin_category_rule", owner, Json.obj().put("matchText", match).put("categoryId", categoryId.toString()));
    } else {
      records.update("fin_category_rule", owner, UUID.fromString(existing.get(0).path("id").asText()),
          Json.obj().put("categoryId", categoryId.toString()));
    }
    return match;
  }
}

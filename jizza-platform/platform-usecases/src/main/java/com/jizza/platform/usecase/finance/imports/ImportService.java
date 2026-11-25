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
  private static final Set<String> BROKER_SUBS = Set.of("shares", "etf", "sgb");

  private final FinanceRecords records;
  private final FinRecordsUseCase forms;
  private final FinScopeResolver scopes;
  private final StatementFiles files;
  private final FinCashFlowUseCase cashFlow;
  private final AuditLog audit;

  public ImportService(FinanceRecords records, FinRecordsUseCase forms, FinScopeResolver scopes,
                       StatementFiles files, FinCashFlowUseCase cashFlow, AuditLog audit) {
    this.records = records;
    this.forms = forms;
    this.scopes = scopes;
    this.files = files;
    this.cashFlow = cashFlow;
    this.audit = audit;
  }

  /** One upload. {@code password} is for a locked PDF and is never kept. */
  public record Upload(String kind, UUID personId, UUID accountId, JsonNode mapping, String password,
                       String filename, String contentType, byte[] bytes) {}

  /** The person's choices on the review screen: a category per row, and rows to make rules from. */
  public record Choices(Map<String, UUID> categories, Set<Integer> always) {}

  // ── Upload and preview ──────────────────────────────────────────────────────

  @Transactional
  public ObjectNode upload(UUID caller, Upload u) {
    UUID owner = scopes.own(caller).ownerUserId();
    String kind = u.kind() == null ? "" : u.kind();
    if (!KINDS.contains(kind)) throw new ValidationException("Choose what kind of file this is.");
    if (kind.equals("bank_pdf")) {
      throw new ValidationException("Bank PDFs can't be read yet. Download the statement as CSV (or Excel saved as CSV) instead.");
    }
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
      boolean fits = kind.equals("bank_csv") ? BANK_ACCOUNTS.contains(accountKind) : BROKER_ACCOUNTS.contains(accountKind);
      if (!fits) {
        throw new ValidationException(kind.equals("bank_csv")
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
    audit.record(owner, ActorType.USER, caller, "finance.import.parsed", saved.path("id").asText(),
        Json.obj().put("kind", kind));
    return saved;
  }

  private Statement parse(UUID owner, String kind, ObjectNode account, Upload u) {
    try {
      return switch (kind) {
        case "bank_csv" -> BankCsvImporter.parse(u.bytes(), u.mapping(), remembered(owner, account), MAX_ROWS);
        case "cas_pdf" -> CasImporter.parse(files.pdfText(u.bytes(), u.password()), MAX_ROWS);
        case "broker_holdings" -> BrokerFileImporter.holdings(u.bytes(), LocalDate.now(), MAX_ROWS);
        case "broker_tradebook" -> BrokerFileImporter.tradebook(u.bytes(), MAX_ROWS);
        default -> throw new ValidationException("Choose what kind of file this is.");
      };
    } catch (Csv.TooLong tooLong) {
      throw new ValidationException("This file has more than " + MAX_ROWS + " rows. Import a shorter period.");
    }
  }

  /** The columns used for this account's last saved bank statement, if any. */
  private JsonNode remembered(UUID owner, ObjectNode account) {
    return records.list("fin_import_batch", owner, Map.of("accountId", account.path("id").asText(), "kind", "bank_csv"))
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

    Categoriser cat = kind.equals("bank_csv") ? categoriser(owner) : null;
    Map<UUID, String> buckets = kind.equals("bank_csv") ? buckets(owner) : Map.of();
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
    Categoriser cat = kind.equals("bank_csv") && choices != null && choices.always() != null && !choices.always().isEmpty()
        ? categoriser(owner) : null;

    Map<String, Set<String>> known = new HashMap<>();
    int imported = 0;
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
      if (kind.equals("bank_csv")) {
        String bucket = category == null ? null : buckets.get(category);
        if ("transfer".equals(bucket)) txnKind = "transfer";
        else if ("transfer".equals(txnKind)) txnKind = "in".equals(r.path("direction").asText()) ? "income" : "spend";
      }
      boolean needsLook = kind.equals("bank_csv") ? category == null
          : r.path("needsLook").asBoolean() && !chosen.containsKey(i);
      ObjectNode t = Json.obj().put("accountId", accountId).put("personId", personId.toString())
          .put("importBatchId", id.toString()).put("txnDate", r.path("date").asText())
          .put("amount", r.path("amount").decimalValue()).put("direction", r.path("direction").asText())
          .put("description", r.path("description").asText()).put("kind", txnKind)
          .put("dedupeKey", key).put("needsMapping", needsLook);
      if (category != null) t.put("categoryId", category.toString());
      if (r.hasNonNull("units")) t.put("units", r.path("units").decimalValue());
      if (r.hasNonNull("price")) t.put("price", r.path("price").decimalValue());
      records.insert("fin_transaction", owner, t);
      imported++;
    }

    // Balances and holdings, each previous figure kept for undo.
    if (stats.has("balance") && account != null) {
      balance(owner, account, stats.path("balance"), createdAssets, changedAssets);
    }
    if (stats.path("holdings").size() > 0) {
      Map<String, List<JsonNode>> byAccount = new HashMap<>();
      for (JsonNode h : stats.path("holdings")) {
        String accountId = account != null ? account.path("id").asText() : groupAccount.get(h.path("group").asText());
        if (accountId != null) byAccount.computeIfAbsent(accountId, a -> new ArrayList<>()).add(h);
      }
      boolean snapshot = kind.equals("broker_holdings");
      byAccount.forEach((accountId, hs) -> holdings(owner, personId, accountId, hs, snapshot, createdAssets, changedAssets));
    }

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

  /**
   * Each holding updates the account's asset of the same name, or adds one. A broker's holdings
   * file is the whole account, so shares it no longer lists are set to nothing.
   */
  private void holdings(UUID owner, UUID personId, String accountId, List<JsonNode> hs, boolean snapshot,
                        ArrayNode created, ArrayNode changed) {
    List<ObjectNode> existing = records.list("fin_asset", owner, Map.of("accountId", accountId));
    Set<String> listed = new HashSet<>();
    for (JsonNode h : hs) {
      String name = h.path("name").asText();
      listed.add(name.toLowerCase(Locale.ROOT));
      String asOf = h.hasNonNull("asOf") ? h.path("asOf").asText() : LocalDate.now().toString();
      ObjectNode match = existing.stream().filter(a -> name.equalsIgnoreCase(a.path("name").asText()))
          .findFirst().orElse(null);
      if (match != null) {
        change(owner, match, h.path("value").decimalValue(), asOf, changed);
      } else {
        ObjectNode a = Json.obj().put("personId", personId.toString()).put("accountId", accountId)
            .put("kind", "other").put("name", name).put("assetClass", h.path("assetClass").asText())
            .put("subCategory", h.path("subCategory").asText()).put("value", h.path("value").decimalValue())
            .put("valueAsOf", asOf);
        created.add(records.insert("fin_asset", owner, a).path("id").asText());
      }
    }
    if (!snapshot) return;
    for (ObjectNode a : existing) {
      if (listed.contains(a.path("name").asText().toLowerCase(Locale.ROOT))) continue;
      if (!BROKER_SUBS.contains(a.path("subCategory").asText()) || a.path("value").decimalValue().signum() == 0) continue;
      change(owner, a, BigDecimal.ZERO, LocalDate.now().toString(), changed);
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
            + ") changed the same balances. Undo that one first.");
      }
    }

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

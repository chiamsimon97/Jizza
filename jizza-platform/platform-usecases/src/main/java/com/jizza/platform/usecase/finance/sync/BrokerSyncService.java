package com.jizza.platform.usecase.finance.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.mcp.McpServerDto;
import com.jizza.engine.api.mcp.McpServers;
import com.jizza.engine.api.mcp.McpToolDto;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.platform.usecase.finance.imports.ImportService;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import com.jizza.platform.usecase.finance.tools.FinanceToolProvider;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Connected sources (Kai part A): a broker's MCP server the person added on Connectors, tied to one
 * of their demat accounts. A sync calls only the server's read tools ({@code get_holdings},
 * {@code get_mf_holdings}, and {@code get_margins} for the cash left in the account), never
 * anything that changes the account, and lands as a holdings
 * import the person reviews before it is saved. The answers are never logged; the log names only
 * the user, the source and counts.
 */
@Service
public class BrokerSyncService {

  private static final Logger log = LoggerFactory.getLogger(BrokerSyncService.class);

  /** The only tools a sync may call: they read and never change the account. */
  static final String SHARES_TOOL = "get_holdings";
  static final String FUNDS_TOOL = "get_mf_holdings";
  static final String CASH_TOOL = "get_margins";
  /** Kite's server since late 2026: one tool per area, the part chosen by {@code mode}. */
  static final String PORTFOLIO_TOOL = "portfolio";
  static final String FUNDS_AREA_TOOL = "mutual_funds";
  static final Set<String> READ_TOOLS = Set.of(SHARES_TOOL, FUNDS_TOOL, CASH_TOOL, PORTFOLIO_TOOL, FUNDS_AREA_TOOL);
  /** The only modes a sync asks for: each reads, none changes the account. */
  static final Set<String> READ_MODES = Set.of("holdings", "margins");
  private static final int PAGE = 500;
  private static final int MAX_PAGES = 20;

  /** How to read one thing from a broker's server: which tool, with which arguments. */
  record Reader(String tool, ObjectNode args, boolean paged) {}

  /** The shares tool: {@code get_holdings}, or {@code portfolio} with mode holdings. */
  static Optional<Reader> sharesReader(McpServerDto s) {
    Optional<McpToolDto> old = tool(s, SHARES_TOOL);
    if (old.isPresent()) return Optional.of(new Reader(old.get().name(), Json.obj(), false));
    return tool(s, PORTFOLIO_TOOL).filter(t -> hasMode(t, "holdings"))
        .map(t -> new Reader(t.name(), Json.obj().put("mode", "holdings").put("type", "full").put("limit", PAGE), true));
  }

  /** The funds tool: {@code get_mf_holdings}, or {@code mutual_funds} with mode holdings. */
  static Optional<Reader> fundsReader(McpServerDto s) {
    Optional<McpToolDto> old = tool(s, FUNDS_TOOL);
    if (old.isPresent()) return Optional.of(new Reader(old.get().name(), Json.obj(), false));
    return tool(s, FUNDS_AREA_TOOL).filter(t -> hasMode(t, "holdings"))
        .map(t -> new Reader(t.name(), Json.obj().put("mode", "holdings"), false));
  }

  /** The cash tool: {@code get_margins}, or {@code portfolio} with mode margins. */
  static Optional<Reader> cashReader(McpServerDto s) {
    Optional<McpToolDto> old = tool(s, CASH_TOOL);
    if (old.isPresent()) return Optional.of(new Reader(old.get().name(), Json.obj(), false));
    return tool(s, PORTFOLIO_TOOL).filter(t -> hasMode(t, "margins"))
        .map(t -> new Reader(t.name(), Json.obj().put("mode", "margins"), false));
  }

  /** True when the tool's {@code mode} argument offers this value. */
  static boolean hasMode(McpToolDto t, String mode) {
    JsonNode values = t.inputSchema() == null ? null : t.inputSchema().path("properties").path("mode").path("enum");
    if (values == null || !values.isArray()) return false;
    for (JsonNode v : values) if (mode.equals(v.asText())) return true;
    return false;
  }
  private static final Set<String> BROKER_ACCOUNTS = Set.of("demat", "us_broker", "other");

  private final FinanceRecords records;
  private final FinScopeResolver scopes;
  private final McpServers servers;
  private final WorkspaceDirectory workspaces;
  private final ImportService imports;
  private final NotificationBus bus;
  private final AgentDirectory agents;
  private final com.jizza.platform.usecase.finance.records.FinRecordsUseCase people;

  public BrokerSyncService(FinanceRecords records, FinScopeResolver scopes, McpServers servers,
                           WorkspaceDirectory workspaces, ImportService imports, NotificationBus bus,
                           AgentDirectory agents, com.jizza.platform.usecase.finance.records.FinRecordsUseCase people) {
    this.records = records;
    this.scopes = scopes;
    this.servers = servers;
    this.workspaces = workspaces;
    this.imports = imports;
    this.bus = bus;
    this.agents = agents;
    this.people = people;
  }

  /** A server on Connectors that can be a source: it offers a holdings tool. */
  public record Candidate(UUID serverId, String name, boolean signedIn, boolean enabled, boolean funds,
                          boolean placesOrders) {}

  /** One linked source, with how its server stands now. */
  public record Source(UUID id, UUID serverId, String serverName, UUID accountId, String accountName,
                       boolean enabled, boolean signedIn, String lastStatus, String lastSyncedAt,
                       String lastBatchId) {}

  public record SourcesView(List<Source> sources, List<Candidate> servers) {}

  /** What a sync did: a batch to review, nothing new, or why it could not run. */
  public record SyncResult(String status, String batchId, int holdings, String message) {}

  // ── Reading ─────────────────────────────────────────────────────────────────

  public SourcesView view(UUID caller) {
    UUID owner = scopes.own(caller).ownerUserId();
    List<McpServerDto> mine = ownServers(owner);
    List<Candidate> candidates = new ArrayList<>();
    for (McpServerDto s : mine) {
      if (sharesReader(s).isEmpty()) continue;
      candidates.add(new Candidate(s.id(), s.name(), signedIn(s), s.enabled(), fundsReader(s).isPresent(),
          tool(s, "place_order").isPresent()));
    }
    List<Source> sources = new ArrayList<>();
    for (ObjectNode row : records.list("fin_sync_source", owner, Map.of())) sources.add(source(owner, row, mine));
    return new SourcesView(sources, candidates);
  }

  // ── Linking ─────────────────────────────────────────────────────────────────

  public Source link(UUID caller, UUID serverId, UUID accountId) {
    UUID owner = scopes.own(caller).ownerUserId();
    McpServerDto server = ownServers(owner).stream().filter(s -> s.id().equals(serverId)).findFirst()
        .orElseThrow(() -> new NotFoundException("Connected server", serverId));
    if (sharesReader(server).isEmpty()) {
      throw new ValidationException(server.name() + " has no holdings tool, so Kai can't read holdings from it.");
    }
    ObjectNode account = records.get("fin_account", owner, accountId)
        .orElseThrow(() -> new NotFoundException("Account", accountId));
    if (!BROKER_ACCOUNTS.contains(account.path("kind").asText())) {
      throw new ValidationException("Choose a demat or broker account for holdings.");
    }
    boolean taken = records.list("fin_sync_source", owner, Map.of("serverId", serverId.toString())).stream()
        .anyMatch(r -> r.path("accountId").asText().equals(accountId.toString()));
    if (taken) throw new ConflictException(server.name() + " already syncs into that account.");
    ObjectNode row = records.insert("fin_sync_source", owner, Json.obj()
        .put("serverId", serverId.toString()).put("accountId", accountId.toString()).put("enabled", true));
    return source(owner, row, List.of(server));
  }

  public Source setEnabled(UUID caller, UUID id, boolean enabled) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode row = records.update("fin_sync_source", owner, id, Json.obj().put("enabled", enabled))
        .orElseThrow(() -> new NotFoundException("Source", id));
    return source(owner, row, ownServers(owner));
  }

  public void unlink(UUID caller, UUID id) {
    UUID owner = scopes.own(caller).ownerUserId();
    if (!records.delete("fin_sync_source", owner, id)) throw new NotFoundException("Source", id);
  }

  // ── Syncing ─────────────────────────────────────────────────────────────────

  /** Sync now, from the Connect screen. */
  public SyncResult syncNow(UUID caller, UUID id) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode row = records.get("fin_sync_source", owner, id).orElseThrow(() -> new NotFoundException("Source", id));
    return sync(owner, row, false);
  }

  /**
   * Every enabled source of one user, from the daily job. A source whose server needs signing in
   * is skipped quietly; the Connect screen says so. A batch to review raises one notification
   * that names the source, never an amount.
   */
  public int syncAll(UUID owner) {
    int toReview = 0;
    for (ObjectNode row : records.list("fin_sync_source", owner, Map.of())) {
      if (!row.path("enabled").asBoolean(true)) continue;
      SyncResult r = sync(owner, row, true);
      if ("to_review".equals(r.status())) toReview++;
    }
    return toReview;
  }

  private SyncResult sync(UUID owner, ObjectNode row, boolean scheduled) {
    UUID id = UUID.fromString(row.path("id").asText());
    UUID serverId = UUID.fromString(row.path("serverId").asText());
    UUID accountId = UUID.fromString(row.path("accountId").asText());
    Optional<McpServerDto> found = ownServers(owner).stream().filter(s -> s.id().equals(serverId)).findFirst();
    if (found.isEmpty()) return done(owner, id, "failed", null, 0, "That connected server is gone. Link another one.");
    McpServerDto server = found.get();
    if (!server.enabled()) return done(owner, id, "failed", null, 0, server.name() + " is turned off on Connectors.");
    if (!signedIn(server)) {
      return done(owner, id, "needs_sign_in", null, 0, "Sign in to " + server.name() + " on Connectors, then sync again.");
    }
    Optional<Reader> shares = sharesReader(server);
    if (shares.isEmpty()) return done(owner, id, "failed", null, 0, server.name() + " no longer offers its holdings tool.");

    ToolResult a = read(serverId, shares.get());
    if (!a.ok()) return failed(owner, id, server, a.error());
    JsonNode funds = null;
    Optional<Reader> fundsTool = fundsReader(server);
    if (fundsTool.isPresent()) {
      ToolResult b = read(serverId, fundsTool.get());
      if (!b.ok()) return failed(owner, id, server, b.error());
      funds = b.output();
    }
    if (looksSignedOut(a.output()) || looksSignedOut(funds)) {
      return done(owner, id, "needs_sign_in", null, 0, "Sign in to " + server.name() + " on Connectors, then sync again.");
    }
    // The cash is a nice-to-have: a server without the tool, or one that fails it, still syncs holdings.
    JsonNode cash = cashReader(server).map(r -> read(serverId, r))
        .filter(ToolResult::ok).map(ToolResult::output).filter(x -> !looksSignedOut(x)).orElse(null);
    try {
      ImportService.Synced s = imports.fromSource(owner, accountId, server.name(), a.output(), funds, cash);
      if (!s.changed()) return done(owner, id, "no_change", null, s.holdings(), "Nothing changed since the last saved sync.");
      String batchId = s.batch().path("id").asText();
      if (scheduled) notifyReview(owner, server.name(), batchId);
      return done(owner, id, "to_review", batchId, s.holdings(), "Review the holdings, then save them.");
    } catch (ValidationException | NotFoundException e) {
      return done(owner, id, "failed", null, 0, e.getMessage());
    }
  }

  /**
   * Calls one read tool. A paged one ({@code portfolio} holdings) is read page by page until the
   * server says there is no more, and the rows come back as one {@code data} array.
   */
  private ToolResult read(UUID serverId, Reader r) {
    if (!r.paged()) return servers.call(serverId, r.tool(), r.args());
    com.fasterxml.jackson.databind.node.ArrayNode rows = Json.mapper().createArrayNode();
    int from = 0;
    for (int page = 0; page < MAX_PAGES; page++) {
      ToolResult res = servers.call(serverId, r.tool(), r.args().deepCopy().put("from", from));
      if (!res.ok()) return res;
      JsonNode body = parsed(res.output());
      if (body == null) return res; // not JSON: a sign-in sentence or a plain error, handled by the caller
      JsonNode data = body.isArray() ? body : body.path("data");
      if (!data.isArray()) return res;
      data.forEach(rows::add);
      JsonNode more = body.path("pagination");
      boolean hasMore = more.path("has_more").asBoolean(more.path("hasMore").asBoolean(false));
      if (!hasMore || data.isEmpty()) break;
      from += data.size();
    }
    return ToolResult.ok(Json.obj().set("data", rows));
  }

  private static JsonNode parsed(JsonNode answer) {
    if (answer == null) return null;
    if (!answer.isTextual()) return answer;
    try {
      return Json.mapper().readTree(answer.asText());
    } catch (Exception notJson) {
      return null;
    }
  }

  private SyncResult failed(UUID owner, UUID id, McpServerDto server, String error) {
    String e = error == null ? "" : error;
    if (e.contains("log in") || e.contains("login") || e.contains("sign") || e.contains("signing in")) {
      return done(owner, id, "needs_sign_in", null, 0, "Sign in to " + server.name() + " on Connectors, then sync again.");
    }
    log.warn("Holdings sync failed for user {} source {}", owner, id);
    return done(owner, id, "failed", null, 0, server.name() + " didn't answer. Try again in a while.");
  }

  /** Kite answers a holdings call made before login with a plain sentence, not data. */
  static boolean looksSignedOut(JsonNode answer) {
    if (answer == null || !answer.isTextual()) return false;
    String t = answer.asText().toLowerCase(java.util.Locale.ROOT);
    return !t.trim().startsWith("[") && !t.trim().startsWith("{") && (t.contains("log in") || t.contains("login"));
  }

  private SyncResult done(UUID owner, UUID id, String status, String batchId, int holdings, String message) {
    ObjectNode patch = Json.obj().put("lastStatus", status).put("lastSyncedAt", OffsetDateTime.now().toString());
    if (batchId != null) patch.put("lastBatchId", batchId);
    records.update("fin_sync_source", owner, id, patch);
    return new SyncResult(status, batchId, holdings, message);
  }

  private void notifyReview(UUID owner, String serverName, String batchId) {
    UUID kai = agents.byKey(owner, FinanceToolProvider.AGENT_KEY).map(x -> x.id()).orElse(null);
    if (kai == null) return;
    var detail = Json.obj().put("source", "kai_portfolio").put("alert", "sync_review").put("view", "connect")
        .put("batchId", batchId);
    bus.raise(NotificationKind.INFO, kai, new NotificationContext(owner,
        "New holdings from " + serverName + " are ready to review.", detail, null, null, null, null));
  }

  // ── From chat ───────────────────────────────────────────────────────────────

  /** What a sync asked for in chat did for one connected broker. */
  public record ChatSync(String server, UUID accountId, String accountName, boolean linkedNow,
                         boolean accountCreated, SyncResult result) {}

  /**
   * "Sync my Zerodha" in chat: every connected broker server (or the one whose name matches
   * {@code serverHint}) is synced into its account with no mapping by hand. A server not linked
   * yet is linked to the person's own demat account at the same broker, and when there is none,
   * a demat account is created for them at that broker. Each sync lands as a batch to review,
   * exactly as from the Connect screen; nothing is saved until the person says so.
   */
  public List<ChatSync> syncFromChat(UUID caller, String serverHint) {
    UUID owner = scopes.own(caller).ownerUserId();
    String hint = serverHint == null ? "" : serverHint.strip().toLowerCase(java.util.Locale.ROOT);
    List<McpServerDto> brokers = ownServers(owner).stream()
        .filter(s -> sharesReader(s).isPresent())
        .filter(s -> hint.isEmpty() || s.name().toLowerCase(java.util.Locale.ROOT).contains(hint)
            || brand(s.name()).toLowerCase(java.util.Locale.ROOT).contains(hint))
        .toList();
    if (brokers.isEmpty()) {
      throw new ValidationException(hint.isEmpty()
          ? "No broker is connected yet. Add Zerodha Kite (or another broker) under Settings, Connectors, sign in, then ask again."
          : "No connected broker matches \"" + serverHint.strip() + "\". Add it under Settings, Connectors first.");
    }
    List<ChatSync> out = new ArrayList<>();
    for (McpServerDto server : brokers) {
      List<ObjectNode> linked = records.list("fin_sync_source", owner, Map.of("serverId", server.id().toString()));
      boolean linkedNow = false;
      boolean created = false;
      if (linked.isEmpty()) {
        UUID[] account = {null};
        created = pickOrCreateAccount(owner, server, account);
        linked = List.of(records.insert("fin_sync_source", owner, Json.obj()
            .put("serverId", server.id().toString()).put("accountId", account[0].toString()).put("enabled", true)));
        linkedNow = true;
        log.info("Linked broker source for user {} from chat (new account: {})", owner, created);
      }
      for (ObjectNode row : linked) {
        UUID accountId = UUID.fromString(row.path("accountId").asText());
        SyncResult r = row.path("enabled").asBoolean(true) ? sync(owner, row, false)
            : new SyncResult("paused", null, 0, "Syncing from " + server.name() + " is paused under Connect.");
        out.add(new ChatSync(server.name(), accountId, source(owner, row, List.of(server)).accountName(),
            linkedNow, created, r));
      }
    }
    return out;
  }

  /**
   * The demat account a broker's holdings go into: the person's own active one at that broker (by
   * the provider's name), else a single unlinked one with no provider named, else a new demat
   * account at that broker. Returns true when it was created.
   */
  private boolean pickOrCreateAccount(UUID owner, McpServerDto server, UUID[] out) {
    String brand = brand(server.name());
    String b = brand.toLowerCase(java.util.Locale.ROOT);
    java.util.Set<String> taken = new java.util.HashSet<>();
    records.list("fin_sync_source", owner, Map.of()).forEach(r -> taken.add(r.path("accountId").asText()));
    String self = people.selfId(owner).toString();
    List<ObjectNode> mine = records.list("fin_account", owner, Map.of()).stream()
        .filter(a -> "demat".equals(a.path("kind").asText()) || "us_broker".equals(a.path("kind").asText()))
        .filter(a -> !"closed".equals(a.path("status").asText()))
        .filter(a -> self.equals(a.path("personId").asText()))
        .toList();
    for (ObjectNode a : mine) {
      String p = a.path("provider").asText("").toLowerCase(java.util.Locale.ROOT);
      if (!p.isBlank() && (p.contains(b) || b.contains(p))) {
        out[0] = UUID.fromString(a.path("id").asText());
        return false;
      }
    }
    List<ObjectNode> blank = mine.stream().filter(a -> a.path("provider").asText("").isBlank())
        .filter(a -> !taken.contains(a.path("id").asText())).toList();
    if (blank.size() == 1) {
      out[0] = UUID.fromString(blank.get(0).path("id").asText());
      return false;
    }
    ObjectNode a = records.insert("fin_account", owner, Json.obj().put("personId", self).put("kind", "demat")
        .put("provider", brand.length() > 60 ? brand.substring(0, 60) : brand).put("source", "api"));
    out[0] = UUID.fromString(a.path("id").asText());
    return true;
  }

  /** The broker a server stands for: Zerodha for Kite, else the server's own name. */
  static String brand(String serverName) {
    String n = serverName == null ? "" : serverName.strip();
    String l = n.toLowerCase(java.util.Locale.ROOT);
    if (l.contains("kite") || l.contains("zerodha")) return "Zerodha";
    return n.isEmpty() ? "Broker" : n;
  }

  // ── Orders (part D) ─────────────────────────────────────────────────────────

  /**
   * Where an order for this account can go: the connected server linked to it that offers order
   * placing and is signed in. {@code serverId} is null when there is none, and {@code reason} says
   * why in words for the screen. Zerodha's hosted Kite server offers no order placing.
   */
  public record OrderRoute(UUID serverId, String serverName, String tool, String reason) {
    public boolean available() { return serverId != null; }
  }

  public OrderRoute orderRoute(UUID owner, UUID accountId) {
    List<McpServerDto> mine = ownServers(owner);
    String reason = "No broker is connected to this account. Link one under Connect.";
    for (ObjectNode row : records.list("fin_sync_source", owner, Map.of("accountId", accountId.toString()))) {
      UUID serverId = UUID.fromString(row.path("serverId").asText());
      McpServerDto s = mine.stream().filter(x -> x.id().equals(serverId)).findFirst().orElse(null);
      if (s == null || !s.enabled()) continue;
      Optional<McpToolDto> place = tool(s, "place_order");
      if (place.isEmpty()) {
        reason = s.name() + " doesn't offer order placing here. Zerodha's hosted server only reads; placing needs your own Kite Connect key.";
        continue;
      }
      if (!signedIn(s)) {
        reason = "Sign in to " + s.name() + " on Connectors first.";
        continue;
      }
      return new OrderRoute(s.id(), s.name(), place.get().name(), null);
    }
    return new OrderRoute(null, null, null, reason);
  }

  /** Sends one order the person confirmed; only {@code place_order}, never another tool. */
  public ToolResult placeOrder(UUID owner, OrderRoute route, ObjectNode arguments) {
    if (route == null || !route.available() || !"place_order".equals(route.tool())) {
      throw new ValidationException("No connected broker can place this order.");
    }
    if (ownServers(owner).stream().noneMatch(s -> s.id().equals(route.serverId()))) {
      throw new NotFoundException("Connected server", route.serverId());
    }
    return servers.call(route.serverId(), route.tool(), arguments);
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  /** The person's own servers in their personal workspace; never someone else's broker. */
  private List<McpServerDto> ownServers(UUID owner) {
    UUID ws = workspaces.personalWorkspaceOf(owner);
    if (ws == null) return List.of();
    return servers.list(ws).stream().filter(s -> owner.equals(s.userId())).toList();
  }

  private static Optional<McpToolDto> tool(McpServerDto s, String name) {
    return s.tools() == null ? Optional.empty()
        : s.tools().stream().filter(t -> name.equals(t.name()) && t.enabled()).findFirst();
  }

  private static boolean signedIn(McpServerDto s) {
    if ("needs_auth".equals(s.status())) return false;
    return s.signInTool() == null || s.sessionSignInAt() != null;
  }

  private Source source(UUID owner, ObjectNode row, List<McpServerDto> known) {
    UUID serverId = UUID.fromString(row.path("serverId").asText());
    McpServerDto server = known.stream().filter(s -> s.id().equals(serverId)).findFirst().orElse(null);
    String accountId = row.path("accountId").asText();
    String accountName = records.get("fin_account", owner, UUID.fromString(accountId))
        .map(a -> String.join(" · ", java.util.stream.Stream.of(a.path("provider").asText(""), a.path("nickname").asText(""))
            .filter(x -> !x.isBlank()).toList()))
        .filter(x -> !x.isBlank()).orElse("Demat account");
    return new Source(UUID.fromString(row.path("id").asText()), serverId, server == null ? "Removed server" : server.name(),
        UUID.fromString(accountId), accountName, row.path("enabled").asBoolean(true),
        server != null && signedIn(server), row.path("lastStatus").asText(null), row.path("lastSyncedAt").asText(null),
        row.path("lastBatchId").asText(null));
  }
}

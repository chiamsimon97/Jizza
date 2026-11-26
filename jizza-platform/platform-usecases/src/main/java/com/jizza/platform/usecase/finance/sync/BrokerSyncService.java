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
 * of their demat accounts. A sync calls only the server's holdings tools ({@code get_holdings},
 * {@code get_mf_holdings}), never anything that changes the account, and lands as a holdings
 * import the person reviews before it is saved. The answers are never logged; the log names only
 * the user, the source and counts.
 */
@Service
public class BrokerSyncService {

  private static final Logger log = LoggerFactory.getLogger(BrokerSyncService.class);

  /** The only tools a sync may call: they read and never change the account. */
  static final String SHARES_TOOL = "get_holdings";
  static final String FUNDS_TOOL = "get_mf_holdings";
  static final Set<String> READ_TOOLS = Set.of(SHARES_TOOL, FUNDS_TOOL);
  private static final Set<String> BROKER_ACCOUNTS = Set.of("demat", "us_broker", "other");

  private final FinanceRecords records;
  private final FinScopeResolver scopes;
  private final McpServers servers;
  private final WorkspaceDirectory workspaces;
  private final ImportService imports;
  private final NotificationBus bus;
  private final AgentDirectory agents;

  public BrokerSyncService(FinanceRecords records, FinScopeResolver scopes, McpServers servers,
                           WorkspaceDirectory workspaces, ImportService imports, NotificationBus bus,
                           AgentDirectory agents) {
    this.records = records;
    this.scopes = scopes;
    this.servers = servers;
    this.workspaces = workspaces;
    this.imports = imports;
    this.bus = bus;
    this.agents = agents;
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
      if (tool(s, SHARES_TOOL).isEmpty()) continue;
      candidates.add(new Candidate(s.id(), s.name(), signedIn(s), s.enabled(), tool(s, FUNDS_TOOL).isPresent(),
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
    if (tool(server, SHARES_TOOL).isEmpty()) {
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
    Optional<McpToolDto> shares = tool(server, SHARES_TOOL);
    if (shares.isEmpty()) return done(owner, id, "failed", null, 0, server.name() + " no longer offers its holdings tool.");

    ToolResult a = servers.call(serverId, shares.get().name(), Json.obj());
    if (!a.ok()) return failed(owner, id, server, a.error());
    JsonNode funds = null;
    Optional<McpToolDto> fundsTool = tool(server, FUNDS_TOOL);
    if (fundsTool.isPresent()) {
      ToolResult b = servers.call(serverId, fundsTool.get().name(), Json.obj());
      if (!b.ok()) return failed(owner, id, server, b.error());
      funds = b.output();
    }
    if (looksSignedOut(a.output()) || looksSignedOut(funds)) {
      return done(owner, id, "needs_sign_in", null, 0, "Sign in to " + server.name() + " on Connectors, then sync again.");
    }
    try {
      ImportService.Synced s = imports.fromSource(owner, accountId, server.name(), a.output(), funds);
      if (!s.changed()) return done(owner, id, "no_change", null, s.holdings(), "Nothing changed since the last saved sync.");
      String batchId = s.batch().path("id").asText();
      if (scheduled) notifyReview(owner, server.name(), batchId);
      return done(owner, id, "to_review", batchId, s.holdings(), "Review the holdings, then save them.");
    } catch (ValidationException | NotFoundException e) {
      return done(owner, id, "failed", null, 0, e.getMessage());
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

package com.jizza.platform.usecase.finance.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceRecords;
import com.jizza.engine.api.finance.StatementFiles;
import com.jizza.engine.api.mcp.McpServerDto;
import com.jizza.engine.api.mcp.McpServers;
import com.jizza.engine.api.mcp.McpToolDto;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import com.jizza.platform.usecase.finance.imports.ImportService;
import com.jizza.platform.usecase.finance.records.FinRecordsUseCase;
import com.jizza.platform.usecase.finance.sharing.FinScopeResolver;
import com.jizza.platform.usecase.finance.tools.FinanceToolProvider;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Statements from email (Kai part B): a mail server the person added on Connectors (Gmail), the
 * sender whose mails carry a statement, and where it goes: a CAS for one person, or a bank
 * statement for one bank account. A check calls only the server's read tools, {@code
 * search_threads} and {@code get_thread}, finds PDF attachments in the raw mail, opens them with
 * the saved password and lands each new one as an import the person reviews. The password is kept
 * encrypted and never returned; mail contents are never logged.
 */
@Service
public class MailPullService {

  private static final Logger log = LoggerFactory.getLogger(MailPullService.class);

  /** The only tools a check may call: they read and never change the mailbox. */
  static final String SEARCH_TOOL = "search_threads";
  static final String READ_TOOL = "get_thread";
  static final Set<String> READ_TOOLS = Set.of(SEARCH_TOOL, READ_TOOL);
  private static final Set<String> BANK_ACCOUNTS = Set.of("savings", "current", "credit_card", "wallet", "deposit", "other");
  /** The registrars' own mail domains, for a CAS source with no sender typed. */
  public static final String CAS_SENDERS = "camsonline.com kfintech.com";
  private static final Pattern SENDER = Pattern.compile("[A-Za-z0-9@._+-]{3,100}");
  private static final int MAX_THREADS = 10;
  private static final int FIRST_LOOK_DAYS = 45;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final FinanceRecords records;
  private final FinScopeResolver scopes;
  private final FinRecordsUseCase forms;
  private final McpServers servers;
  private final WorkspaceDirectory workspaces;
  private final ImportService imports;
  private final EncryptionService crypto;
  private final NotificationBus bus;
  private final AgentDirectory agents;

  public MailPullService(FinanceRecords records, FinScopeResolver scopes, FinRecordsUseCase forms, McpServers servers,
                         WorkspaceDirectory workspaces, ImportService imports, EncryptionService crypto,
                         NotificationBus bus, AgentDirectory agents) {
    this.records = records;
    this.scopes = scopes;
    this.forms = forms;
    this.servers = servers;
    this.workspaces = workspaces;
    this.imports = imports;
    this.crypto = crypto;
    this.bus = bus;
    this.agents = agents;
  }

  /** A server on Connectors that can be a mail source: it can search and read mail. */
  public record Candidate(UUID serverId, String name, boolean signedIn, boolean enabled) {}

  /** One mail source, as the screen shows it. The password itself is never sent back. */
  public record Source(UUID id, UUID serverId, String serverName, String kind, UUID personId, UUID accountId,
                       String accountName, String sender, String subject, boolean hasPassword, boolean enabled,
                       boolean signedIn, String lastStatus, String lastCheckedAt, String lastBatchId) {}

  public record SourcesView(List<Source> sources, List<Candidate> servers, String casSenders) {}

  /** What a check did. {@code found} counts new statements landed for review. */
  public record CheckResult(String status, String batchId, int found, String message) {}

  /** The person's choices when linking or changing a source. Blank password keeps the saved one. */
  public record Link(UUID serverId, String kind, UUID personId, UUID accountId, String sender, String subject,
                     String password) {}

  // ── Reading ─────────────────────────────────────────────────────────────────

  public SourcesView view(UUID caller) {
    UUID owner = scopes.own(caller).ownerUserId();
    List<McpServerDto> mine = ownServers(owner);
    List<Candidate> candidates = new ArrayList<>();
    for (McpServerDto s : mine) {
      if (tool(s, SEARCH_TOOL).isEmpty() || tool(s, READ_TOOL).isEmpty()) continue;
      candidates.add(new Candidate(s.id(), s.name(), signedIn(s), s.enabled()));
    }
    List<Source> sources = new ArrayList<>();
    for (ObjectNode row : records.list("fin_mail_source", owner, Map.of())) sources.add(source(owner, row, mine));
    return new SourcesView(sources, candidates, CAS_SENDERS);
  }

  // ── Linking ─────────────────────────────────────────────────────────────────

  public Source link(UUID caller, Link l) {
    UUID owner = scopes.own(caller).ownerUserId();
    if (l.serverId() == null) throw new ValidationException("Choose the mail connector to read from.");
    McpServerDto server = ownServers(owner).stream().filter(s -> s.id().equals(l.serverId())).findFirst()
        .orElseThrow(() -> new NotFoundException("Connected server", l.serverId()));
    if (tool(server, SEARCH_TOOL).isEmpty() || tool(server, READ_TOOL).isEmpty()) {
      throw new ValidationException(server.name() + " can't search and read mail, so Kai can't find statements in it.");
    }
    String kind = l.kind() == null ? "" : l.kind();
    ObjectNode row = Json.obj().put("serverId", server.id().toString()).put("kind", kind).put("enabled", true);
    if (kind.equals("cas")) {
      UUID personId = l.personId() == null ? forms.selfId(owner) : l.personId();
      forms.requirePerson(owner, personId);
      row.put("personId", personId.toString());
      row.put("sender", senders(l.sender() == null || l.sender().isBlank() ? CAS_SENDERS : l.sender()));
    } else if (kind.equals("bank")) {
      if (l.accountId() == null) throw new ValidationException("Choose the bank account these statements are for.");
      ObjectNode account = records.get("fin_account", owner, l.accountId())
          .orElseThrow(() -> new NotFoundException("Account", l.accountId()));
      if (!BANK_ACCOUNTS.contains(account.path("kind").asText())) {
        throw new ValidationException("A bank statement goes with a bank, card or wallet account.");
      }
      if (l.sender() == null || l.sender().isBlank()) {
        throw new ValidationException("Type the address your bank mails statements from. It's on any statement email.");
      }
      row.put("accountId", l.accountId().toString());
      row.put("sender", senders(l.sender()));
    } else {
      throw new ValidationException("Choose CAS or bank statement.");
    }
    String subject = subject(l.subject());
    if (subject != null) row.put("subject", subject);
    ObjectNode saved = records.insert("fin_mail_source", owner, row);
    UUID id = UUID.fromString(saved.path("id").asText());
    if (l.password() != null && !l.password().isBlank()) savePassword(owner, id, l.password());
    return source(owner, saved, List.of(server));
  }

  /** Changes the sender, subject, password or on/off of a source. */
  public Source change(UUID caller, UUID id, String sender, String subject, String password, Boolean enabled) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode row = records.get("fin_mail_source", owner, id).orElseThrow(() -> new NotFoundException("Mail source", id));
    ObjectNode patch = Json.obj();
    if (sender != null) {
      if (sender.isBlank() && !"cas".equals(row.path("kind").asText())) {
        throw new ValidationException("Type the address your bank mails statements from.");
      }
      patch.put("sender", senders(sender.isBlank() ? CAS_SENDERS : sender));
    }
    if (subject != null) {
      String s = subject(subject);
      if (s == null) patch.putNull("subject");
      else patch.put("subject", s);
    }
    if (enabled != null) patch.put("enabled", enabled);
    if (!patch.isEmpty()) row = records.update("fin_mail_source", owner, id, patch).orElse(row);
    if (password != null && !password.isBlank()) savePassword(owner, id, password);
    return source(owner, row, ownServers(owner));
  }

  public void unlink(UUID caller, UUID id) {
    UUID owner = scopes.own(caller).ownerUserId();
    if (!records.delete("fin_mail_source", owner, id)) throw new NotFoundException("Mail source", id);
  }

  // ── Checking ────────────────────────────────────────────────────────────────

  public CheckResult checkNow(UUID caller, UUID id) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode row = records.get("fin_mail_source", owner, id).orElseThrow(() -> new NotFoundException("Mail source", id));
    return check(owner, row, false);
  }

  /** Every enabled source of one user, from the daily job. Returns how many statements are new. */
  public int checkAll(UUID owner) {
    int found = 0;
    for (ObjectNode row : records.list("fin_mail_source", owner, Map.of())) {
      if (!row.path("enabled").asBoolean(true)) continue;
      found += check(owner, row, true).found();
    }
    return found;
  }

  private CheckResult check(UUID owner, ObjectNode row, boolean scheduled) {
    UUID id = UUID.fromString(row.path("id").asText());
    UUID serverId = UUID.fromString(row.path("serverId").asText());
    Optional<McpServerDto> found = ownServers(owner).stream().filter(s -> s.id().equals(serverId)).findFirst();
    if (found.isEmpty()) return done(owner, id, "failed", null, 0, "That mail connector is gone. Link another one.");
    McpServerDto server = found.get();
    if (!server.enabled()) return done(owner, id, "failed", null, 0, server.name() + " is turned off on Connectors.");
    if (!signedIn(server)) return signIn(owner, id, server);
    Optional<McpToolDto> search = tool(server, SEARCH_TOOL);
    Optional<McpToolDto> read = tool(server, READ_TOOL);
    if (search.isEmpty() || read.isEmpty()) {
      return done(owner, id, "failed", null, 0, server.name() + " no longer offers its mail search and read tools.");
    }

    ToolResult hits = servers.call(serverId, search.get().name(),
        Json.obj().put("query", query(row)).put("pageSize", MAX_THREADS));
    if (!hits.ok()) return failed(owner, id, server, hits.error());
    List<String> threads = threadIds(hits.output());

    String kind = row.path("kind").asText();
    UUID personId = row.hasNonNull("personId") ? UUID.fromString(row.path("personId").asText()) : null;
    UUID accountId = row.hasNonNull("accountId") ? UUID.fromString(row.path("accountId").asText()) : null;
    String password = records.readSecret("fin_mail_source", owner, id, "passwordEnc").map(crypto::decryptString).orElse(null);
    String label = "Email · " + server.name();

    int landed = 0;
    String lastBatch = null;
    boolean locked = false;
    for (String threadId : threads) {
      ToolResult t = servers.call(serverId, read.get().name(), Json.obj().put("threadId", threadId).put("messageFormat", "RAW"));
      if (!t.ok()) return failed(owner, id, server, t.error());
      for (String raw : raws(t.output())) {
        for (MimePdfs.Pdf pdf : MimePdfs.extract(raw)) {
          ImportService.Upload u = new ImportService.Upload(kind.equals("cas") ? "cas_pdf" : "bank_pdf",
              personId, kind.equals("cas") ? null : accountId, null, password, pdf.filename(), "application/pdf", pdf.bytes());
          try {
            Optional<ObjectNode> batch = imports.fromMail(owner, u, label);
            if (batch.isPresent()) {
              landed++;
              lastBatch = batch.get().path("id").asText();
            }
          } catch (StatementFiles.PdfLockedException needsPassword) {
            locked = true;
          } catch (ValidationException | ConflictException | NotFoundException notAStatement) {
            // Another PDF from the same sender (a newsletter, say): left alone.
          }
        }
      }
    }
    if (landed > 0) {
      if (scheduled) notifyReview(owner, kind);
      return done(owner, id, "to_review", lastBatch, landed,
          landed == 1 ? "A new statement is ready to review." : landed + " new statements are ready to review.");
    }
    if (locked) {
      return done(owner, id, "needs_password", null, 0, password == null
          ? "The statement is locked. Save its PDF password on this source, then check again."
          : "The saved password didn't open the statement. Change it, then check again.");
    }
    return done(owner, id, "nothing_new", null, 0, threads.isEmpty()
        ? "No statement mails from this sender since the last check." : "Nothing new: those statements are already here.");
  }

  /** The Gmail search: the sender, PDF attachments, and since a little before the last check. */
  static String query(ObjectNode row, LocalDate today) {
    String from = String.join(" OR ", row.path("sender").asText().split("\\s+"));
    LocalDate since = row.hasNonNull("lastCheckedAt")
        ? OffsetDateTime.parse(row.path("lastCheckedAt").asText()).atZoneSameInstant(IST).toLocalDate().minusDays(3)
        : today.minusDays(FIRST_LOOK_DAYS);
    StringBuilder q = new StringBuilder("from:(").append(from).append(") has:attachment filename:pdf after:")
        .append(since.format(DateTimeFormatter.ofPattern("yyyy/MM/dd")));
    if (row.hasNonNull("subject")) q.append(" subject:(\"").append(row.path("subject").asText()).append("\")");
    return q.toString();
  }

  private static String query(ObjectNode row) {
    return query(row, LocalDate.now(IST));
  }

  /** Thread ids from a search answer: {threads:[{id}]}, a bare array, or the same as JSON text. */
  static List<String> threadIds(JsonNode answer) {
    JsonNode n = asJson(answer);
    JsonNode list = n == null ? null : n.isArray() ? n : n.path("threads");
    Set<String> ids = new LinkedHashSet<>();
    if (list != null && list.isArray()) {
      for (JsonNode t : list) {
        String id = t.isTextual() ? t.asText() : t.path("id").asText(t.path("threadId").asText(""));
        if (!id.isBlank() && id.length() <= 100) ids.add(id);
        if (ids.size() >= MAX_THREADS) break;
      }
    }
    return new ArrayList<>(ids);
  }

  /** Every raw message in a thread answer. */
  static List<String> raws(JsonNode answer) {
    JsonNode n = asJson(answer);
    List<String> out = new ArrayList<>();
    if (n == null) return out;
    collectRaw(n, out, 0);
    return out;
  }

  private static void collectRaw(JsonNode n, List<String> out, int depth) {
    if (depth > 4) return;
    if (n.isObject()) {
      if (n.path("raw").isTextual()) out.add(n.path("raw").asText());
      n.fields().forEachRemaining(e -> {
        if (!e.getKey().equals("raw")) collectRaw(e.getValue(), out, depth + 1);
      });
    } else if (n.isArray()) {
      for (JsonNode x : n) collectRaw(x, out, depth + 1);
    }
  }

  private static JsonNode asJson(JsonNode answer) {
    if (answer == null || answer.isNull()) return null;
    if (!answer.isTextual()) return answer;
    String t = answer.asText().strip();
    if (!t.startsWith("{") && !t.startsWith("[")) return null;
    try {
      return MAPPER.readTree(t);
    } catch (Exception unreadable) {
      return null;
    }
  }

  /** One or more addresses or domains, separated by spaces or commas. */
  static String senders(String raw) {
    List<String> out = new ArrayList<>();
    for (String s : raw.split("[,;\\s]+")) {
      if (s.isBlank()) continue;
      if (!SENDER.matcher(s).matches()) {
        throw new ValidationException("Type the sender as an email address or a domain, such as statements@yourbank.com.");
      }
      out.add(s.toLowerCase(java.util.Locale.ROOT));
    }
    if (out.isEmpty() || out.size() > 5) throw new ValidationException("Type one to five senders.");
    return String.join(" ", out);
  }

  /** Words the statement mail's subject has, or null. Quotes and brackets are dropped. */
  static String subject(String raw) {
    if (raw == null) return null;
    String s = raw.replaceAll("[\"(){}\\[\\]:]", " ").replaceAll("\\s+", " ").strip();
    if (s.isEmpty()) return null;
    return s.length() > 120 ? s.substring(0, 120) : s;
  }

  private void savePassword(UUID owner, UUID id, String password) {
    if (password.length() > 100) throw new ValidationException("That password is too long.");
    records.writeSecret("fin_mail_source", owner, id, "passwordEnc", crypto.encryptString(password));
  }

  private CheckResult signIn(UUID owner, UUID id, McpServerDto server) {
    return done(owner, id, "needs_sign_in", null, 0, "Sign in to " + server.name() + " on Connectors, then check again.");
  }

  private CheckResult failed(UUID owner, UUID id, McpServerDto server, String error) {
    String e = error == null ? "" : error.toLowerCase(java.util.Locale.ROOT);
    if (e.contains("401") || e.contains("unauthor") || e.contains("sign in") || e.contains("log in") || e.contains("token")) {
      return signIn(owner, id, server);
    }
    log.warn("Mail check failed for user {} source {}", owner, id);
    return done(owner, id, "failed", null, 0, server.name() + " didn't answer. Try again in a while.");
  }

  private CheckResult done(UUID owner, UUID id, String status, String batchId, int found, String message) {
    ObjectNode patch = Json.obj().put("lastStatus", status);
    // The next search starts from here only when this one got through.
    if (!status.equals("needs_sign_in") && !status.equals("failed")) patch.put("lastCheckedAt", OffsetDateTime.now().toString());
    if (batchId != null) patch.put("lastBatchId", batchId);
    records.update("fin_mail_source", owner, id, patch);
    return new CheckResult(status, batchId, found, message);
  }

  private void notifyReview(UUID owner, String kind) {
    UUID kai = agents.byKey(owner, FinanceToolProvider.AGENT_KEY).map(x -> x.id()).orElse(null);
    if (kai == null) return;
    var detail = Json.obj().put("source", "kai_portfolio").put("alert", "mail_review").put("view", "connect");
    bus.raise(NotificationKind.INFO, kai, new NotificationContext(owner,
        "cas".equals(kind) ? "Your new CAS from email is ready to review." : "A new bank statement from email is ready to review.",
        detail, null, null, null, null));
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

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
    UUID id = UUID.fromString(row.path("id").asText());
    UUID serverId = UUID.fromString(row.path("serverId").asText());
    McpServerDto server = known.stream().filter(s -> s.id().equals(serverId)).findFirst().orElse(null);
    UUID accountId = row.hasNonNull("accountId") ? UUID.fromString(row.path("accountId").asText()) : null;
    String accountName = accountId == null ? null : records.get("fin_account", owner, accountId)
        .map(a -> String.join(" · ", java.util.stream.Stream.of(a.path("provider").asText(""), a.path("nickname").asText(""))
            .filter(x -> !x.isBlank()).toList()))
        .filter(x -> !x.isBlank()).orElse("Bank account");
    boolean hasPassword = records.readSecret("fin_mail_source", owner, id, "passwordEnc").isPresent();
    return new Source(id, serverId, server == null ? "Removed connector" : server.name(), row.path("kind").asText(),
        row.hasNonNull("personId") ? UUID.fromString(row.path("personId").asText()) : null, accountId, accountName,
        row.path("sender").asText(), row.path("subject").asText(null), hasPassword, row.path("enabled").asBoolean(true),
        server != null && signedIn(server), row.path("lastStatus").asText(null), row.path("lastCheckedAt").asText(null),
        row.path("lastBatchId").asText(null));
  }
}

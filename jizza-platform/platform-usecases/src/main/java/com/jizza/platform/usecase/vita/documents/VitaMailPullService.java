package com.jizza.platform.usecase.vita.documents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.enums.NotificationKind;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.mcp.McpServerDto;
import com.jizza.engine.api.mcp.McpServers;
import com.jizza.engine.api.mcp.McpToolDto;
import com.jizza.engine.api.notification.NotificationBus;
import com.jizza.engine.api.notification.NotificationContext;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.engine.api.workspace.WorkspaceDirectory;
import com.jizza.platform.usecase.finance.sync.MimePdfs;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import com.jizza.platform.usecase.vita.tools.VitaToolProvider;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reports from the mailbox (spec 6): a mail server the user added on Connectors, the sender (a
 * lab) whose mails carry reports, and the person they are for. A check calls only the server's read
 * tools, {@code search_threads} and {@code get_thread}, takes the PDFs out of the raw mail, files
 * each new one as a document for that person and reads it into a draft. Nothing is saved from a
 * report until the user confirms the review. A PDF already handled is never handled again. The mail
 * is data, never instructions; it is never sent, deleted or marked; and no sender, subject or text
 * is ever logged. Shaped like Kai's {@code MailPullService}.
 */
@Service
public class VitaMailPullService {

  private static final Logger log = LoggerFactory.getLogger(VitaMailPullService.class);

  static final String TABLE = "vita_mail_source";
  /** The only tools a check may call: they read and never change the mailbox. */
  public static final String SEARCH_TOOL = "search_threads";
  public static final String READ_TOOL = "get_thread";
  public static final Set<String> READ_TOOLS = Set.of(SEARCH_TOOL, READ_TOOL);
  private static final Pattern SENDER = Pattern.compile("[A-Za-z0-9@._+-]{3,100}");
  private static final int MAX_THREADS = 10;
  private static final int FIRST_LOOK_DAYS = 45;
  /** How many fingerprints a source remembers; far more than a lab mails between checks. */
  static final int HANDLED_MAX = 300;
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private final VitaRecords records;
  private final VitaScopeResolver scopes;
  private final VitaDocumentUseCase documents;
  private final McpServers servers;
  private final WorkspaceDirectory workspaces;
  private final EncryptionService crypto;
  private final NotificationBus bus;
  private final AgentDirectory agents;

  public VitaMailPullService(VitaRecords records, VitaScopeResolver scopes, VitaDocumentUseCase documents,
                             McpServers servers, WorkspaceDirectory workspaces, EncryptionService crypto,
                             NotificationBus bus, AgentDirectory agents) {
    this.records = records;
    this.scopes = scopes;
    this.documents = documents;
    this.servers = servers;
    this.workspaces = workspaces;
    this.crypto = crypto;
    this.bus = bus;
    this.agents = agents;
  }

  /** A server on Connectors that can be a mail source: it can search and read mail. */
  public record Candidate(UUID serverId, String name, boolean signedIn, boolean enabled) {}

  /** One mail source, as the screen shows it. The password itself is never sent back. */
  public record Source(UUID id, UUID serverId, String serverName, UUID personId, String personName, String sender,
                       String subject, boolean hasPassword, boolean enabled, boolean signedIn, String lastStatus,
                       String lastCheckedAt, String lastDocumentId) {}

  public record SourcesView(List<Source> sources, List<Candidate> servers) {}

  /** What a check did. {@code found} counts new reports filed for review. */
  public record CheckResult(String status, String documentId, int found, String message) {}

  /** The user's choices when linking or changing a source. A blank password keeps the saved one. */
  public record Link(UUID serverId, UUID personId, String sender, String subject, String password) {}

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
    for (ObjectNode row : records.list(TABLE, owner, Map.of())) sources.add(source(caller, owner, row, mine));
    return new SourcesView(sources, candidates);
  }

  // ── Linking ─────────────────────────────────────────────────────────────────

  public Source link(UUID caller, Link l) {
    UUID owner = scopes.own(caller).ownerUserId();
    if (l.serverId() == null) throw new ValidationException("Choose the mail connector to read from.");
    McpServerDto server = ownServers(owner).stream().filter(s -> s.id().equals(l.serverId())).findFirst()
        .orElseThrow(() -> new NotFoundException("Connected server", l.serverId()));
    if (tool(server, SEARCH_TOOL).isEmpty() || tool(server, READ_TOOL).isEmpty()) {
      throw new ValidationException(server.name() + " can't search and read mail, so Vita can't find reports in it.");
    }
    if (l.personId() == null) throw new ValidationException("Choose whose reports these are.");
    PersonScope p = person(caller, owner, l.personId());
    if (l.sender() == null || l.sender().isBlank()) {
      throw new ValidationException("Type the address your lab mails reports from. It's on any report email.");
    }
    ObjectNode row = Json.obj().put("serverId", server.id().toString()).put("personId", p.personId().toString())
        .put("sender", senders(l.sender())).put("enabled", true);
    String subject = subject(l.subject());
    if (subject != null) row.put("subject", subject);
    ObjectNode saved = records.insert(TABLE, owner, row);
    UUID id = UUID.fromString(saved.path("id").asText());
    if (l.password() != null && !l.password().isBlank()) savePassword(owner, id, l.password());
    return source(caller, owner, saved, List.of(server));
  }

  /** Changes the sender, subject, person, password or on/off of a source. */
  public Source change(UUID caller, UUID id, JsonNode body) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode row = records.get(TABLE, owner, id).orElseThrow(() -> new NotFoundException("Mail source", id));
    if (body == null || !body.isObject()) throw new ValidationException("Send the fields as an object.");
    ObjectNode patch = Json.obj();
    if (body.has("sender")) {
      String sender = body.path("sender").asText("");
      if (sender.isBlank()) throw new ValidationException("Type the address your lab mails reports from.");
      patch.put("sender", senders(sender));
    }
    if (body.has("subject")) {
      String s = subject(body.path("subject").asText(null));
      if (s == null) patch.putNull("subject");
      else patch.put("subject", s);
    }
    if (body.hasNonNull("personId")) {
      UUID personId;
      try {
        personId = UUID.fromString(body.get("personId").asText());
      } catch (IllegalArgumentException e) {
        throw new ValidationException("personId must be an id.");
      }
      patch.put("personId", person(caller, owner, personId).personId().toString());
    }
    if (body.has("enabled")) {
      if (!body.get("enabled").isBoolean()) throw new ValidationException("enabled is true or false.");
      patch.put("enabled", body.get("enabled").asBoolean());
    }
    if (!patch.isEmpty()) row = records.update(TABLE, owner, id, patch).orElse(row);
    String password = body.path("password").asText("");
    if (!password.isBlank()) savePassword(owner, id, password);
    return source(caller, owner, row, ownServers(owner));
  }

  public void unlink(UUID caller, UUID id) {
    UUID owner = scopes.own(caller).ownerUserId();
    if (!records.delete(TABLE, owner, id)) throw new NotFoundException("Mail source", id);
  }

  // ── Checking ────────────────────────────────────────────────────────────────

  public CheckResult checkNow(UUID caller, UUID id) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode row = records.get(TABLE, owner, id).orElseThrow(() -> new NotFoundException("Mail source", id));
    return check(owner, row, false);
  }

  /** Every enabled source of one user, from the daily job. Returns how many reports are new. */
  public int checkAll(UUID owner) {
    int found = 0;
    for (ObjectNode row : records.list(TABLE, owner, Map.of())) {
      if (!row.path("enabled").asBoolean(true)) continue;
      try {
        found += check(owner, row, true).found();
      } catch (RuntimeException e) {
        log.warn("Vita mail check failed for user {} source {}: {}", owner, row.path("id").asText(), e.getClass().getSimpleName());
      }
    }
    return found;
  }

  /** The users who have Vita records, mail sources among them, for the daily job. */
  public List<UUID> owners() {
    return records.ownersWithRecords();
  }

  private CheckResult check(UUID owner, ObjectNode row, boolean scheduled) {
    UUID id = UUID.fromString(row.path("id").asText());
    UUID serverId = UUID.fromString(row.path("serverId").asText());
    UUID personId = UUID.fromString(row.path("personId").asText());
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
        Json.obj().put("query", query(row, LocalDate.now(IST))).put("pageSize", MAX_THREADS));
    if (!hits.ok()) return failed(owner, id, server, hits.error());
    List<String> threads = threadIds(hits.output());

    String password = records.readSecret(TABLE, owner, id, "passwordEnc").map(crypto::decryptString).orElse(null);
    Set<String> handled = handled(row);
    int landed = 0;
    String lastDocument = null;
    boolean locked = false;
    boolean seen = false;
    for (String threadId : threads) {
      ToolResult t = servers.call(serverId, read.get().name(), Json.obj().put("threadId", threadId).put("messageFormat", "RAW"));
      if (!t.ok()) return failed(owner, id, server, t.error());
      for (String raw : raws(t.output())) {
        for (MimePdfs.Pdf pdf : MimePdfs.extract(raw)) {
          String ref = fingerprint(pdf.bytes());
          if (handled.contains(ref)) {
            seen = true;
            // Filed before but still locked: try again now that a password may be saved.
            Optional<ObjectNode> waiting = lockedDocument(owner, ref);
            if (waiting.isEmpty()) continue;
            if (password == null) {
              locked = true;
              continue;
            }
            Filed again = read(owner, UUID.fromString(waiting.get().path("id").asText()), password);
            if (again.locked()) locked = true;
            else {
              landed++;
              lastDocument = again.documentId();
            }
            continue;
          }
          Filed f = file(owner, personId, pdf, ref, password);
          if (f == null) continue;
          handled.add(ref);
          if (f.locked()) locked = true;
          else {
            landed++;
            lastDocument = f.documentId();
          }
        }
      }
    }
    records.update(TABLE, owner, id, Json.obj().set("handled", handledNode(handled)));
    if (landed > 0) {
      if (scheduled) notifyReview(owner);
      return done(owner, id, "to_review", lastDocument, landed,
          landed == 1 ? "A new report is ready to review." : landed + " new reports are ready to review.");
    }
    if (locked) {
      return done(owner, id, "needs_password", null, 0, password == null
          ? "The report is locked. Save its PDF password on this source, then check again."
          : "The saved password didn't open the report. Change it, then check again.");
    }
    return done(owner, id, "nothing_new", null, 0, threads.isEmpty() || !seen
        ? "No report mails from this sender since the last check." : "Nothing new: those reports are already here.");
  }

  record Filed(String documentId, boolean locked) {}

  /** Files one PDF as a document for the person and reads it into a draft. Null when refused. */
  private Filed file(UUID owner, UUID personId, MimePdfs.Pdf pdf, String ref, String password) {
    ObjectNode doc;
    try {
      doc = documents.upload(owner, personId, pdf.filename(), "application/pdf", pdf.bytes(), Json.obj(), "mail");
    } catch (ValidationException | NotFoundException refused) {
      // Not a PDF Vita can take (too big, or the record is full), or the person is gone.
      return null;
    }
    UUID docId = UUID.fromString(doc.path("id").asText());
    records.update("vita_document", owner, docId, Json.obj().put("sourceRef", ref));
    return read(owner, docId, password);
  }

  /** Reads a filed document into a draft. A document that can't be read now stays, to read later. */
  private Filed read(UUID owner, UUID docId, String password) {
    ObjectNode body = Json.obj();
    if (password != null) body.put("password", password);
    try {
      ObjectNode out = documents.extract(owner, docId, body, Instant.now());
      return new Filed(docId.toString(), out.path("needsPassword").asBoolean(false));
    } catch (RuntimeException notNow) {
      // The day's reading limit, say, or no AI model: the document waits in the vault with "Read" on it.
      return new Filed(docId.toString(), false);
    }
  }

  /** The document filed from this PDF, if it is still there, unconfirmed and waiting for a password. */
  private Optional<ObjectNode> lockedDocument(UUID owner, String ref) {
    return records.list("vita_document", owner, Map.of("sourceRef", ref)).stream()
        .filter(d -> d.path("deletedAt").isNull() && !d.hasNonNull("confirmedAt"))
        .filter(d -> VitaDocumentUseCase.view(d).path("needsPassword").asBoolean(false))
        .findFirst();
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
    if (n != null) collectRaw(n, out, 0);
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
      return Json.mapper().readTree(t);
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
        throw new ValidationException("Type the sender as an email address or a domain, such as reports@yourlab.com.");
      }
      out.add(s.toLowerCase(Locale.ROOT));
    }
    if (out.isEmpty() || out.size() > 5) throw new ValidationException("Type one to five senders.");
    return String.join(" ", out);
  }

  /** Words the report mail's subject has, or null. Quotes and brackets are dropped. */
  static String subject(String raw) {
    if (raw == null) return null;
    String s = raw.replaceAll("[\"(){}\\[\\]:]", " ").replaceAll("\\s+", " ").strip();
    if (s.isEmpty()) return null;
    return s.length() > 120 ? s.substring(0, 120) : s;
  }

  /** "mail:" and the SHA-256 of the PDF: the same attachment in any mail is the same report. */
  static String fingerprint(byte[] bytes) {
    try {
      return "mail:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Set<String> handled(ObjectNode row) {
    Set<String> out = new LinkedHashSet<>();
    for (JsonNode h : row.path("handled")) {
      String ref = h.path("ref").asText("");
      if (!ref.isBlank()) out.add(ref);
    }
    return out;
  }

  private static ArrayNode handledNode(Set<String> refs) {
    List<String> list = new ArrayList<>(refs);
    if (list.size() > HANDLED_MAX) list = list.subList(list.size() - HANDLED_MAX, list.size());
    ArrayNode out = Json.mapper().createArrayNode();
    list.forEach(r -> out.addObject().put("ref", r));
    return out;
  }

  private void savePassword(UUID owner, UUID id, String password) {
    if (password.length() > 100) throw new ValidationException("That password is too long.");
    records.writeSecret(TABLE, owner, id, "passwordEnc", crypto.encryptString(password));
  }

  private CheckResult signIn(UUID owner, UUID id, McpServerDto server) {
    return done(owner, id, "needs_sign_in", null, 0, "Sign in to " + server.name() + " on Connectors, then check again.");
  }

  private CheckResult failed(UUID owner, UUID id, McpServerDto server, String error) {
    String e = error == null ? "" : error.toLowerCase(Locale.ROOT);
    if (e.contains("401") || e.contains("unauthor") || e.contains("sign in") || e.contains("log in") || e.contains("token")) {
      return signIn(owner, id, server);
    }
    log.warn("Vita mail check failed for user {} source {}", owner, id);
    return done(owner, id, "failed", null, 0, server.name() + " didn't answer. Try again in a while.");
  }

  private CheckResult done(UUID owner, UUID id, String status, String documentId, int found, String message) {
    ObjectNode patch = Json.obj().put("lastStatus", status);
    // The next search starts from here only when this one got through.
    if (!status.equals("needs_sign_in") && !status.equals("failed")) patch.put("lastCheckedAt", OffsetDateTime.now().toString());
    if (documentId != null) patch.put("lastDocumentId", documentId);
    records.update(TABLE, owner, id, patch);
    return new CheckResult(status, documentId, found, message);
  }

  private void notifyReview(UUID owner) {
    UUID vita = agents.byKey(owner, VitaToolProvider.AGENT_KEY).map(x -> x.id()).orElse(null);
    if (vita == null) return;
    var detail = Json.obj().put("source", "vita").put("alert", "mail_review").put("view", "documents");
    bus.raise(NotificationKind.INFO, vita, new NotificationContext(owner,
        "A report from email is ready to review.", detail, null, null, null, null));
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  private PersonScope person(UUID caller, UUID owner, UUID personId) {
    PersonScope p = scopes.person(caller, personId);
    if (!owner.equals(p.owner())) throw new NotFoundException("Person", personId);
    return p.requireOpen();
  }

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

  private Source source(UUID caller, UUID owner, ObjectNode row, List<McpServerDto> known) {
    UUID id = UUID.fromString(row.path("id").asText());
    UUID serverId = UUID.fromString(row.path("serverId").asText());
    UUID personId = UUID.fromString(row.path("personId").asText());
    McpServerDto server = known.stream().filter(s -> s.id().equals(serverId)).findFirst().orElse(null);
    String personName;
    try {
      personName = scopes.person(caller, personId).person().displayName();
    } catch (RuntimeException gone) {
      personName = null;
    }
    boolean hasPassword = records.readSecret(TABLE, owner, id, "passwordEnc").isPresent();
    return new Source(id, serverId, server == null ? "Removed connector" : server.name(), personId, personName,
        row.path("sender").asText(), row.path("subject").asText(null), hasPassword, row.path("enabled").asBoolean(true),
        server != null && signedIn(server), row.path("lastStatus").asText(null), row.path("lastCheckedAt").asText(null),
        row.path("lastDocumentId").asText(null));
  }
}

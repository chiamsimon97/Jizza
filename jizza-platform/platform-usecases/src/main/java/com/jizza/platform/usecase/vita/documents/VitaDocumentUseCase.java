package com.jizza.platform.usecase.vita.documents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.chat.ChatFiles;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.StatementFiles;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.vita.config.VitaConfigService;
import com.jizza.platform.usecase.vita.medicine.VitaMedicineUseCase;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.records.VitaTable;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The documents vault and the extraction review (spec 5.3, 5.12): upload, list, correct, delete,
 * move, read into a draft, and confirm or discard the draft. A document is filed under the
 * managing account and one person; nothing read from it reaches a record table until the user
 * confirms the review, and a document whose patient does not match is confirmed only knowingly.
 */
@Service
public class VitaDocumentUseCase {

  static final String DOC = "vita_document";
  static final Set<String> TYPES = Set.of("application/pdf", "image/jpeg", "image/png", "image/gif", "image/webp");
  private static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;
  private static final long DEFAULT_MAX_IMAGE_BYTES = 5L * 1024 * 1024;

  private final VitaRecords records;
  private final VitaScopeResolver scopes;
  private final VitaRecordsUseCase forms;
  private final VitaConfigService config;
  private final VitaExtractionService extraction;
  private final VitaMedicineUseCase medicines;
  private final StatementFiles files;
  private final EncryptionService crypto;
  private final AuditLog audit;
  private final ChatFiles chatFiles;
  private final com.jizza.platform.usecase.vita.labs.VitaLabUseCase labs;

  public VitaDocumentUseCase(VitaRecords records, VitaScopeResolver scopes, VitaRecordsUseCase forms,
                             VitaConfigService config, VitaExtractionService extraction,
                             VitaMedicineUseCase medicines, StatementFiles files, EncryptionService crypto,
                             AuditLog audit, ChatFiles chatFiles,
                             com.jizza.platform.usecase.vita.labs.VitaLabUseCase labs) {
    this.labs = labs;
    this.records = records;
    this.scopes = scopes;
    this.forms = forms;
    this.config = config;
    this.extraction = extraction;
    this.medicines = medicines;
    this.files = files;
    this.crypto = crypto;
    this.audit = audit;
    this.chatFiles = chatFiles;
  }

  /** extraction.limits, with Vita's defaults for anything missing. */
  record Limits(int pagesPerDocument, int documentsPerDay, long maxBytes, long maxImageBytes) {}

  Limits limits(UUID caller) {
    JsonNode v = config.value(caller, "extraction.limits").orElse(Json.obj());
    return new Limits(v.path("pagesPerDocument").asInt(30), v.path("documentsPerDay").asInt(20),
        v.path("maxBytes").asLong(DEFAULT_MAX_BYTES), v.path("maxImageBytes").asLong(DEFAULT_MAX_IMAGE_BYTES));
  }

  // ── The vault ───────────────────────────────────────────────────────────────

  /**
   * Stores the file encrypted under the managing account and files a document for the person,
   * waiting to be read. Refuses what the model could not take, and says why.
   */
  @Transactional
  public ObjectNode upload(UUID caller, UUID personId, String filename, String contentType, byte[] bytes,
                           JsonNode meta, String source) {
    PersonScope p = scopes.person(caller, personId).requireOpen();
    Limits limits = limits(caller);
    String type = checkFile(contentType, bytes, limits);
    if (forms.live(p, VitaTable.DOCUMENT).size() >= VitaTable.DOCUMENT.maxPerPerson) {
      throw new ValidationException("A record can hold at most " + VitaTable.DOCUMENT.maxPerPerson + " documents.");
    }
    ObjectNode values = meta == null || meta.isNull() || meta.isEmpty() ? Json.obj()
        : VitaRecordsUseCase.clean(VitaTable.DOCUMENT.fields, meta, true);
    forms.checkRefs(p.owner(), VitaTable.DOCUMENT.fields, values);
    UUID fileId = files.store(p.owner(), filename == null ? "document" : filename, type, bytes);
    values.put("personId", p.personId().toString());
    values.put("source", VitaRecordsUseCase.SOURCES.contains(source) ? source : "upload");
    values.put("createdByUserId", p.caller().toString());
    values.put("fileId", fileId.toString());
    values.put("contentType", type);
    values.put("sizeBytes", bytes.length);
    values.put("extractionStatus", "pending");
    if (!values.hasNonNull("kind")) values.put("kind", "other");
    ObjectNode row = records.insert(DOC, p.owner(), values);
    audit.record(p.owner(), ActorType.USER, p.caller(), "vita.document.added", row.path("id").asText(), null);
    return view(row);
  }

  /**
   * Files a PDF or photo the user attached in a chat as a document for the person, with its own
   * encrypted copy, so removing one never removes the other.
   */
  @Transactional
  public ObjectNode fromChatFile(UUID caller, UUID personId, UUID fileId) {
    UUID owner = scopes.own(caller).ownerUserId();
    ChatFiles.Content content = chatFiles.content(owner, fileId)
        .orElseThrow(() -> new NotFoundException("Attachment", fileId));
    return upload(caller, personId, content.name(), content.contentType(), content.bytes(), Json.obj(), "chat");
  }

  /** The type Vita will store, after checking the bytes really are that type and fit the limits. */
  static String checkFile(String contentType, byte[] bytes, Limits limits) {
    if (bytes == null || bytes.length == 0) throw new ValidationException("The file is empty.");
    String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).strip();
    if (type.equals("image/jpg")) type = "image/jpeg";
    if (type.contains("heic") || type.contains("heif")) {
      throw new ValidationException("Vita reads PDFs and photos in JPEG, PNG, GIF or WebP. Convert a HEIC photo to JPEG first.");
    }
    String sniffed = sniff(bytes);
    if (!TYPES.contains(type) || sniffed == null || !sniffed.equals(type)) {
      if (sniffed != null && (type.isEmpty() || type.equals("application/octet-stream"))) {
        type = sniffed;
      } else {
        throw new ValidationException("Vita reads PDFs and photos in JPEG, PNG, GIF or WebP. This file is not one of them.");
      }
    }
    if (bytes.length > limits.maxBytes()) {
      throw new ValidationException("Files are limited to " + (limits.maxBytes() / (1024 * 1024)) + " MB.");
    }
    if (type.startsWith("image/") && bytes.length > limits.maxImageBytes()) {
      throw new ValidationException("Photos must be under " + (limits.maxImageBytes() / (1024 * 1024))
          + " MB for the AI model to read them. Take it again at a smaller size, or upload a PDF.");
    }
    return type;
  }

  /** The type the first bytes say, or null. */
  static String sniff(byte[] b) {
    if (b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return "application/pdf";
    if (b.length >= 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) return "image/jpeg";
    if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
    if (b.length >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "image/gif";
    if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
        && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
    return null;
  }

  /** The person's documents, newest first by their date, then by when they were added. */
  public List<ObjectNode> list(UUID caller, UUID personId) {
    PersonScope p = scopes.person(caller, personId);
    return forms.live(p, VitaTable.DOCUMENT).stream()
        .sorted(Comparator.comparing((ObjectNode r) -> r.path("documentDate").asText(""))
            .thenComparing(r -> r.path("createdAt").asText("")).reversed())
        .map(VitaDocumentUseCase::view).toList();
  }

  public ObjectNode get(UUID caller, UUID id) {
    return view(doc(caller, id).row());
  }

  /** The stored file, for the review's side-by-side view. */
  public byte[] file(UUID caller, UUID id) {
    Found f = doc(caller, id);
    UUID fileId = uuid(f.row().path("fileId").asText(null));
    if (fileId == null) throw new NotFoundException("Document file", id);
    return files.read(f.p().owner(), fileId).orElseThrow(() -> new NotFoundException("Document file", id));
  }

  /** The content type to serve the file with. */
  public String contentType(UUID caller, UUID id) {
    return doc(caller, id).row().path("contentType").asText("application/octet-stream");
  }

  /** Corrects the document's details: kind, title, date, doctor, condition, private, notes. */
  @Transactional
  public ObjectNode update(UUID caller, UUID id, JsonNode body) {
    Found f = doc(caller, id);
    f.p().requireOpen();
    ObjectNode values = VitaRecordsUseCase.clean(VitaTable.DOCUMENT.fields, body, false);
    forms.checkRefs(f.p().owner(), VitaTable.DOCUMENT.fields, values);
    ObjectNode row = values.isEmpty() ? f.row()
        : records.update(DOC, f.p().owner(), id, values).orElseThrow(() -> new NotFoundException("Document", id));
    audit.record(f.p().owner(), ActorType.USER, caller, "vita.document.updated", id.toString(), null);
    return view(row);
  }

  /**
   * Removes the document from every list and deletes the stored file, the text read from it and
   * the draft. What was already confirmed into the record stays there.
   */
  @Transactional
  public void delete(UUID caller, UUID id) {
    Found f = doc(caller, id);
    f.p().requireOpen();
    UUID fileId = uuid(f.row().path("fileId").asText(null));
    ObjectNode gone = Json.obj().put("deletedAt", Instant.now().toString());
    gone.putNull("fileId");
    gone.putNull("draft");
    records.update(DOC, f.p().owner(), id, gone);
    records.writeSecret(DOC, f.p().owner(), id, "extractedTextEnc", null);
    records.writeSecret(DOC, f.p().owner(), id, "passwordEnc", null);
    if (fileId != null) files.delete(f.p().owner(), fileId);
    audit.record(f.p().owner(), ActorType.USER, caller, "vita.document.removed", id.toString(), null);
  }

  /**
   * Files the document under another person of the same household. A draft is checked again
   * against the new person. Refused once its rows were confirmed into a record.
   */
  @Transactional
  public ObjectNode move(UUID caller, UUID id, JsonNode body) {
    Found f = doc(caller, id);
    f.p().requireOpen();
    UUID to = uuid(body == null ? null : body.path("toPersonId").asText(null));
    if (to == null) throw new ValidationException("Choose the person to move it to (toPersonId).");
    PersonScope target = scopes.person(caller, to).requireOpen();
    if (!target.owner().equals(f.p().owner())) throw new NotFoundException("Person", to);
    if (f.row().hasNonNull("confirmedAt")) {
      throw new ConflictException("Its rows are already saved in " + f.p().person().displayName()
          + "'s record. Remove them there, then upload the document for the right person.");
    }
    ObjectNode values = Json.obj().put("personId", to.toString());
    JsonNode draft = f.row().get("draft");
    if (draft != null && draft.isObject()) {
      ObjectNode d = ((ObjectNode) draft).deepCopy();
      JsonNode pt = d.path("patient");
      LocalDate on = date(d.path("documentDate").asText(null));
      VitaExtractionService.PersonFacts facts = facts(target);
      d.set("patientCheck", PatientCheck.check(text(pt, "name"), text(pt, "age"), text(pt, "sex"),
          facts.name(), facts.dob(), facts.sexAtBirth(), on == null ? today(target) : on));
      values.set("draft", d);
    }
    ObjectNode row = records.update(DOC, f.p().owner(), id, values).orElseThrow(() -> new NotFoundException("Document", id));
    audit.record(f.p().owner(), ActorType.USER, caller, "vita.document.moved", id.toString(), null);
    return view(row);
  }

  // ── Reading and the review ─────────────────────────────────────────────────

  /**
   * Reads the document into a draft. Saves only the draft on the document, never a record row.
   * A locked PDF asks for its password; a password that opens it is kept encrypted for next time.
   */
  public ObjectNode extract(UUID caller, UUID id, JsonNode body) {
    return extract(caller, id, body, Instant.now());
  }

  public ObjectNode extract(UUID caller, UUID id, JsonNode body, Instant now) {
    Found f = doc(caller, id);
    PersonScope p = f.p().requireOpen();
    if (f.row().hasNonNull("confirmedAt")) throw new ConflictException("This document's review is already confirmed.");
    Limits limits = limits(caller);
    LocalDate today = now.atZone(VitaRecordsUseCase.ZONE).toLocalDate();
    Instant dayStart = today.atStartOfDay(VitaRecordsUseCase.ZONE).toInstant();
    long readToday = records.list(DOC, p.owner(), Map.of()).stream()
        .filter(r -> r.hasNonNull("extractedAt") && !Instant.parse(r.path("extractedAt").asText()).isBefore(dayStart))
        .count();
    if (readToday >= limits.documentsPerDay()) {
      throw new ValidationException("Vita reads up to " + limits.documentsPerDay()
          + " documents a day. Try again tomorrow, or type the values in beside the document.");
    }
    UUID fileId = uuid(f.row().path("fileId").asText(null));
    byte[] bytes = fileId == null ? null : files.read(p.owner(), fileId).orElse(null);
    if (bytes == null) throw new NotFoundException("Document file", id);

    String given = body == null ? null : text(body, "password");
    String password = given != null ? given
        : records.readSecret(DOC, p.owner(), id, "passwordEnc").map(crypto::decryptString).orElse(null);
    VitaExtractionService.Result r = extraction.read(p.owner(), id, fileId, f.row().path("contentType").asText(null),
        bytes, password, limits.pagesPerDocument(), facts(p), shorthand(caller), today);

    ObjectNode values = Json.obj();
    if (r.draft() == null) {
      values.put("extractionStatus", "failed");
      values.put("extractionError", clip(r.error(), 300));
      values.putNull("draft");
      if (!r.needsPassword()) values.put("extractedAt", now.toString());
    } else {
      values.put("extractionStatus", "done");
      values.putNull("extractionError");
      values.set("draft", r.draft());
      values.put("extractedAt", now.toString());
      if ("other".equals(f.row().path("kind").asText()) && !"other".equals(r.draft().path("kind").asText())) {
        values.put("kind", r.draft().path("kind").asText());
      }
      if (!f.row().hasNonNull("documentDate") && r.draft().hasNonNull("documentDate")) {
        LocalDate d = date(r.draft().path("documentDate").asText());
        if (d != null && !d.isAfter(today)) values.put("documentDate", d.toString());
      }
    }
    if (r.pages() != null) values.put("pageCount", r.pages());
    ObjectNode row = records.update(DOC, p.owner(), id, values).orElseThrow(() -> new NotFoundException("Document", id));
    if (r.text() != null) records.writeSecret(DOC, p.owner(), id, "extractedTextEnc", crypto.encryptString(r.text()));
    if (given != null && !r.needsPassword()) {
      records.writeSecret(DOC, p.owner(), id, "passwordEnc", crypto.encryptString(given));
    }
    audit.record(p.owner(), ActorType.USER, caller, "vita.document.read", id.toString(),
        Json.obj().put("status", row.path("extractionStatus").asText()));
    ObjectNode out = Json.obj();
    out.set("document", view(row));
    out.set("draft", row.get("draft") == null || row.get("draft").isNull() ? null : row.get("draft"));
    if (r.needsPassword()) out.put("needsPassword", true);
    return out;
  }

  /** The draft waiting for review. */
  public ObjectNode draft(UUID caller, UUID id) {
    Found f = doc(caller, id);
    JsonNode draft = f.row().get("draft");
    if (draft == null || !draft.isObject()) throw new NotFoundException("Draft", id);
    ObjectNode out = Json.obj();
    out.set("document", view(f.row()));
    out.set("draft", draft);
    return out;
  }

  /** Throws the draft away; the document stays and can be read again. */
  @Transactional
  public ObjectNode discard(UUID caller, UUID id) {
    Found f = doc(caller, id);
    f.p().requireOpen();
    if (f.row().hasNonNull("confirmedAt")) throw new ConflictException("This document's review is already confirmed.");
    ObjectNode values = Json.obj().put("extractionStatus", "none");
    values.putNull("draft");
    values.putNull("extractionError");
    ObjectNode row = records.update(DOC, f.p().owner(), id, values).orElseThrow(() -> new NotFoundException("Document", id));
    audit.record(f.p().owner(), ActorType.USER, caller, "vita.document.draft_discarded", id.toString(), null);
    return view(row);
  }

  /**
   * Saves the reviewed draft: the document's date and kind, and for a prescription each medicine
   * the user kept, with its drafted schedule. The edits the user made in the review are what is
   * saved. A patient mismatch must be confirmed knowingly ({@code patientConfirmed}). A lab
   * report's kept rows become one lab report with its results.
   */
  @Transactional
  public ObjectNode confirm(UUID caller, UUID id, JsonNode body) {
    return confirm(caller, id, body, Instant.now());
  }

  @Transactional
  public ObjectNode confirm(UUID caller, UUID id, JsonNode body, Instant now) {
    Found f = doc(caller, id);
    PersonScope p = f.p().requireOpen();
    if (f.row().hasNonNull("confirmedAt")) throw new ConflictException("This document's review is already confirmed.");
    JsonNode stored = f.row().get("draft");
    if (stored == null || !stored.isObject()) throw new NotFoundException("Draft", id);
    JsonNode b = body == null || body.isNull() ? Json.obj() : body;
    ObjectNode draft = b.path("draft").isObject() ? ((ObjectNode) b.get("draft")).deepCopy() : ((ObjectNode) stored).deepCopy();
    // The patient check is Vita's, never the edit's.
    draft.set("patientCheck", stored.path("patientCheck"));
    if ("mismatch".equals(stored.path("patientCheck").path("status").asText())
        && !b.path("patientConfirmed").asBoolean(false)) {
      throw new ConflictException("The age or sex on this document does not match " + p.person().displayName()
          + "'s record. Check it is the right person, then confirm again, or move it to the right person.");
    }
    String kind = text(draft, "kind");
    if (kind == null || !VitaExtractionService.KINDS.contains(kind)) kind = f.row().path("kind").asText("other");
    LocalDate today = now.atZone(VitaRecordsUseCase.ZONE).toLocalDate();
    LocalDate date = date(text(draft, "documentDate"));
    if (text(draft, "documentDate") != null && date == null) throw new ValidationException("documentDate must be a date (yyyy-mm-dd).");
    if (date != null && date.isAfter(today)) throw new ValidationException("documentDate cannot be in the future.");

    List<String> saved = new ArrayList<>();
    String labReport = null;
    if (kind.equals("lab_report")) {
      UUID doctor = uuid(b.path("doctorId").asText(f.row().path("doctorId").asText(null)));
      labReport = labs.fromDocument(p, id, doctor, draft, date, now).map(r -> r.path("id").asText()).orElse(null);
    }
    if (kind.equals("prescription")) {
      UUID doctor = uuid(b.path("doctorId").asText(f.row().path("doctorId").asText(null)));
      List<ObjectNode> bodies = medicineBodies(draft.path("medicines"), doctor, date == null ? today : date);
      for (ObjectNode med : bodies) {
        ObjectNode made = medicines.create(p, med, "upload", now);
        UUID medId = UUID.fromString(made.path("id").asText());
        records.update("vita_medicine", p.owner(), medId, Json.obj().put("documentId", id.toString()));
        saved.add(medId.toString());
      }
    }

    ObjectNode values = Json.obj().put("kind", kind).put("confirmedAt", now.toString())
        .put("confirmedByUserId", caller.toString()).put("extractionStatus", "done");
    if (date != null) values.put("documentDate", date.toString());
    draft.put("confirmed", true);
    values.set("draft", draft);
    ObjectNode row = records.update(DOC, p.owner(), id, values).orElseThrow(() -> new NotFoundException("Document", id));
    audit.record(p.owner(), ActorType.USER, caller, "vita.document.confirmed", id.toString(),
        Json.obj().put("medicines", saved.size()).put("labReport", labReport != null));
    ObjectNode out = Json.obj();
    out.set("document", view(row));
    ObjectNode savedNode = out.putObject("saved");
    ArrayNode meds = savedNode.putArray("medicines");
    saved.forEach(meds::add);
    if (labReport != null) savedNode.put("labReport", labReport); else savedNode.putNull("labReport");
    return out;
  }

  /** The medicine forms for the rows the user kept; a row without a name is never saved. */
  static List<ObjectNode> medicineBodies(JsonNode rows, UUID doctor, LocalDate start) {
    List<ObjectNode> out = new ArrayList<>();
    int n = 0;
    for (JsonNode r : rows) {
      n++;
      if (r.path("skip").asBoolean(false)) continue;
      String name = text(r, "name");
      if (name == null) {
        throw new ValidationException("Type the name of medicine " + n + " from the prescription, or leave it out.");
      }
      ObjectNode m = Json.obj().put("name", name);
      if (text(r, "strength") != null) m.put("strength", text(r, "strength"));
      String form = text(r, "form");
      if (form != null && VitaExtractionService.FORMS.contains(form)) m.put("form", form);
      m.put("startDate", text(r, "startDate") != null ? text(r, "startDate") : start.toString());
      if (text(r, "endDate") != null) m.put("endDate", text(r, "endDate"));
      if (doctor != null) m.put("prescribedBy", doctor.toString());
      boolean asNeeded = r.path("asNeeded").asBoolean(false);
      if (asNeeded) {
        m.put("asNeeded", true);
      } else {
        JsonNode s = r.get("schedule");
        if (s == null || !s.isObject() || !s.path("times").isArray() || s.path("times").isEmpty()) {
          throw new ValidationException("Set the times for medicine " + n + " (" + name + "), mark it as needed, or leave it out.");
        }
        m.set("schedule", s);
      }
      out.add(m);
    }
    return out;
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  record Found(PersonScope p, ObjectNode row) {}

  /** One of the caller's household documents; anyone else's reads as not found. */
  Found doc(UUID caller, UUID id) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode row = records.get(DOC, owner, id).filter(r -> r.path("deletedAt").isNull())
        .orElseThrow(() -> new NotFoundException("Document", id));
    PersonScope p = scopes.person(caller, UUID.fromString(row.path("personId").asText()));
    return new Found(p, row);
  }

  private VitaExtractionService.PersonFacts facts(PersonScope p) {
    String sex = records.list("vita_profile", p.owner(), Map.of("personId", p.personId())).stream()
        .map(r -> r.path("sexAtBirth").asText(null)).filter(s -> s != null).findFirst().orElse(null);
    return new VitaExtractionService.PersonFacts(p.person().displayName(), p.person().dob(), sex);
  }

  private RxShorthand shorthand(UUID caller) {
    JsonNode codes = config.value(caller, "clinical.rx_shorthand").orElse(null);
    JsonNode times = config.value(caller, "reminder.slot_times").orElse(Json.obj());
    Map<String, String> slots = new LinkedHashMap<>();
    times.fields().forEachRemaining(e -> {
      if (e.getValue().isTextual() && e.getValue().asText().matches("([01]\\d|2[0-3]):[0-5]\\d")) {
        slots.put(e.getKey(), e.getValue().asText());
      }
    });
    return new RxShorthand(codes, slots);
  }

  private static LocalDate today(PersonScope p) {
    return LocalDate.now(VitaRecordsUseCase.ZONE);
  }

  /** The row as the screens see it: the draft is fetched on its own, secrets never. */
  static ObjectNode view(ObjectNode row) {
    ObjectNode v = row.deepCopy();
    JsonNode draft = v.remove("draft");
    boolean has = draft != null && draft.isObject();
    v.put("hasDraft", has && !row.hasNonNull("confirmedAt"));
    v.put("confirmed", row.hasNonNull("confirmedAt"));
    if (has) {
      v.put("draftRows", draft.path("rowCount").asInt(0));
      v.put("patientCheck", draft.path("patientCheck").path("status").asText("unknown"));
    }
    String err = row.path("extractionError").asText("");
    v.put("needsPassword", err.contains("password") || err.contains("locked"));
    return v;
  }

  private static LocalDate date(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return LocalDate.parse(s);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static UUID uuid(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n == null ? null : n.get(field);
    if (v == null || v.isNull() || !v.isValueNode()) return null;
    String s = v.asText();
    return s.isBlank() ? null : s.strip();
  }

  private static String clip(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}

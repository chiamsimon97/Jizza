package com.jizza.platform.usecase.vita.labs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.vita.config.VitaConfigService;
import com.jizza.platform.usecase.vita.documents.PrintedNumber;
import com.jizza.platform.usecase.vita.documents.PrintedRange;
import com.jizza.platform.usecase.vita.documents.VitaExtractionService;
import com.jizza.platform.usecase.vita.ranges.RangeSet;
import com.jizza.platform.usecase.vita.ranges.TargetEvaluator;
import com.jizza.platform.usecase.vita.ranges.VitaTargetService;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lab reports and their results (spec 4.2, 5.4, 5.5): a confirmed lab-report document becomes one
 * report; each printed row keeps its value, unit and range as printed, and the normalised figures
 * beside them. Every view shows two results per value, never merged: the lab flag (what the lab's
 * printed range says) and the target status (spec 2.4). Vita never assigns "critical": only a lab
 * that printed it does.
 */
@Service
public class VitaLabUseCase {

  static final String REPORT = "vita_lab_report";
  static final String RESULT = "vita_lab_result";
  static final Set<String> FLAGS = Set.of("low", "normal", "high", "critical", "unknown");
  /** A product limit on rows per report, so one upload cannot flood the record. */
  static final int MAX_ROWS = 300;

  private final VitaRecords records;
  private final VitaScopeResolver scopes;
  private final VitaRecordsUseCase forms;
  private final VitaTargetService targets;
  private final VitaLabCatalog catalog;
  private final VitaConfigService config;
  private final AuditLog audit;

  public VitaLabUseCase(VitaRecords records, VitaScopeResolver scopes, VitaRecordsUseCase forms,
                        VitaTargetService targets, VitaLabCatalog catalog, VitaConfigService config, AuditLog audit) {
    this.records = records;
    this.scopes = scopes;
    this.forms = forms;
    this.targets = targets;
    this.catalog = catalog;
    this.config = config;
    this.audit = audit;
  }

  // ── From a confirmed document ───────────────────────────────────────────────

  /**
   * Files the rows of a confirmed lab-report draft as one report. Rows marked {@code skip} or
   * without a name are left out. Returns the report, or empty when no row was kept.
   */
  @Transactional
  public Optional<ObjectNode> fromDocument(PersonScope p, UUID documentId, UUID doctorId, JsonNode draft,
                                           LocalDate collectedOn, Instant now) {
    List<JsonNode> rows = new ArrayList<>();
    for (JsonNode t : draft.path("tests")) {
      if (t.path("skip").asBoolean(false) || text(t, "name") == null) continue;
      rows.add(t);
    }
    if (rows.isEmpty()) return Optional.empty();
    if (rows.size() > MAX_ROWS) throw new ValidationException("A report can hold at most " + MAX_ROWS + " rows.");
    ObjectNode report = Json.obj().put("personId", p.personId().toString()).put("source", "upload")
        .put("createdByUserId", p.caller().toString()).put("status", "confirmed")
        .put("documentId", documentId.toString());
    String lab = text(draft, "labName");
    if (lab != null) report.put("labName", cut(lab, 200));
    if (collectedOn != null) report.put("collectedOn", collectedOn.toString());
    if (doctorId != null) report.put("doctorId", doctorId.toString());
    ObjectNode saved = records.insert(REPORT, p.owner(), report);
    UUID reportId = UUID.fromString(saved.path("id").asText());
    LabNormaliser n = catalog.normaliser();
    int i = 0;
    for (JsonNode row : rows) {
      ObjectNode r = resultValues(row, n);
      r.put("reportId", reportId.toString()).put("personId", p.personId().toString()).put("source", "upload")
          .put("createdByUserId", p.caller().toString()).put("sortOrder", i++);
      records.insert(RESULT, p.owner(), r);
    }
    audit.record(p.owner(), ActorType.USER, p.caller(), "vita.lab.report.saved", reportId.toString(),
        Json.obj().put("rows", rows.size()));
    return Optional.of(saved);
  }

  /**
   * The stored columns of one result, from a reviewed draft row. The lab flag is worked out again
   * here from what was printed, so an edit cannot turn a row "critical".
   */
  static ObjectNode resultValues(JsonNode row, LabNormaliser n) {
    ObjectNode r = Json.obj();
    String name = text(row, "name");
    if (name == null) throw new ValidationException("Each row needs the test name as printed.");
    r.put("testNameAsPrinted", cut(name, 200));
    String section = text(row, "section");
    if (section != null) r.put("section", cut(section, 200));
    String printed = text(row, "valueAsPrinted");
    if (printed != null) r.put("valueAsPrinted", cut(printed, 60));
    BigDecimal value = row.path("value").isNumber() ? row.get("value").decimalValue() : null;
    if (value == null && printed != null) {
      PrintedNumber.Parsed parsed = PrintedNumber.parse(printed);
      value = parsed == null ? null : parsed.value();
    }
    if (value != null) r.put("valueNum", value); else r.put("valueText", cut(printed == null ? text(row, "valueText") : printed, 200));
    String unit = text(row, "unit");
    if (unit != null) r.put("unit", cut(unit, 40));

    String rangePrinted = text(row, "rangeAsPrinted");
    PrintedRange.Range range = PrintedRange.parse(rangePrinted);
    BigDecimal low = range == null ? null : range.low();
    BigDecimal high = range == null ? null : range.high();
    boolean lowInc = range == null || range.lowInclusive();
    boolean highInc = range == null || range.highInclusive();
    if (rangePrinted == null && (row.path("refLow").isNumber() || row.path("refHigh").isNumber())) {
      low = row.path("refLow").isNumber() ? row.get("refLow").decimalValue() : null;
      high = row.path("refHigh").isNumber() ? row.get("refHigh").decimalValue() : null;
      lowInc = row.path("refLowInclusive").asBoolean(true);
      highInc = row.path("refHighInclusive").asBoolean(true);
    }
    if (low != null) r.put("refLow", low);
    if (high != null) r.put("refHigh", high);
    r.put("refLowInclusive", lowInc).put("refHighInclusive", highInc);
    // The range as printed, kept whole: bands and all.
    if (rangePrinted != null) r.put("refText", cut(rangePrinted, 500));

    String flagPrinted = text(row, "flagAsPrinted");
    if (flagPrinted != null) r.put("flagAsPrinted", cut(flagPrinted, 20));
    String fromLab = VitaExtractionService.flag(flagPrinted);
    String flag = fromLab != null ? fromLab
        : (low != null || high != null) ? new PrintedRange.Range(low, high, lowInc, highInc, null, false).flagFor(value)
        : "unknown";
    r.put("flag", flag);
    if (row.path("confidence").isNumber()) {
      BigDecimal c = row.get("confidence").decimalValue();
      if (c.signum() >= 0 && c.compareTo(BigDecimal.ONE) <= 0) r.put("confidence", c.setScale(2, RoundingMode.HALF_UP));
    }

    String code = text(row, "testCode");
    LabNormaliser.Normalised norm;
    if (code != null) {
      if (n.byCode(code).isEmpty()) throw new ValidationException("testCode is not a known lab test.");
      norm = n.normalise(n.byCode(code).get().name(), unit, value, low, high);
    } else {
      norm = n.normalise(name, unit, value, low, high);
    }
    put(r, norm);
    r.put("confirmed", true);
    return r;
  }

  private static void put(ObjectNode r, LabNormaliser.Normalised norm) {
    if (norm.code() != null) r.put("testCode", norm.code()); else r.putNull("testCode");
    if (norm.unitCanonical() != null) r.put("unitCanonical", norm.unitCanonical()); else r.putNull("unitCanonical");
    putOrNull(r, "valueCanonical", norm.valueCanonical());
    putOrNull(r, "refLowCanonical", norm.refLowCanonical());
    putOrNull(r, "refHighCanonical", norm.refHighCanonical());
    if (norm.conversion() != null) {
      LabNormaliser.Conversion c = norm.conversion();
      ObjectNode cj = Json.obj().put("from", c.from()).put("multiply", c.multiply()).put("source", c.source())
          .put("needsVerification", c.needsVerification());
      if (c.add() != null) cj.put("add", c.add());
      r.set("conversion", cj);
    } else {
      r.putNull("conversion");
    }
  }

  private static void putOrNull(ObjectNode r, String f, BigDecimal v) {
    if (v == null) r.putNull(f); else r.put(f, v);
  }

  // ── Reports ─────────────────────────────────────────────────────────────────

  /** The person's live reports, newest first, with how many rows the lab flagged. */
  public List<ObjectNode> list(UUID caller, UUID personId) {
    PersonScope p = scopes.person(caller, personId);
    List<ObjectNode> out = new ArrayList<>();
    for (ObjectNode r : reports(p)) {
      List<ObjectNode> rows = results(p, UUID.fromString(r.path("id").asText()));
      ObjectNode v = r.deepCopy();
      v.put("date", date(r) == null ? null : date(r).toString());
      v.put("rows", rows.size());
      v.put("labFlagged", rows.stream().filter(x -> Set.of("low", "high", "critical").contains(x.path("flag").asText())).count());
      out.add(v);
    }
    out.sort(Comparator.comparing((ObjectNode v) -> v.path("date").asText("")).reversed());
    return out;
  }

  /** One report with every result and its two judgements. */
  public ObjectNode get(UUID caller, UUID reportId) {
    Found f = report(caller, reportId);
    return view(f.p(), f.row());
  }

  ObjectNode view(PersonScope p, ObjectNode report) {
    VitaTargetService.Context ctx = targets.context(p);
    LabNormaliser n = catalog.normaliser();
    Formulas formulas = formulas(p);
    ObjectNode out = Json.obj();
    out.set("report", report);
    out.put("date", date(report) == null ? null : date(report).toString());
    ArrayNode rows = out.putArray("results");
    for (ObjectNode r : results(p, UUID.fromString(report.path("id").asText()))) rows.add(resultView(r, ctx, n, formulas));
    return out;
  }

  /**
   * Corrects a report: its lab, dates and doctor, and any of its rows ({@code results}: each with
   * its {@code id} and the fields as printed; {@code remove: true} leaves a row out). A corrected
   * row is normalised again.
   */
  @Transactional
  public ObjectNode update(UUID caller, UUID reportId, JsonNode body) {
    Found f = report(caller, reportId);
    PersonScope p = f.p().requireOpen();
    if (body == null || !body.isObject()) throw new ValidationException("Send the changes as an object.");
    Set<String> allowed = Set.of("labName", "collectedOn", "reportedOn", "doctorId", "notes", "results", "version");
    body.fieldNames().forEachRemaining(k -> {
      if (!allowed.contains(k)) throw new ValidationException(k + " cannot be changed here.");
    });
    if (body.hasNonNull("version") && body.get("version").asLong() != f.row().path("version").asLong()) {
      throw new com.jizza.engine.api.error.ConflictException("This report changed since you opened it. Reload and try again.");
    }
    ObjectNode values = Json.obj();
    if (body.has("labName")) {
      String lab = text(body, "labName");
      if (lab != null && lab.length() > 200) throw new ValidationException("labName is too long (200 at most).");
      if (lab == null) values.putNull("labName"); else values.put("labName", lab);
    }
    for (String d : List.of("collectedOn", "reportedOn")) {
      if (!body.has(d)) continue;
      if (body.get(d).isNull()) values.putNull(d);
      else values.put(d, VitaRecordsUseCase.pastDate(d, body.get(d)).toString());
    }
    if (body.has("doctorId")) {
      if (body.get("doctorId").isNull()) {
        values.putNull("doctorId");
      } else {
        values.put("doctorId", body.get("doctorId").asText());
        forms.checkRefs(p.owner(), Map.of("doctorId", com.jizza.platform.usecase.vita.records.VitaField.ref("doctorId", "vita_doctor")), values);
      }
    }
    if (body.has("notes")) {
      String notes = text(body, "notes");
      if (notes != null && notes.length() > 1000) throw new ValidationException("notes is too long (1000 at most).");
      if (notes == null) values.putNull("notes"); else values.put("notes", notes);
    }
    int changed = 0;
    if (body.has("results")) {
      if (!body.get("results").isArray()) throw new ValidationException("results must be a list.");
      Map<String, ObjectNode> mine = new LinkedHashMap<>();
      results(p, reportId).forEach(r -> mine.put(r.path("id").asText(), r));
      LabNormaliser n = catalog.normaliser();
      for (JsonNode change : body.get("results")) {
        String id = change.path("id").asText("");
        ObjectNode current = mine.get(id);
        if (current == null) throw new ValidationException("A result in the list is not part of this report.");
        UUID rid = UUID.fromString(id);
        if (change.path("remove").asBoolean(false)) {
          forms.removeRow(p, RESULT, "Lab result", rid);
          changed++;
          continue;
        }
        ObjectNode merged = Json.obj();
        merged.put("name", change.has("testNameAsPrinted") ? text(change, "testNameAsPrinted") : current.path("testNameAsPrinted").asText(null));
        merged.put("section", change.has("section") ? text(change, "section") : current.path("section").asText(null));
        String valuePrinted = change.has("valueAsPrinted") ? text(change, "valueAsPrinted")
            : current.hasNonNull("valueAsPrinted") ? current.get("valueAsPrinted").asText() : null;
        merged.put("valueAsPrinted", valuePrinted);
        if (!change.has("valueAsPrinted") && current.hasNonNull("valueNum")) merged.put("value", current.get("valueNum").decimalValue());
        merged.put("unit", change.has("unit") ? text(change, "unit") : current.path("unit").asText(null));
        String rangePrinted = change.has("rangeAsPrinted") ? text(change, "rangeAsPrinted") : current.path("refText").asText(null);
        merged.put("rangeAsPrinted", rangePrinted);
        if (rangePrinted == null) {
          if (current.hasNonNull("refLow")) merged.put("refLow", current.get("refLow").decimalValue());
          if (current.hasNonNull("refHigh")) merged.put("refHigh", current.get("refHigh").decimalValue());
          merged.put("refLowInclusive", current.path("refLowInclusive").asBoolean(true));
          merged.put("refHighInclusive", current.path("refHighInclusive").asBoolean(true));
        }
        merged.put("flagAsPrinted", change.has("flagAsPrinted") ? text(change, "flagAsPrinted") : current.path("flagAsPrinted").asText(null));
        if (change.has("testCode")) {
          if (text(change, "testCode") != null) merged.put("testCode", text(change, "testCode"));
        } else if (current.hasNonNull("testCode")) {
          merged.put("testCode", current.get("testCode").asText());
        }
        ObjectNode v = resultValues(merged, n);
        for (String f2 : List.of("valueNum", "valueText", "refLow", "refHigh", "refText", "flagAsPrinted", "section",
            "unit", "valueAsPrinted")) {
          if (!v.has(f2)) v.putNull(f2);
        }
        v.put("confidence", BigDecimal.ONE.setScale(2, RoundingMode.UNNECESSARY));
        records.update(RESULT, p.owner(), rid, v);
        changed++;
      }
    }
    values.put("version", f.row().path("version").asLong() + 1);
    records.update(REPORT, p.owner(), reportId, values);
    audit.record(p.owner(), ActorType.USER, caller, "vita.lab.report.corrected", reportId.toString(),
        Json.obj().put("rows", changed));
    return view(p, records.get(REPORT, p.owner(), reportId).orElseThrow());
  }

  /** Removes a report and its rows (soft delete). The document it came from stays. */
  @Transactional
  public void delete(UUID caller, UUID reportId) {
    Found f = report(caller, reportId);
    PersonScope p = f.p().requireOpen();
    for (ObjectNode r : results(p, reportId)) forms.removeRow(p, RESULT, "Lab result", UUID.fromString(r.path("id").asText()));
    forms.removeRow(p, REPORT, "Lab report", reportId);
    audit.record(p.owner(), ActorType.USER, caller, "vita.lab.report.removed", reportId.toString(), null);
  }

  // ── Trend of one test ───────────────────────────────────────────────────────

  /**
   * One test across the person's confirmed reports (spec 5.5): each value with its date, lab flag
   * and target status; the change since the previous report and since the first, unrounded and
   * shown; and for HbA1c the secondary lines (mmol/mol, estimated average glucose) where their
   * formulas carry a source. A value in a unit that could not be converted is listed but left out
   * of the changes.
   */
  public ObjectNode trend(UUID caller, UUID personId, String testCode) {
    PersonScope p = scopes.person(caller, personId);
    LabNormaliser n = catalog.normaliser();
    LabNormaliser.Test test = n.byCode(testCode).orElseThrow(() -> new ValidationException("test is not a known lab test."));
    return trend(p, targets.context(p), test, formulas(p));
  }

  ObjectNode trend(PersonScope p, VitaTargetService.Context ctx, LabNormaliser.Test test, Formulas formulas) {
    LabNormaliser n = catalog.normaliser();
    record Pt(LocalDate date, ObjectNode report, ObjectNode result) {}
    List<Pt> pts = new ArrayList<>();
    for (ObjectNode rep : reports(p)) {
      if (!"confirmed".equals(rep.path("status").asText())) continue;
      LocalDate d = date(rep);
      if (d == null) continue;
      for (ObjectNode r : results(p, UUID.fromString(rep.path("id").asText()))) {
        if (test.code().equals(r.path("testCode").asText(null))) pts.add(new Pt(d, rep, r));
      }
    }
    pts.sort(Comparator.comparing(Pt::date));
    ObjectNode out = Json.obj();
    out.set("test", testNode(test));
    RangeSet range = ctx.labRangeFor(test.code());
    out.set("target", range == null
        ? Json.obj().put("status", TargetEvaluator.NOT_SET).put("label", TargetEvaluator.NOT_SET_LABEL)
        : Json.obj().put("status", "set").put("range", range.text()).put("source", range.source())
            .put("label", range.label()).put("needsVerification", range.needsVerification()));
    ArrayNode series = out.putArray("points");
    BigDecimal first = null;
    BigDecimal previous = null;
    BigDecimal last = null;
    for (Pt pt : pts) {
      ObjectNode v = resultView(pt.result(), ctx, n, formulas);
      v.put("date", pt.date().toString());
      v.put("reportId", pt.report().path("id").asText());
      if (pt.report().hasNonNull("labName")) v.put("labName", pt.report().get("labName").asText());
      BigDecimal value = pt.result().hasNonNull("valueCanonical") ? pt.result().get("valueCanonical").decimalValue() : null;
      v.put("comparable", value != null);
      if (value != null) {
        if (previous != null) v.set("changeSincePrevious", change(previous, value, test.decimals()));
        if (first == null) first = value;
        previous = value;
        last = value;
      }
      series.add(v);
    }
    out.put("count", pts.size());
    if (first != null && last != null && series.size() > 1) out.set("changeSinceFirst", change(first, last, test.decimals()));
    if (previous != null && series.size() > 1) {
      JsonNode lastPoint = series.get(series.size() - 1);
      if (lastPoint.has("changeSincePrevious")) out.set("changeSincePrevious", lastPoint.get("changeSincePrevious"));
    }
    return out;
  }

  /** The difference to - from: unrounded, shown half up, and its direction. */
  static ObjectNode change(BigDecimal from, BigDecimal to, int decimals) {
    BigDecimal d = to.subtract(from);
    return Json.obj().put("by", d.stripTrailingZeros().scale() < 0 ? d.setScale(0) : d.stripTrailingZeros())
        .put("shown", LabNormaliser.shown(d, decimals)).put("direction", d.signum() > 0 ? "up" : d.signum() < 0 ? "down" : "same");
  }

  // ── One result, as shown ────────────────────────────────────────────────────

  /** The secondary formulas from config, each with whether it needs verification. */
  record Formulas(JsonNode eag, boolean eagNeedsVerification) {}

  Formulas formulas(PersonScope p) {
    for (var item : config.list(p.owner())) {
      if (item.key().equals("clinical.formulas.eag") && item.value() != null && item.sourceUrl() != null) {
        return new Formulas(item.value(), item.needsVerification());
      }
    }
    return new Formulas(null, false);
  }

  /**
   * A stored result with: the test it is, the figures as shown (canonical where there is one,
   * otherwise as printed), the lab flag in words, and the target status from the evaluator. The
   * two are separate fields and never merged.
   */
  static ObjectNode resultView(ObjectNode r, VitaTargetService.Context ctx, LabNormaliser n, Formulas formulas) {
    ObjectNode v = r.deepCopy();
    String code = r.path("testCode").asText(null);
    LabNormaliser.Test test = code == null ? null : n.byCode(code).orElse(null);
    v.set("test", test == null ? null : testNode(test));
    int decimals = test == null ? 2 : test.decimals();
    BigDecimal canonical = r.hasNonNull("valueCanonical") ? r.get("valueCanonical").decimalValue() : null;
    ObjectNode shown = v.putObject("shown");
    if (canonical != null) {
      shown.put("value", LabNormaliser.shown(canonical, decimals).toPlainString()).put("unit", r.path("unitCanonical").asText());
      String range = LabNormaliser.rangeShown(dec(r, "refLowCanonical"), r.path("refLowInclusive").asBoolean(true),
          dec(r, "refHighCanonical"), r.path("refHighInclusive").asBoolean(true), decimals);
      boolean bands = r.hasNonNull("refText") && PrintedRange.parse(r.get("refText").asText()) != null
          && !PrintedRange.parse(r.get("refText").asText()).bounded();
      shown.put("range", bands ? r.get("refText").asText() : range);
    } else {
      shown.put("value", r.hasNonNull("valueAsPrinted") ? r.get("valueAsPrinted").asText()
          : r.hasNonNull("valueNum") ? r.get("valueNum").asText() : r.path("valueText").asText(null));
      shown.put("unit", r.path("unit").asText(null));
      shown.put("range", r.path("refText").asText(null));
    }
    boolean converted = r.path("conversion").isObject();
    shown.put("converted", converted);
    if (converted) {
      shown.put("asPrinted", r.path("valueAsPrinted").asText("") + (r.hasNonNull("unit") ? " " + r.get("unit").asText() : ""));
      shown.put("needsVerification", r.path("conversion").path("needsVerification").asBoolean(true));
    }

    // The lab flag: the lab's own judgement, from its printed flag or printed range.
    String flag = r.path("flag").asText("unknown");
    ObjectNode lab = v.putObject("labFlag");
    lab.put("flag", flag);
    String rangeText = shown.path("range").asText(null);
    lab.put("label", "Lab: " + (flag.equals("unknown") ? "no flag" : flag) + (rangeText == null ? "" : " (" + rangeText + ")"));

    // The target status (spec 2.4): only for a known test with a value in its canonical unit.
    ObjectNode target = v.putObject("target");
    RangeSet range = test == null ? null : ctx.labRangeFor(test.code());
    if (canonical == null || range == null) {
      target.put("status", TargetEvaluator.NOT_SET).put("label", TargetEvaluator.NOT_SET_LABEL);
    } else {
      var e = TargetEvaluator.evaluate(new TargetEvaluator.Reading("lab", canonical, null, null, null), range, List.of(), null);
      target.put("status", e.status()).put("range", e.range()).put("source", e.rangeSource())
          .put("label", e.label()).put("needsVerification", e.needsVerification());
      if (e.doctorName() != null) target.put("doctorName", e.doctorName());
      if (e.setOn() != null) target.put("setOn", e.setOn().toString());
      ArrayNode outside = target.putArray("outside");
      e.outside().forEach(outside::add);
    }

    // HbA1c's secondary lines: never instead of the measured value.
    if (test != null && "hba1c".equals(test.code()) && canonical != null && "%".equals(r.path("unitCanonical").asText())) {
      ArrayNode secondary = v.putArray("secondary");
      for (LabNormaliser.Conversion c : test.conversions()) {
        BigDecimal back = c.invert(canonical);
        int d = c.decimals() == null ? decimals : c.decimals();
        secondary.addObject().put("what", "HbA1c in " + c.from()).put("value", back)
            .put("shown", LabNormaliser.shown(back, d).toPlainString()).put("unit", c.from())
            .put("needsVerification", c.needsVerification()).put("source", c.source());
      }
      if (formulas != null && formulas.eag() != null && formulas.eag().hasNonNull("multiply")) {
        JsonNode f = formulas.eag();
        BigDecimal eag = canonical.multiply(f.get("multiply").decimalValue())
            .add(f.hasNonNull("add") ? f.get("add").decimalValue() : BigDecimal.ZERO);
        secondary.addObject().put("what", "Estimated average glucose").put("value", eag)
            .put("shown", LabNormaliser.shown(eag, f.path("decimals").asInt(0)).toPlainString())
            .put("unit", f.path("unit").asText("mg/dL")).put("needsVerification", formulas.eagNeedsVerification());
      }
    }
    return v;
  }

  static ObjectNode testNode(LabNormaliser.Test t) {
    return Json.obj().put("code", t.code()).put("name", t.name()).put("group", t.group())
        .put("unit", t.canonicalUnit()).put("decimals", t.decimals());
  }

  // ── Reading the store ───────────────────────────────────────────────────────

  record Found(PersonScope p, ObjectNode row) {}

  Found report(UUID caller, UUID id) {
    UUID owner = scopes.own(caller).ownerUserId();
    ObjectNode row = records.get(REPORT, owner, id).filter(r -> r.path("deletedAt").isNull())
        .orElseThrow(() -> new NotFoundException("Lab report", id));
    return new Found(scopes.person(caller, UUID.fromString(row.path("personId").asText())), row);
  }

  public List<ObjectNode> reports(PersonScope p) {
    return records.list(REPORT, p.owner(), VitaRecordsUseCase.livePerson(p.personId()));
  }

  public List<ObjectNode> results(PersonScope p, UUID reportId) {
    Map<String, Object> where = new LinkedHashMap<>();
    where.put("reportId", reportId);
    where.put("deletedAt", null);
    List<ObjectNode> rows = new ArrayList<>(records.list(RESULT, p.owner(), where));
    rows.sort(Comparator.comparingInt(r -> r.path("sortOrder").asInt()));
    return rows;
  }

  /** The date a report counts from: collected, else reported, else the day it was filed. */
  public static LocalDate date(ObjectNode report) {
    for (String f : List.of("collectedOn", "reportedOn")) {
      if (report.hasNonNull(f)) return LocalDate.parse(report.get(f).asText());
    }
    if (report.hasNonNull("createdAt")) {
      return Instant.parse(normaliseInstant(report.get("createdAt").asText())).atZone(VitaRecordsUseCase.ZONE).toLocalDate();
    }
    return null;
  }

  private static String normaliseInstant(String s) {
    try {
      return java.time.OffsetDateTime.parse(s).toInstant().toString();
    } catch (RuntimeException e) {
      return s;
    }
  }

  static BigDecimal dec(JsonNode r, String f) {
    return r.hasNonNull(f) ? r.get(f).decimalValue() : null;
  }

  static String text(JsonNode n, String f) {
    return n.hasNonNull(f) && !n.get(f).asText().isBlank() ? n.get(f).asText().strip() : null;
  }

  private static String cut(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}

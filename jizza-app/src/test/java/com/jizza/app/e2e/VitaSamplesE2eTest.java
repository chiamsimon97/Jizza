package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.util.Json;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The extraction pipeline over the sample set (plan-02 step 3, {@code samples/expected.md}). The
 * two prescriptions are the fictional PDFs themselves. The public lab reports could not be
 * downloaded here, so each is rendered as a fictional PDF from the rows {@code expected.md} lists,
 * with no name on it. The scripted model is a plain line copier over the text layer the server
 * sends it, so the test measures what Vita's own code does with a faithful copy: every printed row
 * reaches the review, and each value, unit, range and flag comes out as {@code expected.md} says.
 * How well a real model copies needs the live evals (an API key).
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class VitaSamplesE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private E2eSupport.ScriptedLlmClient model;
  private ApiClient api;
  private String person;

  /** One row of an expected.md table. */
  record Row(String name, String value, String unit, String range, String flag) {}

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    model.reset();
    model.fallback = VitaSamplesE2eTest::copy;
    String email = "vita-samples-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    assertThat(api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    var made = api.post("/api/people", Map.of("displayName", "Sample person", "kind", "parent", "type", "individual"));
    assertThat(made.ok()).as(made.body()).isTrue();
    person = made.json().path("id").asText();
  }

  // ── expected.md ───────────────────────────────────────────────────────────

  /** The tables of expected.md by their heading's sample id (lab-01, rx-01, ...). */
  static Map<String, List<List<String>>> tables() throws Exception {
    String md = new String(VitaDocumentsE2eTest.resource("expected.md"), StandardCharsets.UTF_8);
    Map<String, List<List<String>>> out = new LinkedHashMap<>();
    String current = null;
    for (String line : md.split("\n")) {
      Matcher h = Pattern.compile("^## ((?:lab|rx)-\\d\\d)").matcher(line);
      if (h.find()) {
        current = h.group(1);
        out.put(current, new ArrayList<>());
        continue;
      }
      if (current == null || !line.startsWith("|") || line.startsWith("|---") || line.contains("| Value |")
          || line.contains("| Medicine as printed |") || line.contains("Test as printed")) continue;
      List<String> cells = new ArrayList<>();
      for (String c : line.substring(1, line.length() - 1).split("\\|", -1)) cells.add(c.strip());
      out.get(current).add(cells);
    }
    return out;
  }

  static List<Row> labRows(List<List<String>> table) {
    List<Row> rows = new ArrayList<>();
    for (List<String> c : table) {
      // lab-02 has a Section column first; lab-04 has no flag column.
      List<String> r = c.size() == 6 ? c.subList(1, 6) : c;
      String range = r.get(3).startsWith("(") ? null : r.get(3);
      rows.add(new Row(r.get(0), r.get(1), r.get(2), range, r.size() > 4 ? r.get(4) : "none"));
    }
    return rows;
  }

  // ── The stand-in model: copies the rows of the text it was sent ───────────

  private static final Pattern LAB_LINE = Pattern.compile("^(.+?)\\s+([0-9][0-9,]*(?:\\.[0-9]+)?)\\s+(\\S+)(?:\\s+(.+))?$");
  private static final Pattern RX_LINE = Pattern.compile(
      "^\\s*\\d+\\.\\s*((?:Tab|Cap|Syp|Inj)\\.\\s*.+?)\\s+(\\d[\\d,]*(?:\\.\\d+)?\\s*(?:mg|mcg|g|IU|ml))\\s+"
          + "(\\d-\\d-\\d|Once a week|OD|BD|TDS|HS|SOS)\\s+(.+?)\\s+(\\d+\\s*(?:days?|weeks?|months?))\\s*$");

  static LlmResponse copy(List<Msg> messages) {
    String text = messages.get(messages.size() - 1).text();
    int start = text.indexOf("<document>");
    int end = text.indexOf("</document>");
    if (start < 0 || end < 0) return LlmResponse.text("{}");
    ObjectNode reply = Json.obj();
    ArrayNode tests = reply.putArray("tests");
    ArrayNode meds = reply.putArray("medicines");
    boolean rx = false;
    for (String raw : text.substring(start + 10, end).split("\n")) {
      String line = raw.strip();
      Matcher date = Pattern.compile("Date:\\s*(\\d{2}/\\d{2}/\\d{4})|Collected:\\s*(\\d{2}/\\d{2}/\\d{4})").matcher(line);
      if (date.find()) reply.put("documentDate", date.group(1) != null ? date.group(1) : date.group(2));
      if (line.equals("Rx")) rx = true;
      Matcher m = RX_LINE.matcher(line);
      if (m.matches()) {
        meds.addObject().put("name", m.group(1)).put("nameSure", true).put("strength", m.group(2))
            .put("form", m.group(1).startsWith("Cap") ? "capsule" : "tablet").put("dose", m.group(3))
            .put("timing", m.group(4)).put("duration", m.group(5));
        continue;
      }
      if (rx || line.contains("Age/Sex") || line.startsWith("FICTIONAL") || line.startsWith("Test ")) continue;
      m = LAB_LINE.matcher(line);
      if (m.matches()) {
        ObjectNode t = tests.addObject().put("name", m.group(1)).put("value", m.group(2)).put("unit", m.group(3));
        if (m.group(4) != null) t.put("range", m.group(4));
      }
    }
    reply.put("kind", meds.isEmpty() ? "lab_report" : "prescription");
    return LlmResponse.text(reply.toString());
  }

  // ── The run ───────────────────────────────────────────────────────────────

  private JsonNode read(String name, byte[] pdf) {
    var up = api.upload("/api/vita/people/" + person + "/documents", Map.of(), name, "application/pdf", pdf);
    assertThat(up.status()).as(up.body()).isEqualTo(201);
    var read = api.post("/api/vita/documents/" + up.json().path("id").asText() + "/extract", Map.of());
    assertThat(read.ok()).as(read.body()).isTrue();
    assertThat(read.json().path("draft").path("readFrom").asText()).as(name).isEqualTo("text");
    return read.json().path("draft");
  }

  private static byte[] render(String id, List<Row> rows) throws Exception {
    List<String> lines = new ArrayList<>();
    lines.add("FICTIONAL RENDER OF THE " + id + " ROWS FOR SOFTWARE TESTING (no patient)");
    lines.add("Age/Sex: 25/M      Collected: 01/10/2026");
    lines.add("Test   Result   Unit   Reference");
    for (Row r : rows) lines.add(r.name() + "   " + r.value() + "   " + r.unit() + (r.range() == null ? "" : "   " + r.range()));
    return VitaDocumentsE2eTest.pdf(List.of(lines), null);
  }

  /** The flag Vita should give: the lab printed none, so it comes from the printed range. */
  private static String expectedFlag(Row r) {
    if (r.range() == null || r.range().contains(":")) return "unknown";
    if (r.flag().contains("above range")) return "high";
    if (r.flag().contains("below range")) return "low";
    return "normal";
  }

  @Test
  void everyPrintedRowReachesTheReviewAsExpectedMdSays() throws Exception {
    var tables = tables();
    StringBuilder report = new StringBuilder("| Sample | Printed rows | Rows in the review | Right without edits |\n|---|---|---|---|\n");

    for (String id : List.of("lab-02", "lab-01", "lab-04")) {
      List<Row> expected = labRows(tables.get(id));
      JsonNode draft = read(id + ".pdf", render(id, expected));
      JsonNode tests = draft.path("tests");
      assertThat(tests).as(id).hasSize(expected.size());
      int right = 0;
      for (int i = 0; i < expected.size(); i++) {
        Row e = expected.get(i);
        JsonNode t = tests.get(i);
        assertThat(t.path("name").asText()).as(id + " row " + (i + 1)).isEqualTo(e.name());
        assertThat(t.path("valueAsPrinted").asText()).isEqualTo(e.value());
        assertThat(t.path("value").decimalValue()).as(id + " " + e.name())
            .isEqualByComparingTo(new BigDecimal(e.value().replace(",", "")));
        assertThat(t.path("unit").asText()).isEqualTo(e.unit());
        assertThat(t.path("rangeAsPrinted").isNull() ? null : t.path("rangeAsPrinted").asText()).isEqualTo(e.range());
        if (e.range() != null && e.range().contains(":")) {
          assertThat(t.path("refText").asText()).as("bands kept whole").isEqualTo(e.range());
        }
        assertThat(t.path("flagAsPrinted").isNull()).isTrue();
        assertThat(t.path("flag").asText()).as(id + " " + e.name() + " flag").isEqualTo(expectedFlag(e));
        if (!t.path("needsCheck").asBoolean()) right++;
      }
      report.append("| ").append(id).append(" (fictional render) | ").append(expected.size()).append(" | ")
          .append(tests.size()).append(" | ").append(right).append(" |\n");
    }

    for (String id : List.of("rx-01", "rx-02")) {
      List<List<String>> expected = tables.get(id);
      JsonNode draft = read(id + "-fictional.pdf", VitaDocumentsE2eTest.resource(id + "-fictional.pdf"));
      JsonNode meds = draft.path("medicines");
      assertThat(meds).as(id).hasSize(expected.size());
      int right = 0;
      for (int i = 0; i < expected.size(); i++) {
        List<String> e = expected.get(i);
        JsonNode m = meds.get(i);
        assertThat(m.path("name").asText() + " " + m.path("strength").asText()).isEqualTo(e.get(1));
        assertThat(m.path("doseAsPrinted").asText()).isEqualTo(e.get(2).equals("Once a week") ? "Once a week" : e.get(2));
        assertThat(m.path("timingAsPrinted").asText()).isEqualTo(e.get(3));
        assertThat(m.path("durationAsPrinted").asText()).isEqualTo(e.get(4));
        if (!m.path("needsCheck").asBoolean()) right++;
      }
      report.append("| ").append(id).append(" | ").append(expected.size()).append(" | ").append(meds.size())
          .append(" | ").append(right).append(" |\n");
    }
    assertThat(api.get("/api/vita/people/" + person + "/medicines").json()).as("nothing saved").isEmpty();

    Path out = Path.of("target", "vita-samples-report.md");
    Files.createDirectories(out.getParent());
    Files.writeString(out, report.toString());
    System.out.println("Vita sample extraction\n" + report);
  }

  @Test
  void theDatesOfTheSamplesAreReadDayFirst() throws Exception {
    assertThat(read("rx-01.pdf", VitaDocumentsE2eTest.resource("rx-01-fictional.pdf")).path("documentDate").asText())
        .isEqualTo("2026-10-12");
    assertThat(read("rx-02.pdf", VitaDocumentsE2eTest.resource("rx-02-fictional.pdf")).path("documentDate").asText())
        .isEqualTo("2026-11-05");
  }
}

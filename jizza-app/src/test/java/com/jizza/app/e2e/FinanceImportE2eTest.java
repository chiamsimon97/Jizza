package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Kai Portfolio, Phase 1c through the API, with made-up files only: a bank CSV and a locked CAS
 * PDF are read, reviewed, saved and undone; the same file is refused the second time; a wrong
 * password is a plain 400; and undo puts every figure back.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class FinanceImportE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  static final String APRIL = """
      Account Name,:,<redacted>
      Account Number,:,<redacted>

      Txn Date,Value Date,Description,Ref No./Cheque No.,Debit,Credit,Balance
      2 Apr 2025,2 Apr 2025,TO TRANSFER-UPI/DR/12345/HOUSE RENT,,15000.00,,20000.00
      4 Apr 2025,4 Apr 2025,BY TRANSFER-NEFT SALARY APRIL,,,50000.00,70000.00
      6 Apr 2025,6 Apr 2025,UPI-CORNER BAKERY-cb@okbank-998877,,250.00,,69750.00
      """;

  static final String APRIL_AND_MAY = """
      Txn Date,Value Date,Description,Ref No./Cheque No.,Debit,Credit,Balance
      6 Apr 2025,6 Apr 2025,UPI-CORNER BAKERY-cb@okbank-998877,,250.00,,69750.00
      3 May 2025,3 May 2025,UPI-CORNER BAKERY-cb@okbank-112233,,180.00,,69570.00
      """;

  static final List<String> CAS = List.of(
      "Consolidated Account Statement",
      "Example Mutual Fund",
      "Folio No: 1234567 / 89 PAN: <redacted> KYC: OK",
      "EX01-Example Flexi Cap Fund - Direct Plan - Growth - ISIN: INF000X01AA1(Advisor: DIRECT) Registrar : CAMS",
      "Opening Unit Balance: 0.000",
      "05-Apr-2024 Purchase - Systematic Investment 5,000.00 100.000 50.0000 100.000",
      "05-Apr-2024 *** Stamp Duty *** 0.25",
      "05-May-2024 SIP Purchase 5,000.00 95.238 52.5000 195.238",
      "Closing Unit Balance: 195.238 NAV on 31-Mar-2025: INR 61.0000 Market Value on 31-Mar-2025: INR 11,909.52",
      "Other Mutual Fund",
      "Folio No: 99887766 PAN: <redacted>",
      "OT01-Other Gold Savings Fund - Growth - ISIN: INF000Y01CC3 Registrar : KFINTECH",
      "01-Jul-2024 Switch In - From Other Fund 2,000.00 200.000 10.0000 200.000",
      "Closing Unit Balance: 200.000 NAV on 31-Mar-2025: INR 11.0000 Market Value on 31-Mar-2025: INR 2,200.00");

  @LocalServerPort private int port;

  private ApiClient api;
  private String me;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    String email = "imports-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    assertThat(api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    me = api.get("/api/finance/profile").json().path("people").get(0).path("id").asText();
  }

  private JsonNode ok(ApiClient.Result r) {
    assertThat(r.ok()).as(r.body()).isTrue();
    return r.json();
  }

  private BigDecimal assets() {
    return ok(api.get("/api/finance/net-worth")).path("assets").decimalValue();
  }

  private ApiClient.Result upload(Map<String, String> fields, String name, String type, byte[] bytes) {
    return api.upload("/api/finance/imports", fields, name, type, bytes);
  }

  @Test
  void aBankStatementIsReviewedSavedRefusedTwiceAndUndone() {
    String account = ok(api.post("/api/finance/accounts", Map.of("kind", "savings", "provider", "Example Bank",
        "nickname", "Main"))).path("id").asText();
    String balance = ok(api.post("/api/finance/assets", Map.of("kind", "savings_balance", "accountId", account,
        "value", 10000, "valueAsOf", "2025-03-01"))).path("id").asText();
    BigDecimal before = assets();
    Map<String, String> categories = new HashMap<>();
    ok(api.get("/api/finance/categories")).forEach(c -> categories.put(c.path("systemKey").asText(), c.path("id").asText()));

    byte[] april = APRIL.getBytes(StandardCharsets.UTF_8);
    JsonNode batch = ok(upload(Map.of("kind", "bank_csv", "accountId", account), "april.csv", "text/csv", april));
    assertThat(batch.path("status").asText()).isEqualTo("parsed");
    JsonNode counts = batch.path("stats").path("counts");
    assertThat(counts.path("new").asInt()).isEqualTo(3);
    assertThat(counts.path("duplicates").asInt()).isZero();
    assertThat(counts.path("needsLook").asInt()).isEqualTo(1);
    assertThat(batch.path("stats").path("balance").path("amount").decimalValue()).isEqualByComparingTo("69750");
    JsonNode rows = batch.path("stats").path("rows");
    assertThat(rows.get(0).path("categoryId").asText()).isEqualTo(categories.get("rent"));
    assertThat(rows.get(1).path("categoryId").asText()).isEqualTo(categories.get("salary"));
    assertThat(rows.get(2).path("needsLook").asBoolean()).isTrue();
    // Nothing is saved before the person says so.
    assertThat(ok(api.get("/api/finance/transactions?account=" + account))).isEmpty();
    assertThat(assets()).isEqualByComparingTo(before);

    String id = batch.path("id").asText();
    JsonNode saved = ok(api.post("/api/finance/imports/" + id + "/commit",
        Map.of("categories", Map.of("2", categories.get("lifestyle")), "always", List.of(2))));
    assertThat(saved.path("status").asText()).isEqualTo("committed");
    assertThat(saved.path("stats").path("counts").path("imported").asInt()).isEqualTo(3);
    assertThat(assets()).isEqualByComparingTo(before.subtract(new BigDecimal("10000")).add(new BigDecimal("69750")));
    JsonNode txns = ok(api.get("/api/finance/transactions?account=" + account + "&month=2025-04"));
    assertThat(txns).hasSize(3);
    txns.forEach(t -> assertThat(t.path("needsMapping").asBoolean()).isFalse());

    // The same file again is refused; the history lists the import without its rows.
    var again = upload(Map.of("kind", "bank_csv", "accountId", account), "april-copy.csv", "text/csv", april);
    assertThat(again.status()).isEqualTo(409);
    JsonNode history = ok(api.get("/api/finance/imports"));
    assertThat(history).hasSize(1);
    assertThat(history.get(0).path("stats").has("rows")).isFalse();

    // An overlapping statement brings in only the new row, already in the remembered category.
    JsonNode overlap = ok(upload(Map.of("kind", "bank_csv", "accountId", account), "may.csv", "text/csv",
        APRIL_AND_MAY.getBytes(StandardCharsets.UTF_8)));
    assertThat(overlap.path("stats").path("counts").path("duplicates").asInt()).isEqualTo(1);
    assertThat(overlap.path("stats").path("counts").path("new").asInt()).isEqualTo(1);
    assertThat(overlap.path("stats").path("rows").get(1).path("categoryId").asText()).isEqualTo(categories.get("lifestyle"));

    // The older import can't be undone under the newer one that moved the same balance.
    String may = overlap.path("id").asText();
    ok(api.post("/api/finance/imports/" + may + "/commit", Map.of()));
    assertThat(api.post("/api/finance/imports/" + id + "/undo", Map.of()).status()).isEqualTo(409);
    assertThat(ok(api.post("/api/finance/imports/" + may + "/undo", Map.of())).path("status").asText()).isEqualTo("undone");
    assertThat(ok(api.post("/api/finance/imports/" + id + "/undo", Map.of())).path("status").asText()).isEqualTo("undone");

    // Every figure is back.
    assertThat(assets()).isEqualByComparingTo(before);
    JsonNode asset = ok(api.get("/api/finance/assets/" + balance));
    assertThat(asset.path("value").decimalValue()).isEqualByComparingTo("10000");
    assertThat(asset.path("valueAsOf").asText()).isEqualTo("2025-03-01");
    assertThat(ok(api.get("/api/finance/transactions?account=" + account))).isEmpty();

    // Once undone, the file may come in again.
    assertThat(upload(Map.of("kind", "bank_csv", "accountId", account), "april.csv", "text/csv", april).status())
        .isEqualTo(201);
  }

  @Test
  void aTransactionCanBeRecategorisedForGood() {
    String account = ok(api.post("/api/finance/accounts", Map.of("kind", "savings"))).path("id").asText();
    String id = ok(upload(Map.of("kind", "bank_csv", "accountId", account), "april.csv", "text/csv",
        APRIL.getBytes(StandardCharsets.UTF_8))).path("id").asText();
    ok(api.post("/api/finance/imports/" + id + "/commit", Map.of()));
    JsonNode bakery = null;
    for (JsonNode t : ok(api.get("/api/finance/transactions"))) {
      if (t.path("description").asText().contains("BAKERY")) bakery = t;
    }
    assertThat(bakery).isNotNull();
    assertThat(bakery.path("needsMapping").asBoolean()).isTrue();
    String shopping = null;
    for (JsonNode c : ok(api.get("/api/finance/categories"))) {
      if ("shopping".equals(c.path("systemKey").asText())) shopping = c.path("id").asText();
    }
    JsonNode changed = ok(api.patch("/api/finance/transactions/" + bakery.path("id").asText(),
        Map.of("categoryId", shopping, "always", true)));
    assertThat(changed.path("transaction").path("categoryId").asText()).isEqualTo(shopping);
    assertThat(changed.path("transaction").path("needsMapping").asBoolean()).isFalse();

    var notMine = api.patch("/api/finance/transactions/" + bakery.path("id").asText(),
        Map.of("categoryId", UUID.randomUUID().toString()));
    assertThat(notMine.status()).isEqualTo(404);
  }

  @Test
  void aLockedCasNeedsItsPasswordThenImportsEachFolio() throws Exception {
    byte[] cas = pdf(CAS, "ABCDE1234F");
    BigDecimal before = assets();

    var locked = upload(Map.of("kind", "cas_pdf", "personId", me), "cas.pdf", "application/pdf", cas);
    assertThat(locked.status()).isEqualTo(400);
    assertThat(locked.body()).contains("Enter its password");
    var wrong = upload(Map.of("kind", "cas_pdf", "personId", me, "password", "nope"), "cas.pdf", "application/pdf", cas);
    assertThat(wrong.status()).isEqualTo(400);
    assertThat(wrong.body()).contains("password did not open").doesNotContain("nope");
    assertThat(ok(api.get("/api/finance/imports"))).isEmpty();

    JsonNode batch = ok(upload(Map.of("kind", "cas_pdf", "personId", me, "password", "ABCDE1234F"),
        "cas.pdf", "application/pdf", cas));
    JsonNode stats = batch.path("stats");
    assertThat(stats.path("groups")).hasSize(2);
    assertThat(stats.path("holdings")).hasSize(2);
    assertThat(stats.path("counts").path("new").asInt()).isEqualTo(4);
    assertThat(batch.toString()).doesNotContain("ABCDE1234F");

    ok(api.post("/api/finance/imports/" + batch.path("id").asText() + "/commit", Map.of()));
    long folios = 0;
    for (JsonNode a : ok(api.get("/api/finance/accounts"))) if ("mf_folio".equals(a.path("kind").asText())) folios++;
    assertThat(folios).isEqualTo(2);
    // Net worth is shown in whole rupees: 11,909.52 + 2,200.00.
    assertThat(assets()).isEqualByComparingTo(before.add(new BigDecimal("14110")));
    JsonNode txns = ok(api.get("/api/finance/transactions"));
    assertThat(txns).hasSize(4);

    ok(api.post("/api/finance/imports/" + batch.path("id").asText() + "/undo", Map.of()));
    assertThat(assets()).isEqualByComparingTo(before);
    assertThat(ok(api.get("/api/finance/transactions"))).isEmpty();
    for (JsonNode a : ok(api.get("/api/finance/accounts"))) assertThat(a.path("kind").asText()).isNotEqualTo("mf_folio");
  }

  @Test
  void aBrokerHoldingsFileReplacesTheAccountsSnapshot() {
    String demat = ok(api.post("/api/finance/accounts", Map.of("kind", "demat", "provider", "Example Broker")))
        .path("id").asText();
    BigDecimal before = assets();
    String first = """
        Instrument,Qty.,Avg. cost,LTP,Cur. val
        EXAMPLECO,10,1500,1600,16000
        OTHERCO,5,100,120,600
        """;
    String id = ok(upload(Map.of("kind", "broker_holdings", "accountId", demat), "holdings.csv", "text/csv",
        first.getBytes(StandardCharsets.UTF_8))).path("id").asText();
    ok(api.post("/api/finance/imports/" + id + "/commit", Map.of()));
    assertThat(assets()).isEqualByComparingTo(before.add(new BigDecimal("16600")));

    String second = """
        Instrument,Qty.,Avg. cost,LTP,Cur. val
        EXAMPLECO,12,1500,1700,20400
        """;
    String next = ok(upload(Map.of("kind", "broker_holdings", "accountId", demat), "holdings-2.csv", "text/csv",
        second.getBytes(StandardCharsets.UTF_8))).path("id").asText();
    ok(api.post("/api/finance/imports/" + next + "/commit", Map.of()));
    // OTHERCO is no longer held.
    assertThat(assets()).isEqualByComparingTo(before.add(new BigDecimal("20400")));
    ok(api.post("/api/finance/imports/" + next + "/undo", Map.of()));
    assertThat(assets()).isEqualByComparingTo(before.add(new BigDecimal("16600")));

    var wrongAccount = upload(Map.of("kind", "bank_csv", "accountId", demat), "a.csv", "text/csv",
        APRIL.getBytes(StandardCharsets.UTF_8));
    assertThat(wrongAccount.status()).isEqualTo(400);
  }

  @Test
  void aCsvWhoseColumnsKaiCannotTellWaitsForTheChoice() {
    String account = ok(api.post("/api/finance/accounts", Map.of("kind", "current"))).path("id").asText();
    byte[] odd = "When,What,Out,In\n01/04/2025,Something,100,\n02/04/2025,Other,,50\n".getBytes(StandardCharsets.UTF_8);
    JsonNode batch = ok(upload(Map.of("kind", "bank_csv", "accountId", account), "odd.csv", "text/csv", odd));
    assertThat(batch.path("status").asText()).isEqualTo("needs_review");
    assertThat(batch.path("stats").path("columns").path("sure").asBoolean()).isFalse();
    assertThat(api.post("/api/finance/imports/" + batch.path("id").asText() + "/commit", Map.of()).status()).isEqualTo(400);

    String mapping = "{\"headerRow\":0,\"date\":\"When\",\"description\":\"What\",\"debit\":\"Out\",\"credit\":\"In\"}";
    JsonNode chosen = ok(upload(Map.of("kind", "bank_csv", "accountId", account, "mapping", mapping), "odd.csv", "text/csv", odd));
    assertThat(chosen.path("status").asText()).isEqualTo("parsed");
    assertThat(chosen.path("stats").path("counts").path("new").asInt()).isEqualTo(2);
    // The earlier, unsaved upload of the same file made way for this one.
    assertThat(ok(api.get("/api/finance/imports"))).hasSize(1);
  }

  /** A one-page PDF with these lines, locked with the password. */
  static byte[] pdf(List<String> lines, String password) throws Exception {
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage();
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        cs.beginText();
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8);
        cs.setLeading(11);
        cs.newLineAtOffset(30, 760);
        for (String line : lines) {
          cs.showText(line);
          cs.newLine();
        }
        cs.endText();
      }
      var policy = new StandardProtectionPolicy("owner-" + password, password, new AccessPermission());
      policy.setEncryptionKeyLength(128);
      doc.protect(policy);
      var out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }
  }
}

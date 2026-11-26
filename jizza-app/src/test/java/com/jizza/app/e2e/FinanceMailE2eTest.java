package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Kai part B through the API: a mail server added on Connectors (a stub that answers with Gmail's
 * tool names and one made-up statement mail) is linked to a savings account. A check finds the
 * PDF in the raw mail, asks for the password when it is locked, keeps the password encrypted and
 * never shows it, and lands the statement as a batch to review; the same mail again changes
 * nothing. Only the read tools are called.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class FinanceMailE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";
  private static final String PDF_PASSWORD = "EXAMPLE0101";

  /** A made-up statement in a dd/mm/yy layout. */
  static final List<String> STATEMENT = List.of(
      "Example Bank Ltd    Page No .: 1",
      "Statement of account",
      "Opening Balance : 10,000.00",
      "Date Narration Chq./Ref.No. Value Dt Withdrawal Amt. Deposit Amt. Closing Balance",
      "01/04/26 UPI-GROCERY STORE-PAYMENT 0000612345678 01/04/26 500.00 9,500.00",
      "02/04/26 NEFT CR-EMPLOYER PVT LTD-SALARY APR 02/04/26 50,000.00 59,500.00",
      "05/04/26 EMI LOAN 12345 05/04/26 12,000.00 47,500.00");

  @LocalServerPort private int port;

  private ApiClient api;
  private StubMcpServer mail;

  @BeforeEach
  void setUp() throws Exception {
    mail = new StubMcpServer();
    byte[] pdf = FinanceImportE2eTest.pdf(STATEMENT, PDF_PASSWORD);
    mail.mailRaw = "From: Example Bank <statements@examplebank.test>\r\n"
        + "Subject: Your account statement\r\n"
        + "MIME-Version: 1.0\r\n"
        + "Content-Type: multipart/mixed; boundary=\"outer\"\r\n\r\n"
        + "--outer\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nYour statement is attached.\r\n"
        + "--outer\r\nContent-Type: application/pdf; name=\"April statement.pdf\"\r\n"
        + "Content-Disposition: attachment; filename=\"April statement.pdf\"\r\n"
        + "Content-Transfer-Encoding: base64\r\n\r\n"
        + Base64.getMimeEncoder().encodeToString(pdf) + "\r\n--outer--\r\n";
    api = new ApiClient(port);
    api.prime();
    String email = "mail-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    assertThat(api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    api.post("/api/auth/step-up", Map.of("password", PASSWORD));
    api.get("/api/finance/profile");
  }

  @AfterEach
  void tearDown() {
    mail.close();
  }

  private JsonNode ok(ApiClient.Result r) {
    assertThat(r.ok()).as(r.body()).isTrue();
    return r.json();
  }

  @Test
  void aBankStatementMailLandsAsABatchToReviewWithTheSavedPassword() {
    Map<String, Object> server = new HashMap<>();
    server.put("name", "Mail");
    server.put("url", mail.url());
    server.put("authType", "NONE");
    String serverId = ok(api.post("/api/mcp-servers", server)).path("id").asText();
    String savings = ok(api.post("/api/finance/accounts", Map.of("kind", "savings", "provider", "Example Bank")))
        .path("id").asText();

    JsonNode view = ok(api.get("/api/finance/mail-sources"));
    assertThat(view.path("servers")).hasSize(1);
    assertThat(view.path("casSenders").asText()).contains("camsonline.com");
    // A bank source needs the sender; a bad one is refused.
    assertThat(api.post("/api/finance/mail-sources", Map.of("serverId", serverId, "kind", "bank", "accountId", savings))
        .status()).isEqualTo(400);
    assertThat(api.post("/api/finance/mail-sources", Map.of("serverId", serverId, "kind", "bank", "accountId", savings,
        "sender", "from:(anyone) OR x")).status()).isEqualTo(400);
    JsonNode source = ok(api.post("/api/finance/mail-sources", Map.of("serverId", serverId, "kind", "bank",
        "accountId", savings, "sender", "Statements@ExampleBank.test")));
    String id = source.path("id").asText();
    assertThat(source.path("sender").asText()).isEqualTo("statements@examplebank.test");
    assertThat(source.path("hasPassword").asBoolean()).isFalse();

    // Locked, and no password saved yet: Kai asks for it.
    JsonNode locked = ok(api.post("/api/finance/mail-sources/" + id + "/check", Map.of()));
    assertThat(locked.path("status").asText()).isEqualTo("needs_password");
    assertThat(ok(api.get("/api/finance/imports"))).isEmpty();

    JsonNode changed = ok(api.patch("/api/finance/mail-sources/" + id, Map.of("password", PDF_PASSWORD)));
    assertThat(changed.path("hasPassword").asBoolean()).isTrue();
    var listed = api.get("/api/finance/mail-sources");
    assertThat(listed.body()).doesNotContain(PDF_PASSWORD).doesNotContain("passwordEnc").doesNotContain("password_enc");

    JsonNode found = ok(api.post("/api/finance/mail-sources/" + id + "/check", Map.of()));
    assertThat(found.path("status").asText()).isEqualTo("to_review");
    assertThat(found.path("found").asInt()).isEqualTo(1);
    JsonNode batch = ok(api.get("/api/finance/imports/" + found.path("batchId").asText()));
    assertThat(batch.path("kind").asText()).isEqualTo("bank_pdf");
    assertThat(batch.path("status").asText()).isEqualTo("parsed");
    assertThat(batch.path("stats").path("source").asText()).isEqualTo("Email · Mail");
    assertThat(batch.path("stats").path("fileName").asText()).isEqualTo("April statement.pdf");
    assertThat(batch.path("stats").path("counts").path("new").asInt()).isEqualTo(3);
    assertThat(batch.path("stats").path("balance").path("amount").decimalValue()).isEqualByComparingTo("47500");
    assertThat(batch.toString()).doesNotContain(PDF_PASSWORD);

    // The same mail again: nothing new, and the waiting review is left as it is.
    JsonNode again = ok(api.post("/api/finance/mail-sources/" + id + "/check", Map.of()));
    assertThat(again.path("status").asText()).isEqualTo("nothing_new");
    assertThat(ok(api.get("/api/finance/imports"))).hasSize(1);
    ok(api.post("/api/finance/imports/" + found.path("batchId").asText() + "/commit", Map.of()));
    assertThat(ok(api.post("/api/finance/mail-sources/" + id + "/check", Map.of())).path("status").asText())
        .isEqualTo("nothing_new");

    // Only the read tools, and the search names the sender and PDFs.
    assertThat(mail.calls).isNotEmpty().allMatch(c -> c.startsWith("search_threads") || c.startsWith("get_thread"));
    assertThat(mail.calls.get(0)).contains("from:(statements@examplebank.test) has:attachment filename:pdf after:");

    // The data export leaves the encrypted password out.
    assertThat(api.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
    var export = api.get("/api/data/export");
    assertThat(export.ok()).as(export.body()).isTrue();
    assertThat(export.json().path("finance").path("records").path("fin_mail_source")).hasSize(1);
    assertThat(export.body()).doesNotContain("passwordEnc").doesNotContain("password_enc").doesNotContain(PDF_PASSWORD);

    // Another account can't see, change or check it.
    ApiClient other = new ApiClient(port);
    other.prime();
    String email = "mail-other-" + UUID.randomUUID() + "@jizza.local";
    other.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    other.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    other.get("/api/finance/profile");
    assertThat(ok(other.get("/api/finance/mail-sources")).path("sources")).isEmpty();
    assertThat(other.post("/api/finance/mail-sources/" + id + "/check", Map.of()).status()).isEqualTo(404);
    assertThat(other.patch("/api/finance/mail-sources/" + id, Map.of("password", "x")).status()).isEqualTo(404);
    assertThat(other.delete("/api/finance/mail-sources/" + id).status()).isEqualTo(404);

    assertThat(api.delete("/api/finance/mail-sources/" + id).status()).isEqualTo(204);
    assertThat(ok(api.get("/api/finance/mail-sources")).path("sources")).isEmpty();
  }

  @Test
  void aCasSourceDefaultsToTheRegistrarsAndBelongsToAPerson() {
    Map<String, Object> server = new HashMap<>();
    server.put("name", "Mail");
    server.put("url", mail.url());
    server.put("authType", "NONE");
    String serverId = ok(api.post("/api/mcp-servers", server)).path("id").asText();
    JsonNode cas = ok(api.post("/api/finance/mail-sources", Map.of("serverId", serverId, "kind", "cas",
        "password", "ABCDE1234F")));
    assertThat(cas.path("sender").asText()).isEqualTo("camsonline.com kfintech.com");
    assertThat(cas.path("personId").asText()).isNotBlank();
    assertThat(cas.path("accountId").isNull()).isTrue();
    assertThat(cas.path("hasPassword").asBoolean()).isTrue();
    // The bank statement in the mail is not a CAS: it is left alone, nothing lands.
    JsonNode checked = ok(api.post("/api/finance/mail-sources/" + cas.path("id").asText() + "/check", Map.of()));
    assertThat(checked.path("status").asText()).isIn("nothing_new", "needs_password");
    assertThat(ok(api.get("/api/finance/imports"))).isEmpty();
    assertThat(mail.calls.get(0)).contains("from:(camsonline.com OR kfintech.com)");
  }
}

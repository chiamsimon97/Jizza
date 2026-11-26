package com.jizza.platform.usecase.finance.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.TextNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Finding PDFs in raw mail, and the pieces of a mailbox check that need no server. */
class MailPullTest {

  private static final byte[] PDF = "%PDF-1.4 made-up statement".getBytes(StandardCharsets.ISO_8859_1);

  static String mail(String pdfPart) {
    return "From: <statements@examplebank.test>\n"
        + "Content-Type: multipart/mixed;\n\tboundary=\"a1\"\n\n"
        + "--a1\nContent-Type: multipart/alternative; boundary=b2\n\n"
        + "--b2\nContent-Type: text/plain\n\nHello\n--b2\nContent-Type: text/html\n\n<p>Hello</p>\n--b2--\n"
        + "--a1\n" + pdfPart + "\n--a1\nContent-Type: image/png; name=\"logo.png\"\nContent-Transfer-Encoding: base64\n\n"
        + Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}) + "\n--a1--\n";
  }

  @Test
  void findsThePdfInNestedPartsAndSkipsTheRest() {
    String raw = mail("Content-Type: application/pdf; name=\"x.pdf\"\nContent-Disposition: attachment;\n filename=\"April 2026.pdf\"\n"
        + "Content-Transfer-Encoding: base64\n\n" + Base64.getMimeEncoder().encodeToString(PDF));
    var pdfs = MimePdfs.extract(raw);
    assertThat(pdfs).hasSize(1);
    assertThat(pdfs.get(0).filename()).isEqualTo("April 2026.pdf");
    assertThat(pdfs.get(0).bytes()).isEqualTo(PDF);
  }

  @Test
  void readsAWholeMessageGivenBase64urlAndOctetStreamPdfs() {
    String raw = mail("Content-Type: application/octet-stream\nContent-Disposition: attachment; filename*=utf-8''CAS%20Mar.pdf\n"
        + "Content-Transfer-Encoding: base64\n\n" + Base64.getMimeEncoder().encodeToString(PDF)).replace("\n", "\r\n");
    String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.ISO_8859_1));
    var pdfs = MimePdfs.extract(encoded);
    assertThat(pdfs).hasSize(1);
    assertThat(pdfs.get(0).filename()).isEqualTo("CAS Mar.pdf");
    assertThat(pdfs.get(0).bytes()).isEqualTo(PDF);
  }

  @Test
  void aMailWithNoPdfHasNone() {
    assertThat(MimePdfs.extract("Subject: hi\n\nJust text")).isEmpty();
    assertThat(MimePdfs.extract(null)).isEmpty();
  }

  @Test
  void theSearchNamesTheSenderPdfsAndASinceDate() {
    var row = Json.obj().put("sender", "camsonline.com kfintech.com").put("subject", "Consolidated Account Statement");
    assertThat(MailPullService.query(row, LocalDate.of(2026, 11, 26)))
        .isEqualTo("from:(camsonline.com OR kfintech.com) has:attachment filename:pdf after:2026/10/12"
            + " subject:(\"Consolidated Account Statement\")");
    row.put("lastCheckedAt", "2026-11-20T02:00:00Z").remove("subject");
    assertThat(MailPullService.query(row, LocalDate.of(2026, 11, 26))).endsWith("after:2026/11/17");
  }

  @Test
  void sendersAreAddressesOrDomainsOnly() {
    assertThat(MailPullService.senders("Alerts@Bank.example, bank.example")).isEqualTo("alerts@bank.example bank.example");
    assertThatThrownBy(() -> MailPullService.senders("from:(x) OR y")).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> MailPullService.senders("  ")).isInstanceOf(ValidationException.class);
    assertThat(MailPullService.subject(" \"Statement\" (April) ")).isEqualTo("Statement April");
    assertThat(MailPullService.subject("  ")).isNull();
  }

  @Test
  void threadIdsAndRawMessagesFromTheAnswers() {
    assertThat(MailPullService.threadIds(new TextNode("{\"threads\":[{\"id\":\"t1\"},{\"id\":\"t2\"},{\"id\":\"t1\"}]}")))
        .containsExactly("t1", "t2");
    assertThat(MailPullService.threadIds(new TextNode("No threads found."))).isEmpty();
    assertThat(MailPullService.raws(new TextNode("{\"messages\":[{\"id\":\"m\",\"raw\":\"R1\"},{\"raw\":\"R2\"}]}")))
        .containsExactly("R1", "R2");
  }

  @Test
  void onlyReadToolsAreEverCalled() {
    assertThat(MailPullService.READ_TOOLS).containsExactlyInAnyOrder("search_threads", "get_thread");
  }
}

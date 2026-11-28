package com.jizza.platform.usecase.vita.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.TextNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.Json;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class VitaMailPullTest {

  @Test
  void theSearchNamesTheSenderPdfsAndASinceDate() {
    var row = Json.obj().put("sender", "reports@lab.test lab2.test");
    assertThat(VitaMailPullService.query(row, LocalDate.of(2026, 11, 28)))
        .isEqualTo("from:(reports@lab.test OR lab2.test) has:attachment filename:pdf after:2026/10/14");
    row.put("lastCheckedAt", "2026-11-20T01:00:00Z").put("subject", "Test report");
    assertThat(VitaMailPullService.query(row, LocalDate.of(2026, 11, 28)))
        .isEqualTo("from:(reports@lab.test OR lab2.test) has:attachment filename:pdf after:2026/11/17 subject:(\"Test report\")");
  }

  @Test
  void sendersAreAddressesOrDomainsOnly() {
    assertThat(VitaMailPullService.senders("Reports@Lab.test, lab2.test")).isEqualTo("reports@lab.test lab2.test");
    assertThatThrownBy(() -> VitaMailPullService.senders("from:(x) OR y")).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> VitaMailPullService.senders(" ")).isInstanceOf(ValidationException.class);
    assertThat(VitaMailPullService.subject("\"Report\" (ready): {x}")).isEqualTo("Report ready x");
    assertThat(VitaMailPullService.subject("  ")).isNull();
  }

  @Test
  void theSamePdfHasTheSameFingerprint() {
    byte[] a = "%PDF-1.4 a".getBytes(StandardCharsets.UTF_8);
    byte[] b = "%PDF-1.4 b".getBytes(StandardCharsets.UTF_8);
    assertThat(VitaMailPullService.fingerprint(a)).isEqualTo(VitaMailPullService.fingerprint(a.clone()))
        .startsWith("mail:").hasSize(5 + 64).isNotEqualTo(VitaMailPullService.fingerprint(b));
  }

  @Test
  void threadIdsAndRawMessagesAreReadFromJsonOrText() {
    assertThat(VitaMailPullService.threadIds(new TextNode("{\"threads\":[{\"id\":\"t1\"},{\"id\":\"t1\"},{\"threadId\":\"t2\"}]}")))
        .containsExactly("t1", "t2");
    assertThat(VitaMailPullService.threadIds(new TextNode("No mail found"))).isEmpty();
    var thread = Json.obj();
    thread.putArray("messages").addObject().put("raw", "abc");
    assertThat(VitaMailPullService.raws(thread)).containsExactly("abc");
  }
}

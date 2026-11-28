package com.jizza.platform.api.rest.vita;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.vita.documents.VitaMailPullService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports from the mailbox (spec 6, Phase 1b), behind the health switch: link a mail server from
 * Connectors, name the lab's sender and the person, and check now. Every report found waits as a
 * document to review. The PDF password is kept encrypted and never sent back. Another account's
 * source reads as not found.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/vita")
@ConditionalOnProperty(name = "jizza.health.enabled", havingValue = "true")
public class VitaMailController {

  private final VitaMailPullService mail;

  public VitaMailController(VitaMailPullService mail) {
    this.mail = mail;
  }

  /** {@code password} is the PDF password; it is kept encrypted and never sent back. */
  public record MailBody(UUID serverId, UUID personId, String sender, String subject, String password) {}

  /** Linked mail sources, and the servers on Connectors that can search and read mail. */
  @GetMapping("/mail-sources")
  public VitaMailPullService.SourcesView sources() {
    return mail.view(CurrentUser.userId());
  }

  @PostMapping("/mail-sources")
  @ResponseStatus(HttpStatus.CREATED)
  public VitaMailPullService.Source link(@RequestBody MailBody body) {
    return mail.link(CurrentUser.userId(), new VitaMailPullService.Link(body.serverId(), body.personId(),
        body.sender(), body.subject(), body.password()));
  }

  /** Any of sender, subject, personId, password (blank keeps the saved one) and enabled. */
  @PutMapping("/mail-sources/{id}")
  public VitaMailPullService.Source change(@PathVariable UUID id, @RequestBody JsonNode body) {
    return mail.change(CurrentUser.userId(), id, body);
  }

  @DeleteMapping("/mail-sources/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unlink(@PathVariable UUID id) {
    mail.unlink(CurrentUser.userId(), id);
  }

  /** Looks for new report mails now; each one found is a document to review, never saved on its own. */
  @PostMapping("/mail-sources/{id}/check")
  public VitaMailPullService.CheckResult check(@PathVariable UUID id) {
    return mail.checkNow(CurrentUser.userId(), id);
  }
}

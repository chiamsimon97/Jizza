package com.jizza.platform.api.rest.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.finance.imports.ImportService;
import com.jizza.platform.usecase.finance.sync.BrokerSyncService;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Kai Portfolio, Phase 1c: statement imports (bank CSV, CAS PDF, broker holdings and tradebook)
 * with review before saving and undo, and the transactions they bring in. Every route is the
 * caller's own data only. Exists only while {@code jizza.finance.enabled} is on.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/finance")
@ConditionalOnProperty(name = "jizza.finance.enabled", havingValue = "true")
public class FinanceImportController {

  private final ImportService imports;
  private final BrokerSyncService sync;

  public FinanceImportController(ImportService imports, BrokerSyncService sync) {
    this.imports = imports;
    this.sync = sync;
  }

  /** The file and what it is. {@code password} opens a locked PDF for this one read and is never kept. */
  @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public ObjectNode upload(@RequestParam("file") MultipartFile file, @RequestParam("kind") String kind,
                           @RequestParam(value = "personId", required = false) UUID personId,
                           @RequestParam(value = "accountId", required = false) UUID accountId,
                           @RequestParam(value = "mapping", required = false) String mapping,
                           @RequestParam(value = "password", required = false) String password) {
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new ValidationException("The file could not be read.");
    }
    JsonNode columns = null;
    if (mapping != null && !mapping.isBlank()) {
      try {
        columns = Json.mapper().readTree(mapping);
      } catch (IOException e) {
        throw new ValidationException("The column choice could not be read.");
      }
    }
    return imports.upload(CurrentUser.userId(), new ImportService.Upload(kind, personId, accountId, columns,
        password, file.getOriginalFilename(), file.getContentType(), bytes));
  }

  @GetMapping("/imports")
  public List<ObjectNode> list(@RequestParam(value = "account", required = false) UUID accountId) {
    return imports.list(CurrentUser.userId(), accountId);
  }

  @GetMapping("/imports/{id}")
  public ObjectNode get(@PathVariable UUID id) {
    return imports.get(CurrentUser.userId(), id);
  }

  /** {@code categories} maps a row number to the category chosen for it; {@code always} lists rows to remember. */
  public record CommitBody(Map<String, UUID> categories, Set<Integer> always) {}

  @PostMapping("/imports/{id}/commit")
  public ObjectNode commit(@PathVariable UUID id, @RequestBody(required = false) CommitBody body) {
    return imports.commit(CurrentUser.userId(), id,
        body == null ? null : new ImportService.Choices(body.categories(), body.always()));
  }

  @PostMapping("/imports/{id}/undo")
  public ObjectNode undo(@PathVariable UUID id) {
    return imports.undo(CurrentUser.userId(), id);
  }

  @GetMapping("/transactions")
  public List<ObjectNode> transactions(@RequestParam(value = "account", required = false) UUID accountId,
                                       @RequestParam(value = "month", required = false) String month) {
    return imports.transactions(CurrentUser.userId(), accountId, month);
  }

  public record CategoryBody(UUID categoryId, Boolean always) {}

  @PatchMapping("/transactions/{id}")
  public ImportService.Recategorised recategorise(@PathVariable UUID id, @RequestBody CategoryBody body) {
    return imports.recategorise(CurrentUser.userId(), id, body == null ? null : body.categoryId(),
        body != null && Boolean.TRUE.equals(body.always()));
  }

  // ── Connected sources ───────────────────────────────────────────────────────

  public record LinkBody(UUID serverId, UUID accountId) {}

  public record EnabledBody(Boolean enabled) {}

  /** Linked sources, and the servers on Connectors that could be one (they offer holdings). */
  @GetMapping("/sources")
  public BrokerSyncService.SourcesView sources() {
    return sync.view(CurrentUser.userId());
  }

  @PostMapping("/sources")
  public BrokerSyncService.Source link(@RequestBody LinkBody body) {
    if (body == null || body.serverId() == null || body.accountId() == null) {
      throw new ValidationException("Choose a connected server and an account.");
    }
    return sync.link(CurrentUser.userId(), body.serverId(), body.accountId());
  }

  @PatchMapping("/sources/{id}")
  public BrokerSyncService.Source setEnabled(@PathVariable UUID id, @RequestBody EnabledBody body) {
    if (body == null || body.enabled() == null) throw new ValidationException("Say whether the source is on.");
    return sync.setEnabled(CurrentUser.userId(), id, body.enabled());
  }

  @DeleteMapping("/sources/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unlink(@PathVariable UUID id) {
    sync.unlink(CurrentUser.userId(), id);
  }

  /** Reads the holdings now; the result is a batch to review, never saved on its own. */
  @PostMapping("/sources/{id}/sync")
  public BrokerSyncService.SyncResult syncNow(@PathVariable UUID id) {
    return sync.syncNow(CurrentUser.userId(), id);
  }
}

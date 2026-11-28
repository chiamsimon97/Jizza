package com.jizza.platform.api.rest.vita;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.vita.labs.VitaCheckUseCase;
import com.jizza.platform.usecase.vita.labs.VitaLabCatalog;
import com.jizza.platform.usecase.vita.labs.VitaLabUseCase;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vita's lab reports, the trend of one test, and checks (spec 5.12, Phase 1b), behind the health
 * switch. A report is made only by confirming a lab-report document; here it is read, corrected or
 * removed. Another account's report or check reads as not found.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/vita")
@ConditionalOnProperty(name = "jizza.health.enabled", havingValue = "true")
public class VitaLabController {

  private final VitaLabUseCase labs;
  private final VitaCheckUseCase checks;
  private final VitaLabCatalog catalog;

  public VitaLabController(VitaLabUseCase labs, VitaCheckUseCase checks, VitaLabCatalog catalog) {
    this.labs = labs;
    this.checks = checks;
    this.catalog = catalog;
  }

  /** The canonical tests a row can be matched to, for the review's "which test is this?" picker. */
  @GetMapping("/lab-tests")
  public ArrayNode labTests() {
    ArrayNode out = Json.mapper().createArrayNode();
    for (var t : catalog.normaliser().tests()) {
      ObjectNode n = out.addObject().put("code", t.code()).put("name", t.name()).put("group", t.group())
          .put("unit", t.canonicalUnit()).put("decimals", t.decimals());
      ArrayNode conv = n.putArray("conversions");
      t.conversions().forEach(c -> conv.addObject().put("from", c.from()).put("source", c.source())
          .put("needsVerification", c.needsVerification()));
    }
    return out;
  }

  @GetMapping("/people/{personId}/labs")
  public List<ObjectNode> list(@PathVariable UUID personId) {
    return labs.list(CurrentUser.userId(), personId);
  }

  @GetMapping("/people/{personId}/labs/trend")
  public ObjectNode trend(@PathVariable UUID personId, @RequestParam("test") String test) {
    return labs.trend(CurrentUser.userId(), personId, test);
  }

  @GetMapping("/labs/{reportId}")
  public ObjectNode get(@PathVariable UUID reportId) {
    return labs.get(CurrentUser.userId(), reportId);
  }

  @PutMapping("/labs/{reportId}")
  public ObjectNode update(@PathVariable UUID reportId, @RequestBody JsonNode body) {
    return labs.update(CurrentUser.userId(), reportId, body);
  }

  @DeleteMapping("/labs/{reportId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID reportId) {
    labs.delete(CurrentUser.userId(), reportId);
  }

  @GetMapping("/people/{personId}/checks")
  public List<ObjectNode> checks(@PathVariable UUID personId) {
    return checks.list(CurrentUser.userId(), personId);
  }

  @PostMapping("/people/{personId}/checks")
  public ObjectNode addCheck(@PathVariable UUID personId, @RequestBody JsonNode body) {
    return checks.create(CurrentUser.userId(), personId, body);
  }

  @PutMapping("/people/{personId}/checks/{id}")
  public ObjectNode updateCheck(@PathVariable UUID personId, @PathVariable UUID id, @RequestBody JsonNode body) {
    return checks.update(CurrentUser.userId(), personId, id, body);
  }

  @DeleteMapping("/people/{personId}/checks/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteCheck(@PathVariable UUID personId, @PathVariable UUID id) {
    checks.delete(CurrentUser.userId(), personId, id);
  }
}

package com.jizza.platform.api.rest.vita;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.vita.records.VitaTable;
import com.jizza.platform.usecase.vita.visits.VitaHistoryUseCase;
import com.jizza.platform.usecase.vita.visits.VitaVisitUseCase;
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
 * Visit prep, the doctor-ready summary's data, vaccinations, and surgeries and stays (spec 5.12,
 * Phase 1b), behind the health switch. Prep and the summary are read-only and send nothing.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/vita")
@ConditionalOnProperty(name = "jizza.health.enabled", havingValue = "true")
public class VitaVisitController {

  private final VitaVisitUseCase visits;
  private final VitaHistoryUseCase history;

  public VitaVisitController(VitaVisitUseCase visits, VitaHistoryUseCase history) {
    this.visits = visits;
    this.history = history;
  }

  /** Builds the visit's prep page. Saves nothing and sends nothing. */
  @PostMapping("/visits/{id}/prepare")
  public ObjectNode prepare(@PathVariable UUID id) {
    return visits.prepare(CurrentUser.userId(), id);
  }

  /** The doctor-ready summary page's data; the client prints it. */
  @GetMapping("/people/{personId}/summary")
  public ObjectNode summary(@PathVariable UUID personId,
                            @RequestParam(value = "condition", required = false) UUID condition,
                            @RequestParam(value = "includePrivate", required = false, defaultValue = "false") boolean includePrivate) {
    return visits.summary(CurrentUser.userId(), personId, condition, includePrivate);
  }

  @GetMapping("/people/{personId}/vaccinations")
  public List<ObjectNode> vaccinations(@PathVariable UUID personId) {
    return history.list(CurrentUser.userId(), personId, VitaTable.VACCINATION);
  }

  @PostMapping("/people/{personId}/vaccinations")
  public ObjectNode addVaccination(@PathVariable UUID personId, @RequestBody JsonNode body) {
    return history.create(CurrentUser.userId(), personId, VitaTable.VACCINATION, body);
  }

  @PutMapping("/people/{personId}/vaccinations/{id}")
  public ObjectNode updateVaccination(@PathVariable UUID personId, @PathVariable UUID id, @RequestBody JsonNode body) {
    return history.update(CurrentUser.userId(), personId, VitaTable.VACCINATION, id, body);
  }

  @DeleteMapping("/people/{personId}/vaccinations/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteVaccination(@PathVariable UUID personId, @PathVariable UUID id) {
    history.delete(CurrentUser.userId(), personId, VitaTable.VACCINATION, id);
  }

  @GetMapping("/people/{personId}/events")
  public List<ObjectNode> events(@PathVariable UUID personId) {
    return history.list(CurrentUser.userId(), personId, VitaTable.EVENT);
  }

  @PostMapping("/people/{personId}/events")
  public ObjectNode addEvent(@PathVariable UUID personId, @RequestBody JsonNode body) {
    return history.create(CurrentUser.userId(), personId, VitaTable.EVENT, body);
  }

  @PutMapping("/people/{personId}/events/{id}")
  public ObjectNode updateEvent(@PathVariable UUID personId, @PathVariable UUID id, @RequestBody JsonNode body) {
    return history.update(CurrentUser.userId(), personId, VitaTable.EVENT, id, body);
  }

  @DeleteMapping("/people/{personId}/events/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteEvent(@PathVariable UUID personId, @PathVariable UUID id) {
    history.delete(CurrentUser.userId(), personId, VitaTable.EVENT, id);
  }
}

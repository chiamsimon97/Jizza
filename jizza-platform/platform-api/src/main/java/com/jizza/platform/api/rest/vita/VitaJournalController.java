package com.jizza.platform.api.rest.vita;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.vita.goals.VitaGoalUseCase;
import com.jizza.platform.usecase.vita.journal.VitaJournalUseCase;
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
 * The journal and goals (spec 5.12, Phase 2c), behind the health switch. The journal is reached
 * only by the manager and through a share of the journal section; goals through the goals
 * section.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/vita")
@ConditionalOnProperty(name = "jizza.health.enabled", havingValue = "true")
public class VitaJournalController {

  private final VitaJournalUseCase journal;
  private final VitaGoalUseCase goals;

  public VitaJournalController(VitaJournalUseCase journal, VitaGoalUseCase goals) {
    this.journal = journal;
    this.goals = goals;
  }

  /** Entries from {@code from} to {@code to} (yyyy-mm-dd), newest first; the last 31 days by default. */
  @GetMapping("/people/{personId}/journal")
  public List<ObjectNode> journal(@PathVariable UUID personId,
                                  @RequestParam(value = "from", required = false) String from,
                                  @RequestParam(value = "to", required = false) String to) {
    return journal.list(CurrentUser.userId(), personId,
        from == null ? null : VitaJournalUseCase.date(from), to == null ? null : VitaJournalUseCase.date(to));
  }

  @GetMapping("/people/{personId}/journal/{date}")
  public ObjectNode entry(@PathVariable UUID personId, @PathVariable String date) {
    return journal.get(CurrentUser.userId(), personId, VitaJournalUseCase.date(date));
  }

  @PutMapping("/people/{personId}/journal/{date}")
  public ObjectNode putEntry(@PathVariable UUID personId, @PathVariable String date, @RequestBody JsonNode body) {
    return journal.put(CurrentUser.userId(), personId, VitaJournalUseCase.date(date), body);
  }

  @DeleteMapping("/people/{personId}/journal/{date}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteEntry(@PathVariable UUID personId, @PathVariable String date) {
    journal.delete(CurrentUser.userId(), personId, VitaJournalUseCase.date(date));
  }

  @GetMapping("/people/{personId}/goals")
  public List<ObjectNode> goals(@PathVariable UUID personId) {
    return goals.list(CurrentUser.userId(), personId);
  }

  @GetMapping("/people/{personId}/goals/{id}")
  public ObjectNode goal(@PathVariable UUID personId, @PathVariable UUID id) {
    return goals.get(CurrentUser.userId(), personId, id);
  }

  @PostMapping("/people/{personId}/goals")
  public ObjectNode addGoal(@PathVariable UUID personId, @RequestBody JsonNode body) {
    return goals.create(CurrentUser.userId(), personId, body);
  }

  @PutMapping("/people/{personId}/goals/{id}")
  public ObjectNode updateGoal(@PathVariable UUID personId, @PathVariable UUID id, @RequestBody JsonNode body) {
    return goals.update(CurrentUser.userId(), personId, id, body);
  }

  @DeleteMapping("/people/{personId}/goals/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteGoal(@PathVariable UUID personId, @PathVariable UUID id) {
    goals.delete(CurrentUser.userId(), personId, id);
  }

  /** A week's value, typed (this week unless weekStart names another Monday). */
  @PostMapping("/people/{personId}/goals/{id}/checkins")
  public ObjectNode checkIn(@PathVariable UUID personId, @PathVariable UUID id, @RequestBody JsonNode body) {
    return goals.checkIn(CurrentUser.userId(), personId, id, body);
  }

  /** The target a new goal would read, or that none is set, before anything is saved. */
  @PostMapping("/goals/preview")
  public ObjectNode preview(@RequestBody JsonNode body) {
    return goals.preview(CurrentUser.userId(), body);
  }
}

package com.jizza.platform.api.rest.vita;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.vita.insights.VitaCorrelationService;
import com.jizza.platform.usecase.vita.insights.VitaWeeklyNoteService;
import com.jizza.platform.usecase.vita.journal.VitaJournalUseCase;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The weekly note and "goes with" (spec 5.9, 5.12, Phase 4), behind the health switch. Both carry
 * "Prepared by Vita from your own records. Not medical advice."
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/vita")
@ConditionalOnProperty(name = "jizza.health.enabled", havingValue = "true")
public class VitaInsightsController {

  private final VitaWeeklyNoteService notes;
  private final VitaCorrelationService insights;

  public VitaInsightsController(VitaWeeklyNoteService notes, VitaCorrelationService insights) {
    this.notes = notes;
    this.insights = insights;
  }

  /** The week's note (this week unless {@code weekStart} names another Monday). */
  @GetMapping("/people/{personId}/weekly-note")
  public ObjectNode weeklyNote(@PathVariable UUID personId,
                               @RequestParam(value = "weekStart", required = false) String weekStart) {
    return notes.get(CurrentUser.userId(), personId, weekStart == null ? null : VitaJournalUseCase.date(weekStart));
  }

  @GetMapping("/people/{personId}/insights")
  public ObjectNode insights(@PathVariable UUID personId) {
    return insights.insights(CurrentUser.userId(), personId);
  }
}

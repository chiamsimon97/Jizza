package com.jizza.platform.usecase.vita.medicine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.scheduler.RecurrenceDays;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.records.VitaTable;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Other reminders (spec 5.2, 5.12): visits, vaccinations, policy renewals, checks due and the
 * user's own. Dose reminders are not here; they come from the dose log. A recurrence must be a
 * rule the scheduler reads.
 */
@Service
public class VitaRemindersUseCase {

  private final VitaRecordsUseCase forms;
  private final RecurrenceDays days;

  public VitaRemindersUseCase(VitaRecordsUseCase forms, RecurrenceDays days) {
    this.forms = forms;
    this.days = days;
  }

  /** The person's reminders, soonest first. */
  public List<ObjectNode> list(UUID caller, UUID personId) {
    return forms.list(caller, personId, VitaTable.REMINDER).stream()
        .sorted(Comparator.comparing((ObjectNode r) -> r.path("dueAt").asText())).toList();
  }

  @Transactional
  public ObjectNode create(UUID caller, UUID personId, JsonNode body) {
    checkRule(body);
    return forms.create(caller, personId, VitaTable.REMINDER, body, "manual");
  }

  @Transactional
  public ObjectNode update(UUID caller, UUID personId, UUID id, JsonNode body) {
    checkRule(body);
    return forms.update(caller, personId, VitaTable.REMINDER, id, body);
  }

  @Transactional
  public void delete(UUID caller, UUID personId, UUID id) {
    forms.delete(caller, personId, VitaTable.REMINDER, id);
  }

  private void checkRule(JsonNode body) {
    if (body == null || !body.hasNonNull("recurrence") || body.get("recurrence").asText().isBlank()) return;
    try {
      days.validate(body.get("recurrence").asText());
    } catch (RuntimeException e) {
      throw new ValidationException("recurrence must be a rule such as FREQ=YEARLY or FREQ=MONTHLY;INTERVAL=3.");
    }
  }
}

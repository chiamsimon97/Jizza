package com.jizza.platform.api.rest.vita;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.util.AuthenticatedOnly;
import com.jizza.engine.security.session.CurrentUser;
import com.jizza.platform.usecase.vita.medicine.VitaDoseUseCase;
import com.jizza.platform.usecase.vita.medicine.VitaMedicineUseCase;
import com.jizza.platform.usecase.vita.medicine.VitaRemindersUseCase;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vita's medicines, doses and other reminders (spec 5.12, Phase 1a part 2), behind the same switch
 * as the rest of {@code /api/vita}. Every route reaches only the caller's own household; another
 * account's person, medicine or dose reads as not found.
 */
@RestController
@AuthenticatedOnly
@RequestMapping("/api/vita")
@ConditionalOnProperty(name = "jizza.health.enabled", havingValue = "true")
public class VitaMedicineController {

  private final VitaMedicineUseCase medicines;
  private final VitaDoseUseCase doses;
  private final VitaRemindersUseCase reminders;

  public VitaMedicineController(VitaMedicineUseCase medicines, VitaDoseUseCase doses, VitaRemindersUseCase reminders) {
    this.medicines = medicines;
    this.doses = doses;
    this.reminders = reminders;
  }

  // ── Medicines ───────────────────────────────────────────────────────────────

  @GetMapping("/people/{personId}/medicines")
  public List<ObjectNode> medicines(@PathVariable UUID personId) {
    return medicines.list(CurrentUser.userId(), personId);
  }

  @PostMapping("/people/{personId}/medicines")
  public ObjectNode addMedicine(@PathVariable UUID personId, @RequestBody JsonNode body) {
    return medicines.create(CurrentUser.userId(), personId, body, "manual");
  }

  @GetMapping("/people/{personId}/medicines/{id}")
  public ObjectNode medicine(@PathVariable UUID personId, @PathVariable UUID id) {
    return medicines.get(CurrentUser.userId(), personId, id);
  }

  @PutMapping("/people/{personId}/medicines/{id}")
  public ObjectNode updateMedicine(@PathVariable UUID personId, @PathVariable UUID id, @RequestBody JsonNode body) {
    return medicines.update(CurrentUser.userId(), personId, id, body);
  }

  /** A dose or time change from a date, with the doctor. */
  @PostMapping("/people/{personId}/medicines/{id}/change")
  public ObjectNode change(@PathVariable UUID personId, @PathVariable UUID id, @RequestBody JsonNode body) {
    return medicines.change(CurrentUser.userId(), personId, id, body);
  }

  @PostMapping("/people/{personId}/medicines/{id}/pause")
  public ObjectNode pause(@PathVariable UUID personId, @PathVariable UUID id, @RequestBody(required = false) JsonNode body) {
    return medicines.pause(CurrentUser.userId(), personId, id, body);
  }

  @PostMapping("/people/{personId}/medicines/{id}/resume")
  public ObjectNode resume(@PathVariable UUID personId, @PathVariable UUID id, @RequestBody(required = false) JsonNode body) {
    return medicines.resume(CurrentUser.userId(), personId, id, body);
  }

  @PostMapping("/people/{personId}/medicines/{id}/stop")
  public ObjectNode stop(@PathVariable UUID personId, @PathVariable UUID id, @RequestBody(required = false) JsonNode body) {
    return medicines.stop(CurrentUser.userId(), personId, id, body);
  }

  @PostMapping("/people/{personId}/medicines/{id}/refill")
  public ObjectNode refill(@PathVariable UUID personId, @PathVariable UUID id, @RequestBody(required = false) JsonNode body) {
    return medicines.refill(CurrentUser.userId(), personId, id, body);
  }

  @GetMapping("/people/{personId}/medicines/{id}/events")
  public List<ObjectNode> events(@PathVariable UUID personId, @PathVariable UUID id) {
    return medicines.events(CurrentUser.userId(), personId, id);
  }

  // ── Doses ───────────────────────────────────────────────────────────────────

  /** The slots from {@code from} to {@code to} (local dates, default today), each with its doses. */
  @GetMapping("/people/{personId}/doses")
  public ObjectNode doses(@PathVariable UUID personId, @RequestParam(required = false) String from,
                          @RequestParam(required = false) String to) {
    return doses.list(CurrentUser.userId(), personId, date("from", from), date("to", to));
  }

  /** Taken, skipped or snoozed for one dose; returns its slot. */
  @PostMapping("/doses/{id}/act")
  public ObjectNode actDose(@PathVariable UUID id, @RequestBody JsonNode body) {
    return doses.actDose(CurrentUser.userId(), id, body);
  }

  /** Taken, skipped or snoozed for every open dose of a time slot; returns the slot. */
  @PostMapping("/people/{personId}/slots/{dueAt}/act")
  public ObjectNode actSlot(@PathVariable UUID personId, @PathVariable String dueAt, @RequestBody JsonNode body) {
    Instant at;
    try {
      at = Instant.parse(dueAt);
    } catch (DateTimeParseException e) {
      throw new ValidationException("dueAt must be the slot's time, such as 2026-12-01T02:30:00Z.");
    }
    return doses.actSlot(CurrentUser.userId(), personId, at, body);
  }

  @GetMapping("/people/{personId}/adherence")
  public ObjectNode adherence(@PathVariable UUID personId, @RequestParam(required = false) String from,
                              @RequestParam(required = false) String to) {
    return doses.adherence(CurrentUser.userId(), personId, date("from", from), date("to", to));
  }

  // ── Other reminders ─────────────────────────────────────────────────────────

  @GetMapping("/people/{personId}/reminders")
  public List<ObjectNode> reminders(@PathVariable UUID personId) {
    return reminders.list(CurrentUser.userId(), personId);
  }

  @PostMapping("/people/{personId}/reminders")
  public ObjectNode addReminder(@PathVariable UUID personId, @RequestBody JsonNode body) {
    return reminders.create(CurrentUser.userId(), personId, body);
  }

  @PutMapping("/people/{personId}/reminders/{id}")
  public ObjectNode updateReminder(@PathVariable UUID personId, @PathVariable UUID id, @RequestBody JsonNode body) {
    return reminders.update(CurrentUser.userId(), personId, id, body);
  }

  @DeleteMapping("/people/{personId}/reminders/{id}")
  public void removeReminder(@PathVariable UUID personId, @PathVariable UUID id) {
    reminders.delete(CurrentUser.userId(), personId, id);
  }

  private static LocalDate date(String name, String v) {
    if (v == null || v.isBlank()) return null;
    return VitaRecordsUseCase.anyDate(name, com.fasterxml.jackson.databind.node.TextNode.valueOf(v));
  }
}

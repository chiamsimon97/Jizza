package com.jizza.platform.api.rest.vita;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.device.DeviceAgentGateway.Companion;
import com.jizza.engine.api.device.PhoneAccess;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.util.PublicEndpoint;
import com.jizza.engine.api.util.TimeBuckets;
import com.jizza.engine.api.vita.VitaDoseStore;
import com.jizza.platform.api.rest.PhoneRequests;
import com.jizza.platform.usecase.vita.medicine.VitaDoseUseCase;
import com.jizza.platform.usecase.vita.medicine.VitaDoseUseCase.Act;
import com.jizza.platform.usecase.vita.medicine.VitaMedicineUseCase;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaSection;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vita's medicine reminders on the phone (spec 5.12, plan step 6d-1), in the {@code /agent/v1/me}
 * family: the device token to read, a signed request at {@link PhoneAccess#APPROVE} (the level
 * answering a notification uses) to mark. Each route calls the same use case as its
 * {@code /api/vita} twin as the phone's owner, so it reaches no more than they can on the web.
 * Sending the same action twice changes nothing the second time and answers 200.
 */
@RestController
@RequestMapping("/agent/v1/me/vita")
@ConditionalOnProperty(name = "jizza.health.enabled", havingValue = "true")
public class VitaPhoneController {

  /** {@code action}: taken, skipped or snoozed; {@code snoozeMinutes} optional. */
  public record SlotBody(UUID personId, String dueAt, String action, Integer snoozeMinutes) {}

  public record DoseBody(String action, Integer snoozeMinutes) {}

  private final PhoneRequests phones;
  private final VitaDoseUseCase doses;
  private final VitaMedicineUseCase medicines;
  private final VitaDoseStore store;
  private final VitaScopeResolver scopes;
  private final FinanceStore people;
  private final AuditLog audit;

  public VitaPhoneController(PhoneRequests phones, VitaDoseUseCase doses, VitaMedicineUseCase medicines,
                             VitaDoseStore store, VitaScopeResolver scopes, FinanceStore people, AuditLog audit) {
    this.phones = phones;
    this.doses = doses;
    this.medicines = medicines;
    this.store = store;
    this.scopes = scopes;
    this.people = people;
    this.audit = audit;
  }

  /**
   * Today's time slots for every person this user may mark (the record they are linked to as
   * themself first, then their own record and the people they manage, then records shared with
   * them with Edit on medicines), from midnight in the phone's zone {@code tz}: to the next midnight, or with
   * {@code hours} (1 to 48) to that many hours from now, for a phone that schedules local
   * reminders. Medicine names come only when the user turned names on ({@code showNames}).
   */
  @PublicEndpoint
  @GetMapping("/today")
  public ObjectNode today(@RequestParam(required = false) String tz, @RequestParam(required = false) Integer hours,
                          HttpServletRequest http) {
    Companion phone = phones.read(http);
    UUID user = phone.userId();
    ZoneId zone = TimeBuckets.zoneOf(tz);
    Instant now = Instant.now();
    Instant from = LocalDate.now(zone).atStartOfDay(zone).toInstant();
    Instant to;
    if (hours == null) {
      to = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant();
    } else {
      if (hours < 1 || hours > 48) throw new ValidationException("hours must be from 1 to 48.");
      to = now.plus(Duration.ofHours(hours));
    }
    boolean names = medicines.reminderSettings(user).showMedicineNames();
    ObjectNode out = Json.obj().put("showNames", names).put("zone", zone.getId())
        .put("from", from.toString()).put("to", to.toString());
    List<ObjectNode> slots = new ArrayList<>();
    // A record this user is linked to as themself comes first, then their own people, then the
    // records shared with them with Edit on medicines (8a).
    List<PersonScope> reach = new ArrayList<>();
    for (PersonScope shared : scopes.sharedWithMe(user)) {
      if (shared.grant().asSelf() && shared.canEdit(VitaSection.MEDICINES)) reach.add(shared);
    }
    for (var person : people.people(user)) {
      if ("huf".equals(person.kind()) || !"active".equals(person.status())) continue;
      reach.add(scopes.person(user, person.id()));
    }
    for (PersonScope shared : scopes.sharedWithMe(user)) {
      if (!shared.grant().asSelf() && shared.canEdit(VitaSection.MEDICINES)) reach.add(shared);
    }
    for (int i = 0; i < reach.size(); i++) {
      PersonScope p = reach.get(i);
      final int rank = i;
      for (ObjectNode slot : doses.slots(p, store.forPerson(p.owner(), p.personId(), from, to), names, now)) {
        slot.put("shared", p.shared()).put("asSelf", p.shared() && p.grant().asSelf()).put("rank", rank);
        slots.add(slot);
      }
    }
    slots.sort(Comparator.comparing((ObjectNode s) -> !s.path("asSelf").asBoolean())
        .thenComparing(s -> s.path("dueAt").asText())
        .thenComparing(s -> s.path("rank").asInt()));
    slots.forEach(s -> s.remove("rank"));
    ArrayNode list = out.putArray("slots");
    slots.forEach(list::add);
    return out;
  }

  /** Taken, skipped or snoozed for every open dose of one person's time slot; answers the slot. */
  @PublicEndpoint
  @PostMapping("/slots/act")
  public ObjectNode actSlot(@RequestBody(required = false) byte[] raw, HttpServletRequest http) {
    Companion phone = phones.signed(http, raw, PhoneAccess.APPROVE);
    SlotBody body = parse(raw, SlotBody.class);
    if (body == null || body.personId() == null || body.dueAt() == null) {
      throw new ValidationException("personId, dueAt and action are required.");
    }
    Instant dueAt;
    try {
      dueAt = Instant.parse(body.dueAt());
    } catch (DateTimeParseException e) {
      throw new ValidationException("dueAt must be the slot's time as sent by /today.");
    }
    PersonScope p = scopes.person(phone.userId(), body.personId(), VitaSection.MEDICINES, true).requireOpen();
    boolean names = medicines.reminderSettings(phone.userId()).showMedicineNames();
    ObjectNode slot = doses.actSlot(p, dueAt, act(body.action(), body.snoozeMinutes()), Instant.now(), names);
    record(phone, "device.phone.vita_slot", body.personId().toString(), body.action());
    return slot;
  }

  /** Taken, skipped or snoozed for one dose; answers its slot. */
  @PublicEndpoint
  @PostMapping("/doses/{id}/act")
  public ObjectNode actDose(@PathVariable UUID id, @RequestBody(required = false) byte[] raw, HttpServletRequest http) {
    Companion phone = phones.signed(http, raw, PhoneAccess.APPROVE);
    DoseBody body = parse(raw, DoseBody.class);
    if (body == null) throw new ValidationException("action is required.");
    boolean names = medicines.reminderSettings(phone.userId()).showMedicineNames();
    ObjectNode slot = doses.actDose(phone.userId(), id, act(body.action(), body.snoozeMinutes()), Instant.now(), names);
    record(phone, "device.phone.vita_dose", id.toString(), body.action());
    return slot;
  }

  private static Act act(String action, Integer snoozeMinutes) {
    ObjectNode b = Json.obj().put("action", action == null ? "" : action);
    if (snoozeMinutes != null) b.put("snoozeMinutes", snoozeMinutes);
    return Act.of(b);
  }

  private void record(Companion phone, String action, String target, String what) {
    audit.record(phone.userId(), ActorType.USER, null, action, target,
        Json.obj().put("action", what).put("deviceId", phone.deviceId().toString()));
  }

  private static <T> T parse(byte[] raw, Class<T> type) {
    if (raw == null || raw.length == 0) return null;
    try {
      return Json.mapper().readValue(raw, type);
    } catch (java.io.IOException e) {
      throw new ValidationException("The request body is not valid JSON for this call");
    }
  }

}

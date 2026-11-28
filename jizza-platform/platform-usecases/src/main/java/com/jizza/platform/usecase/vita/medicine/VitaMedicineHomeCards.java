package com.jizza.platform.usecase.vita.medicine;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaDoseStore;
import com.jizza.platform.usecase.vita.home.VitaHomeUseCase;
import com.jizza.platform.usecase.vita.records.VitaTable;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Home's medicine cards (spec 5.12 {@code GET {p}/home}): today's time slots with their doses and
 * the next other reminders; and on a condition page the medicines linked to that condition, with
 * last week's adherence.
 */
@Component
public class VitaMedicineHomeCards implements VitaHomeUseCase.HomeExtras {

  private final VitaMedicineUseCase medicines;
  private final VitaDoseUseCase doses;
  private final VitaDoseStore store;
  private final VitaRecordsUseCase forms;

  public VitaMedicineHomeCards(VitaMedicineUseCase medicines, VitaDoseUseCase doses, VitaDoseStore store,
                               VitaRecordsUseCase forms) {
    this.medicines = medicines;
    this.doses = doses;
    this.store = store;
    this.forms = forms;
  }

  @Override
  public void addToHome(PersonScope p, ObjectNode home) {
    if (!p.canView(com.jizza.platform.usecase.vita.sharing.VitaSection.MEDICINES)) return;
    var ps = medicines.settings(p);
    Instant now = Instant.now();
    LocalDate today = now.atZone(ps.zone()).toLocalDate();
    ArrayNode slots = home.putArray("slots");
    doses.slots(p, store.forPerson(p.owner(), p.personId(), today.atStartOfDay(ps.zone()).toInstant(),
        today.plusDays(1).atStartOfDay(ps.zone()).toInstant()), true, now).forEach(slots::add);
    ArrayNode reminders = home.putArray("reminders");
    (p.shared() ? java.util.List.<ObjectNode>of() : forms.live(p, VitaTable.REMINDER)).stream()
        .filter(r -> "scheduled".equals(r.path("status").asText()))
        .sorted(java.util.Comparator.comparing((ObjectNode r) -> r.path("dueAt").asText()))
        .limit(5).forEach(reminders::add);
    home.put("refillsDue", (int) medicines.medicines(p).stream().filter(m -> m.hasNonNull("refillRemindedAt")).count());
  }

  @Override
  public void addToSummary(PersonScope p, ObjectNode condition, ObjectNode summary) {
    if (!p.canView(com.jizza.platform.usecase.vita.sharing.VitaSection.MEDICINES)) return;
    String id = condition.path("id").asText();
    ArrayNode list = summary.putArray("medicines");
    var ps = medicines.settings(p);
    LocalDate today = LocalDate.now(ps.zone());
    for (ObjectNode m : medicines.medicines(p)) {
      if (!id.equals(m.path("conditionId").asText()) || !Set.of("active", "paused").contains(m.path("status").asText())) continue;
      ObjectNode v = medicines.view(p, m);
      ObjectNode a = doses.adherence(p, today.minusDays(7), today.minusDays(1));
      ObjectNode row = Json.obj().put("id", m.path("id").asText()).put("name", m.path("name").asText())
          .put("strength", m.path("strength").asText(null)).put("status", m.path("status").asText());
      row.set("schedule", v.get("schedule"));
      for (var r : a.path("medicines")) {
        if (r.path("medicineId").asText().equals(m.path("id").asText())) row.set("adherence7Days", r.get("result"));
      }
      list.add(row);
    }
  }
}

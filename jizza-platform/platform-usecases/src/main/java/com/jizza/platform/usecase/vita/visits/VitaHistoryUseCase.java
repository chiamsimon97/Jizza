package com.jizza.platform.usecase.vita.visits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.error.ValidationException;
import com.jizza.platform.usecase.vita.records.VitaRecordsUseCase;
import com.jizza.platform.usecase.vita.records.VitaTable;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vaccinations, and surgeries and stays (spec 4.2, 5.12): the generic forms, with the few rules a
 * form cannot say on its own. A vaccination's next due date is only ever the one the user typed.
 */
@Service
public class VitaHistoryUseCase {

  private final VitaRecordsUseCase forms;

  public VitaHistoryUseCase(VitaRecordsUseCase forms) {
    this.forms = forms;
  }

  public List<ObjectNode> list(UUID caller, UUID personId, VitaTable t) {
    check(t);
    List<ObjectNode> rows = new java.util.ArrayList<>(forms.list(caller, personId, t));
    String date = t == VitaTable.VACCINATION ? "givenOn" : "fromDate";
    rows.sort(java.util.Comparator.comparing((ObjectNode r) -> r.path(date).asText("")).reversed());
    return rows;
  }

  @Transactional
  public ObjectNode create(UUID caller, UUID personId, VitaTable t, JsonNode body) {
    check(t);
    validate(t, body, null);
    return forms.create(caller, personId, t, body, "manual");
  }

  @Transactional
  public ObjectNode update(UUID caller, UUID personId, VitaTable t, UUID id, JsonNode body) {
    check(t);
    validate(t, body, forms.get(caller, personId, t, id));
    return forms.update(caller, personId, t, id, body);
  }

  @Transactional
  public void delete(UUID caller, UUID personId, VitaTable t, UUID id) {
    check(t);
    forms.delete(caller, personId, t, id);
  }

  private static void check(VitaTable t) {
    if (t != VitaTable.VACCINATION && t != VitaTable.EVENT) throw new IllegalArgumentException(t.name());
  }

  static void validate(VitaTable t, JsonNode body, ObjectNode current) {
    if (body == null || !body.isObject()) throw new ValidationException("Send the fields as an object.");
    if (t == VitaTable.VACCINATION) {
      if (pick(body, current, "givenOn") == null && pick(body, current, "nextDueOn") == null) {
        throw new ValidationException("Give the date it was given, or the date the next dose is due.");
      }
      if (body.hasNonNull("doseNumber") && body.get("doseNumber").isIntegralNumber() && body.get("doseNumber").asInt() < 1) {
        throw new ValidationException("doseNumber starts at 1.");
      }
    } else {
      String from = pick(body, current, "fromDate");
      String to = pick(body, current, "toDate");
      if (from != null && to != null) {
        try {
          if (LocalDate.parse(to).isBefore(LocalDate.parse(from))) throw new ValidationException("toDate cannot be before fromDate.");
        } catch (java.time.format.DateTimeParseException e) {
          // The form reports a malformed date itself.
        }
      }
    }
  }

  private static String pick(JsonNode body, ObjectNode current, String f) {
    if (body.has(f)) return body.hasNonNull(f) && !body.get(f).asText().isBlank() ? body.get(f).asText() : null;
    return current != null && current.hasNonNull(f) ? current.get(f).asText() : null;
  }
}

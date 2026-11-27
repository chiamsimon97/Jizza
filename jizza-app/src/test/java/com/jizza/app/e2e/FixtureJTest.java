package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaDoseStore;
import com.jizza.platform.usecase.vita.medicine.VitaMedicineUseCase;
import com.jizza.platform.usecase.vita.medicine.VitaReminderService;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Fixture J of spec section 10: Father's status is set to deceased. His future un-acted dose rows
 * are removed, no reminder or alert is created afterwards, and his record stays readable but can
 * no longer be changed. (Shares and export arrive in Phase 2a.)
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class FixtureJTest extends E2eSupport {

  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

  @LocalServerPort private int port;
  @Autowired private VitaMedicineUseCase medicines;
  @Autowired private VitaReminderService reminders;
  @Autowired private VitaDoseStore store;
  @Autowired private VitaScopeResolver scopes;

  private static Instant ist(String local) {
    return LocalDateTime.parse(local).atZone(INDIA).toInstant();
  }

  @Test
  void aClosedRecordLosesItsFutureDosesAndNothingIsSentAfterwards() {
    ApiClient api = new ApiClient(port);
    api.prime();
    String email = "fixture-j-" + UUID.randomUUID() + "@jizza.local";
    assertThat(api.post("/api/auth/register", Map.of("email", email, "password", "Correct-Horse-92")).ok()).isTrue();
    var login = api.post("/api/auth/login", Map.of("email", email, "password", "Correct-Horse-92"));
    UUID owner = UUID.fromString(login.json().path("userId").asText());
    String father = api.post("/api/people", Map.of("displayName", "Father", "kind", "parent", "type", "individual"))
        .json().path("id").asText();
    var p = scopes.person(owner, UUID.fromString(father));
    var body = Json.obj().put("name", "telmisartan").put("startDate", "2027-06-01");
    body.putObject("schedule").putArray("times").add("08:00").add("20:00");
    medicines.create(p, body, "manual", ist("2027-06-01T00:30"));
    api.post("/api/vita/people/" + father + "/reminders", Map.of("kind", "custom", "title", "Eye check", "dueAt", "2027-06-02T09:00"));
    assertThat(store.forPerson(owner, p.personId(), ist("2027-06-01T00:00"), ist("2027-06-04T00:00"))).hasSize(4);

    var closed = api.patch("/api/people/" + father + "/status", Map.of("status", "deceased", "on", LocalDate.now(INDIA).toString()));
    assertThat(closed.ok()).as(closed.body()).isTrue();
    assertThat(store.forPerson(owner, p.personId(), ist("2027-06-01T00:00"), ist("2027-06-04T00:00")))
        .as("future un-acted rows removed").isEmpty();
    assertThat(api.get("/api/vita/people/" + father + "/reminders").json().get(0).path("status").asText()).isEqualTo("cancelled");

    int before = api.get("/api/notifications").json().size();
    Set<UUID> me = Set.of(owner);
    reminders.nightly(ist("2027-06-01T00:45"), me);
    for (String t : new String[] {"2027-06-01T08:00", "2027-06-01T09:00", "2027-06-01T20:00", "2027-06-02T09:00"}) {
      var run = reminders.minute(ist(t), me);
      assertThat(run.slotReminders() + run.missedAlerts() + run.otherReminders() + run.expanded()).as(t).isZero();
    }
    assertThat(store.forPerson(owner, p.personId(), ist("2027-06-01T00:00"), ist("2027-06-04T00:00"))).isEmpty();
    assertThat(api.get("/api/notifications").json().size()).isEqualTo(before);

    // Read-only: the list still reads, a change is refused with the plain wording.
    assertThat(api.get("/api/vita/people/" + father + "/medicines").json()).hasSize(1);
    var add = api.post("/api/vita/people/" + father + "/medicines", Map.of("name", "x", "asNeeded", true));
    assertThat(add.status()).isEqualTo(400);
    assertThat(add.body()).contains("This record is closed");
  }
}

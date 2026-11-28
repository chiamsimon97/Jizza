package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.vita.medicine.VitaDoseUseCase;
import com.jizza.platform.usecase.vita.medicine.VitaDoseUseCase.Act;
import com.jizza.platform.usecase.vita.medicine.VitaMedicineUseCase;
import com.jizza.platform.usecase.vita.medicine.VitaReminderService;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver.PersonScope;
import com.jizza.platform.usecase.vita.sharing.VitaSection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Fixture E of the Vita spec (06, section 10), end to end with five accounts: You own a Family
 * with Brother, Mother and Father; a fifth account is outside it. Mother is linked to her own
 * record; Brother is a helper on it after the consent note. The reminder goes to Mother only, the
 * missed alert to You and Brother, the first mark wins, Brother can add but not delete or see
 * private items, revoking and removing a member end every share, and the outsider gets 404.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class VitaSharingE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";
  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

  @LocalServerPort private int port;
  @Autowired private VitaMedicineUseCase medicines;
  @Autowired private VitaDoseUseCase doses;
  @Autowired private VitaReminderService reminders;
  @Autowired private VitaScopeResolver scopes;

  private final Map<String, ApiClient> clients = new HashMap<>();
  private final Map<String, UUID> users = new HashMap<>();
  private final Map<String, String> emails = new HashMap<>();
  private UUID family;
  private UUID personal;

  @BeforeEach
  void setUp() {
    for (String who : List.of("you", "brother", "mother", "father", "outsider")) signUp(who);
    ApiClient you = clients.get("you");
    personal = UUID.fromString(you.get("/api/me/context").json().path("activeWorkspace").path("id").asText());
    assertThat(you.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
    var created = you.post("/api/workspaces/families", Map.of("name", "Our Family"));
    assertThat(created.ok()).as(created.body()).isTrue();
    family = UUID.fromString(created.json().path("id").asText());
    inFamily(() -> {
      invite("brother", "USER");
      invite("mother", "USER");
      invite("father", "ADMIN");
    });
  }

  private void signUp(String who) {
    ApiClient c = new ApiClient(port);
    c.prime();
    String email = "vita-share-" + who + "-" + UUID.randomUUID() + "@jizza.local";
    assertThat(c.post("/api/auth/register", Map.of("email", email, "password", PASSWORD)).ok()).isTrue();
    var login = c.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
    assertThat(login.ok()).isTrue();
    clients.put(who, c);
    users.put(who, UUID.fromString(login.json().path("userId").asText()));
    emails.put(who, email);
  }

  /** Runs {@code r} with You acting in the Family account, then back in your own. */
  private void inFamily(Runnable r) {
    ApiClient you = clients.get("you");
    assertThat(you.post("/api/me/active-workspace", Map.of("workspaceId", family.toString())).ok()).isTrue();
    try {
      r.run();
    } finally {
      assertThat(you.post("/api/me/active-workspace", Map.of("workspaceId", personal.toString())).ok()).isTrue();
      // Switching accounts asks for the password again before anything sensitive.
      assertThat(you.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
    }
  }

  private void invite(String who, String role) {
    ApiClient you = clients.get("you");
    assertThat(you.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
    var invited = you.post("/api/workspaces/current/invitations", Map.of("email", emails.get(who), "role", role));
    assertThat(invited.status()).as(invited.body()).isEqualTo(202);
    ApiClient member = clients.get(who);
    String invitation = member.get("/api/me/invitations").json().get(0).path("id").asText();
    assertThat(member.post("/api/me/invitations/" + invitation + "/accept", null).ok()).isTrue();
  }

  private static Instant ist(String local) {
    return LocalDateTime.parse(local).atZone(INDIA).toInstant();
  }

  private JsonNode ok(ApiClient.Result r) {
    assertThat(r.ok()).as(r.body()).isTrue();
    return r.json();
  }

  private String person(String name) {
    return ok(clients.get("you").post("/api/people", Map.of("displayName", name, "kind", "parent", "type", "individual")))
        .path("id").asText();
  }

  private ApiClient.Result share(String person, String who, String preset, Map<String, String> sections) {
    Map<String, Object> body = new HashMap<>();
    body.put("personId", person);
    body.put("granteeUserId", users.get(who).toString());
    if (preset != null) body.put("preset", preset);
    if (sections != null) body.put("sections", sections);
    return clients.get("you").post("/api/vita/shares", body);
  }

  private List<JsonNode> notes(String who, String text) {
    List<JsonNode> out = new java.util.ArrayList<>();
    for (JsonNode n : clients.get(who).get("/api/notifications").json()) {
      if (n.path("body").asText().contains(text)) out.add(n);
    }
    return out;
  }

  private static ObjectNode medicine(String name, boolean isPrivate, String... times) {
    ObjectNode b = Json.obj().put("name", name).put("startDate", "2026-12-01").put("private", isPrivate);
    var t = b.putObject("schedule").putArray("times");
    for (String x : times) t.add(x);
    return b;
  }

  private static Act taken(Instant at) {
    return new Act("taken", at, null, null);
  }

  @Test
  void fixtureEFamilyThePersonsOwnLoginAndAHelper() {
    ApiClient you = clients.get("you");
    ApiClient brother = clients.get("brother");
    ApiClient mother = clients.get("mother");
    ApiClient outsider = clients.get("outsider");
    String mum = person("Mother");
    String vitals = "/api/vita/people/" + mum;

    // Before the consent note, sharing Mother with Brother is refused; linking Mother to her own
    // record is allowed.
    var refused = share(mum, "brother", "caregiver", null);
    assertThat(refused.status()).isEqualTo(400);
    assertThat(refused.body()).contains("consent note");
    ok(share(mum, "mother", "self", null));
    assertThat(ok(you.get("/api/vita/shares?personId=" + mum)).path("links")).hasSize(1);
    // Only one member is linked as the person themself.
    assertThat(share(mum, "father", "self", null).status()).isEqualTo(409);

    var consent = you.post(vitals + "/consents", Map.of("scope", "view", "channel", "In person"));
    assertThat(consent.status()).as(consent.body()).isEqualTo(201);
    ok(share(mum, "brother", "caregiver", Map.of("emergency", "view")));
    JsonNode links = ok(you.get("/api/vita/shares?personId=" + mum)).path("links");
    assertThat(links).hasSize(2);
    JsonNode brotherLink = links.get(0).path("granteeUserId").asText().equals(users.get("brother").toString())
        ? links.get(0) : links.get(1);
    assertThat(brotherLink.path("sections").path("medicines").asText()).isEqualTo("edit");
    assertThat(brotherLink.path("sections").path("labs").asText()).isEqualTo("view");
    assertThat(brotherLink.path("sections").path("emergency").asText()).isEqualTo("view");
    assertThat(brotherLink.path("sections").has("journal")).isFalse();

    // Mother lands on "My medicines today"; her record reads as "You".
    assertThat(ok(mother.get("/api/vita/status")).path("openOn").asText()).isEqualTo("my-medicines");
    assertThat(ok(brother.get("/api/vita/status")).path("openOn").isNull()).isTrue();
    JsonNode mine = ok(mother.get("/api/vita/shared-with-me"));
    assertThat(mine.get(0).path("asSelf").asBoolean()).isTrue();
    assertThat(mine.get(0).path("name").asText()).isEqualTo("You");

    // Her 08:00 slot reminder goes to her phone only; she taps All taken.
    PersonScope managed = scopes.person(users.get("you"), UUID.fromString(mum));
    medicines.create(managed, medicine("metformin", false, "08:00", "20:00"), "manual", ist("2026-11-30T23:00"));
    medicines.create(managed, medicine("Private pill", true, "08:00"), "manual", ist("2026-11-30T23:00"));
    medicines.expand(managed, ist("2026-12-09T07:00"));
    Set<UUID> only = Set.of(users.get("you"));
    assertThat(reminders.minute(ist("2026-12-09T08:00"), only).slotReminders()).isEqualTo(1);
    assertThat(notes("mother", "Your 8:00 medicines")).hasSize(1);
    assertThat(notes("you", "8:00 medicines")).isEmpty();
    assertThat(notes("brother", "8:00 medicines")).isEmpty();
    PersonScope asMother = scopes.person(users.get("mother"), UUID.fromString(mum), VitaSection.MEDICINES, true);
    var morning = doses.actSlot(asMother, ist("2026-12-09T08:00"), taken(ist("2026-12-09T08:05")),
        ist("2026-12-09T08:05"), true);
    assertThat(morning.path("markedByName").asText()).isEqualTo("You");
    // The private medicine is never in what Mother's login sees, though it is hers.
    assertThat(morning.toString()).doesNotContain("Private pill");
    var managerView = doses.slotView(managed, ist("2026-12-09T08:00"), ist("2026-12-09T08:06"), true);
    assertThat(managerView.path("doses").toString()).contains("\"markedByName\":\"Mother\"");

    // At 20:00 nobody acts; at 21:00 one grouped alert goes to You and Brother.
    reminders.minute(ist("2026-12-09T20:00"), only);
    var at21 = reminders.minute(ist("2026-12-09T21:00"), only);
    assertThat(at21.missedAlerts()).isEqualTo(1);
    assertThat(notes("you", "Mother · 20:00 medicines were not marked as taken.")).hasSize(1);
    assertThat(notes("brother", "Mother · 20:00 medicines were not marked as taken.")).hasSize(1);
    assertThat(notes("mother", "were not marked as taken")).isEmpty();

    // Brother marks it at 21:10: taken, late, by Brother. Your mark at 21:12 is not applied.
    PersonScope asBrother = scopes.person(users.get("brother"), UUID.fromString(mum), VitaSection.MEDICINES, true);
    var late = doses.actSlot(asBrother, ist("2026-12-09T20:00"), taken(ist("2026-12-09T21:10")),
        ist("2026-12-09T21:10"), true);
    assertThat(late.path("status").asText()).isEqualTo("taken");
    assertThat(late.path("doses").get(0).path("late").asBoolean()).isTrue();
    var yours = doses.actSlot(managed, ist("2026-12-09T20:00"), taken(ist("2026-12-09T21:12")),
        ist("2026-12-09T21:12"), true);
    assertThat(yours.path("markedBy").asText()).isEqualTo(users.get("brother").toString());
    assertThat(yours.path("markedByYou").asBoolean()).isFalse();
    assertThat(yours.path("markedByName").asText()).isEqualTo(emails.get("brother"));
    assertThat(yours.path("markedAt").asText()).isEqualTo(ist("2026-12-09T21:10").toString());

    // Brother sees Mother as shared and can add a reading; not her profile, conditions or any
    // private item; he cannot delete a reading Mother added, You can.
    JsonNode home = ok(brother.get(vitals + "/home"));
    assertThat(home.path("person").path("shared").asBoolean()).isTrue();
    var added = brother.post(vitals + "/measurements", Map.of("kind", "weight", "value", 61.5));
    assertThat(added.ok()).as(added.body()).isTrue();
    for (String path : List.of("/profile", "/conditions", "/allergies", "/vaccinations", "/access-list",
        "/consents")) {
      assertThat(brother.get(vitals + path).status()).as(path).isEqualTo(404);
    }
    assertThat(ok(brother.get(vitals + "/emergency-card")).toString()).doesNotContain("Private pill");
    assertThat(ok(brother.get(vitals + "/medicines")).toString()).contains("metformin").doesNotContain("Private pill");
    String privateId = null;
    for (JsonNode m : ok(you.get(vitals + "/medicines"))) {
      if (m.path("name").asText().equals("Private pill")) privateId = m.path("id").asText();
    }
    assertThat(brother.get(vitals + "/medicines/" + privateId).status()).isEqualTo(404);
    var mothersReading = mother.post(vitals + "/measurements", Map.of("kind", "weight", "value", 61.0));
    assertThat(mothersReading.ok()).as(mothersReading.body()).isTrue();
    String readingId = mothersReading.json().path("id").asText();
    assertThat(brother.delete(vitals + "/measurements/" + readingId).status()).isEqualTo(404);
    assertThat(mother.delete(vitals + "/measurements/" + readingId).status()).isEqualTo(404);
    assertThat(you.delete(vitals + "/measurements/" + readingId).ok()).isTrue();
    // Brother can correct his own entry but not Mother's.
    String brothersId = added.json().path("id").asText();
    assertThat(brother.put(vitals + "/measurements/" + brothersId, Map.of("value", 61.4)).ok()).isTrue();

    // Notes between helpers, by name.
    var note = brother.post(vitals + "/care-notes", Map.of("text", "Bought a new strip pack."));
    assertThat(note.status()).as(note.body()).isEqualTo(201);
    JsonNode careNotes = ok(you.get(vitals + "/care-notes"));
    assertThat(careNotes.get(0).path("writtenByName").asText()).isEqualTo(emails.get("brother"));

    // The access list shows who sees what and since when.
    JsonNode access = ok(you.get(vitals + "/access-list"));
    assertThat(access.path("entries").toString()).contains(emails.get("brother")).contains(emails.get("mother"));
    assertThat(access.path("note").asText()).contains("cannot be recalled");

    // You revoke Brother: on his next request Mother is gone, and her reminders leave his inbox.
    assertThat(you.delete("/api/vita/shares?personId=" + mum + "&granteeUserId=" + users.get("brother")).status())
        .isEqualTo(204);
    assertThat(brother.get(vitals + "/home").status()).isEqualTo(404);
    assertThat(brother.get(vitals + "/medicines").status()).isEqualTo(404);
    assertThat(ok(brother.get("/api/vita/shared-with-me"))).isEmpty();
    assertThat(notes("brother", "Mother · 20:00")).allMatch(n -> !n.path("archivedAt").isNull());
    // What he wrote stays with the manager.
    assertThat(ok(you.get(vitals + "/measurements")).toString()).contains(brothersId);

    // A fifth account outside the Family gets 404 on every route, and cannot be shared with.
    for (String path : List.of("/home", "/medicines", "/measurements", "/emergency-card", "/documents", "/labs",
        "/profile", "/care-notes", "/doctors", "/access-list", "/doses")) {
      assertThat(outsider.get(vitals + path).status()).as(path).isEqualTo(404);
    }
    assertThat(outsider.post(vitals + "/measurements", Map.of("kind", "weight", "value", 60)).status()).isEqualTo(404);
    assertThat(share(mum, "outsider", "emergency", null).status()).isEqualTo(404);

    // A member removed from the Family loses every share.
    ok(share(mum, "father", "everything_except_journal", null));
    assertThat(clients.get("father").get(vitals + "/home").ok()).isTrue();
    assertThat(clients.get("father").get(vitals + "/journal").status()).isEqualTo(404);
    inFamily(() -> {
      String memberId = null;
      for (JsonNode m : ok(you.get("/api/workspaces/current/members"))) {
        if (m.path("userId").asText().equals(users.get("father").toString())) memberId = m.path("id").asText();
      }
      assertThat(you.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
      assertThat(you.delete("/api/workspaces/current/members/" + memberId).ok()).isTrue();
    });
    assertThat(clients.get("father").get(vitals + "/home").status()).isEqualTo(404);
    assertThat(ok(you.get(vitals + "/access-list")).path("entries").toString()).contains("\"endedReason\":\"removed\"");
  }

  @Test
  void eachWayAShareEnds() {
    ApiClient you = clients.get("you");
    String mum = person("Mother");
    String vitals = "/api/vita/people/" + mum;
    you.post(vitals + "/consents", Map.of("scope", "view"));

    // Revoked, one section at a time.
    ok(share(mum, "brother", "caregiver", null));
    String labs = null;
    for (JsonNode s : ok(you.get("/api/vita/shares?personId=" + mum)).path("links").get(0).path("shares")) {
      if (s.path("section").asText().equals("labs")) labs = s.path("id").asText();
    }
    assertThat(you.delete("/api/vita/shares/" + labs).status()).isEqualTo(204);
    assertThat(clients.get("brother").get(vitals + "/labs").status()).isEqualTo(404);
    assertThat(clients.get("brother").get(vitals + "/medicines").ok()).isTrue();

    // Replaced: a new access for the same section replaces the old share.
    ok(share(mum, "brother", "caregiver", Map.of("medicines", "view", "labs", "none")));
    assertThat(clients.get("brother").get(vitals + "/medicines").ok()).isTrue();
    // A new matrix for the same member ends what it leaves out.
    ok(share(mum, "brother", "emergency", null));
    assertThat(clients.get("brother").get(vitals + "/medicines").status()).isEqualTo(404);
    assertThat(clients.get("brother").get(vitals + "/emergency-card").ok()).isTrue();

    // The consent note is withdrawn: every helper's share ends.
    String consentId = ok(you.get(vitals + "/consents")).get(0).path("id").asText();
    assertThat(you.delete(vitals + "/consents/" + consentId).ok()).isTrue();
    assertThat(clients.get("brother").get(vitals + "/emergency-card").status()).isEqualTo(404);

    // The member leaves the Family.
    you.post(vitals + "/consents", Map.of("scope", "view"));
    ok(share(mum, "brother", "emergency", null));
    ApiClient brother = clients.get("brother");
    assertThat(brother.post("/api/me/active-workspace", Map.of("workspaceId", family.toString())).ok()).isTrue();
    assertThat(brother.delete("/api/workspaces/current/membership").ok()).isTrue();
    assertThat(brother.get(vitals + "/emergency-card").status()).isEqualTo(404);

    // The record closes.
    ok(share(mum, "mother", "self", null));
    assertThat(clients.get("mother").get(vitals + "/home").ok()).isTrue();
    assertThat(you.patch("/api/people/" + mum + "/status", Map.of("status", "archived", "on",
        java.time.LocalDate.now(INDIA).toString())).ok()).isTrue();
    assertThat(clients.get("mother").get(vitals + "/home").status()).isEqualTo(404);

    String reasons = ok(you.get(vitals + "/access-list")).path("entries").toString();
    assertThat(reasons).contains("\"endedReason\":\"revoked\"").contains("\"endedReason\":\"replaced\"")
        .contains("\"endedReason\":\"left_family\"").contains("\"endedReason\":\"record_closed\"");
    assertThat(ok(you.get(vitals + "/access-list")).path("entries")).allMatch(e -> !e.path("active").asBoolean());
  }

  @Test
  void grantingEditNeedsAFreshStepUpAndOnlyTheManagerShares() {
    // Brother has not stepped up: View is fine, Edit is refused until he does.
    ApiClient brother = clients.get("brother");
    String wife = ok(brother.post("/api/people", Map.of("displayName", "Wife", "kind", "spouse", "type", "individual")))
        .path("id").asText();
    brother.post("/api/vita/people/" + wife + "/consents", Map.of("scope", "view"));
    Map<String, Object> view = Map.of("personId", wife, "granteeUserId", users.get("you").toString(), "preset", "emergency");
    assertThat(brother.post("/api/vita/shares", view).ok()).isTrue();
    Map<String, Object> edit = Map.of("personId", wife, "granteeUserId", users.get("you").toString(), "preset", "caregiver");
    var refused = brother.post("/api/vita/shares", edit);
    assertThat(refused.status()).isEqualTo(403);
    assertThat(refused.problemType()).endsWith("step-up-required");
    assertThat(brother.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
    assertThat(brother.post("/api/vita/shares", edit).ok()).isTrue();

    // You, with Edit on medicines, still cannot share Wife on, export or delete her record.
    String path = "/api/vita/people/" + wife;
    ApiClient you = clients.get("you");
    assertThat(you.get(path + "/medicines").ok()).isTrue();
    assertThat(you.post("/api/vita/shares", Map.of("personId", wife, "granteeUserId", users.get("mother").toString(),
        "preset", "emergency")).status()).isEqualTo(404);
    assertThat(you.get(path + "/export").status()).isEqualTo(404);
    assertThat(you.delete(path + "/record").status()).isEqualTo(404);
  }

  @Test
  void anOrganizationsMembersCannotShareWithEachOther() {
    ApiClient boss = new ApiClient(port);
    boss.prime();
    String bossEmail = "vita-share-org-" + UUID.randomUUID() + "@jizza.local";
    assertThat(boss.post("/api/auth/register", Map.of("email", bossEmail, "password", PASSWORD,
        "accountType", "ORGANIZATION", "organizationName", "Acme Health")).ok()).isTrue();
    var bossLogin = boss.post("/api/auth/login", Map.of("email", bossEmail, "password", PASSWORD));
    assertThat(bossLogin.ok()).isTrue();
    UUID bossId = UUID.fromString(bossLogin.json().path("userId").asText());
    assertThat(boss.post("/api/auth/step-up", Map.of("password", PASSWORD)).ok()).isTrue();
    ApiClient brother = clients.get("brother");
    assertThat(boss.post("/api/workspaces/current/invitations", Map.of("email", emails.get("brother"), "role", "USER"))
        .status()).isEqualTo(202);
    String invitation = brother.get("/api/me/invitations").json().get(0).path("id").asText();
    assertThat(brother.post("/api/me/invitations/" + invitation + "/accept", null).ok()).isTrue();

    // Brother and the Organization's owner share no Family: his grant to them is refused.
    String self = ok(brother.get("/api/people")).get(0).path("id").asText();
    var refused = brother.post("/api/vita/shares", Map.of("personId", self, "granteeUserId", bossId.toString(),
        "preset", "emergency"));
    assertThat(refused.status()).as(refused.body()).isEqualTo(404);
  }

  @Test
  void theManagerExportsAndDeletesOnePersonsRecord() {
    ApiClient you = clients.get("you");
    String dad = person("Father");
    String path = "/api/vita/people/" + dad;
    ok(you.post(path + "/measurements", Map.of("kind", "weight", "value", 72)));
    ok(you.post(path + "/allergies", Map.of("substance", "Penicillin")));
    you.post(path + "/consents", Map.of("scope", "view"));
    ok(share(dad, "brother", "emergency", null));

    JsonNode export = ok(you.get(path + "/export"));
    assertThat(export.path("person").path("displayName").asText()).isEqualTo("Father");
    assertThat(export.path("records").path("vita_measurement")).hasSize(1);
    assertThat(export.path("records").path("vita_allergy")).hasSize(1);
    assertThat(export.path("accessList")).hasSize(1);
    assertThat(export.toString()).doesNotContain("passwordEnc").doesNotContain("abhaNumberEnc");

    JsonNode deleted = ok(you.delete(path + "/record"));
    assertThat(deleted.path("deleted").asInt()).isGreaterThanOrEqualTo(2);
    assertThat(ok(you.get(path + "/measurements"))).isEmpty();
    assertThat(ok(you.get(path + "/allergies"))).isEmpty();
    assertThat(clients.get("brother").get(path + "/emergency-card").status()).isEqualTo(404);
    // The person stays in the household list, which Kai shares.
    assertThat(ok(you.get("/api/people")).toString()).contains("Father");
  }
}

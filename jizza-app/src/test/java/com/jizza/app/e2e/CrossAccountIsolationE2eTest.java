package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Two separate accounts on one server. Everything one of them owns is unreachable to the other
 * through every route that takes its id, read or write, and the owner's data is still intact
 * after the other account has tried each of them.
 *
 * <p>The routes come from Spring's handler mapping, so a route added later is swept as well.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class CrossAccountIsolationE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";
  private static final Pattern VAR = Pattern.compile("\\{([^}]+)}");

  @LocalServerPort private int port;
  @Autowired @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;
  @Autowired private E2eSupport.ScriptedLlmClient model;

  private ApiClient owner;
  private ApiClient other;
  /** Route prefix (up to the first id) to the owner's id for it. */
  private final Map<String, String> owned = new LinkedHashMap<>();
  /** Vita's rows under the owner's person: section to row id. */
  private final Map<String, String> vitaRows = new LinkedHashMap<>();

  @BeforeEach
  void setUp() throws Exception {
    model.reset();
    owner = account();
    other = account();

    JsonNode agents = owner.get("/api/agents").json();
    String agentId = agents.get(0).path("id").asText();
    owned.put("/api/agents/", agentId);

    var conversation = owner.post("/api/conversations", Map.of("title", "Owner's private chat"));
    assertThat(conversation.ok()).as(conversation.body()).isTrue();
    owned.put("/api/conversations/", conversation.json().path("id").asText());

    var workflow = owner.post("/api/workflows", Map.of("name", "Owner's workflow", "graph",
        com.jizza.engine.api.util.Json.read("""
            {"nodes":[{"id":"start","type":"TRIGGER"},{"id":"end","type":"END"}],"edges":[{"from":"start","to":"end"}]}""")));
    assertThat(workflow.ok()).as(workflow.body()).isTrue();
    owned.put("/api/workflows/", workflow.json().path("id").asText());

    var task = owner.post("/api/tasks", Map.of("title", "Owner's task", "agentId", agentId,
        "scheduleType", "ONE_TIME", "runAt", Instant.now().plusSeconds(86_400).toString()));
    assertThat(task.ok()).as(task.body()).isTrue();
    owned.put("/api/tasks/", task.json().path("id").asText());

    var memory = owner.post("/api/memory", Map.of("category", "PREFERENCE",
        "content", "Owner prefers tea"));
    assertThat(memory.ok()).as(memory.body()).isTrue();
    owned.put("/api/memory/", memory.json().path("memory").path("id").asText());

    var subject = owner.post("/api/memory/subjects", Map.of("kind", "PERSON", "name", "Sam"));
    assertThat(subject.ok()).as(subject.body()).isTrue();
    owned.put("/api/memory/subjects/", subject.json().path("id").asText());

    var service = owner.post("/api/services", Map.of("name", "Owner's service",
        "baseUrl", "https://api.example.com", "authScheme", "API_KEY"));
    assertThat(service.ok()).as(service.body()).isTrue();
    owned.put("/api/services/", service.json().path("id").asText());

    // Kai Portfolio: someone the owner manages, and that person's consent note.
    var person = owner.post("/api/finance/people", Map.of("displayName", "Owner's mother",
        "kind", "parent", "type", "individual"));
    assertThat(person.ok()).as(person.body()).isTrue();
    owned.put("/api/finance/people/", person.json().path("id").asText());
    var consent = owner.post("/api/finance/people/" + person.json().path("id").asText()
        + "/consents", Map.of("scope", "manage"));
    assertThat(consent.ok()).as(consent.body()).isTrue();
    owned.put("/api/finance/consents/", consent.json().path("id").asText());
    owned.put("/api/finance/estate/", person.json().path("id").asText());
    // Phase 1a: an account, an asset, a loan (high interest, so the plan holds an action),
    // money lent and a policy.
    var account = owner.post("/api/finance/accounts", Map.of("kind", "savings", "nickname", "Owner's savings"));
    assertThat(account.ok()).as(account.body()).isTrue();
    owned.put("/api/finance/accounts/", account.json().path("id").asText());
    var asset = owner.post("/api/finance/assets", Map.of("kind", "fd", "value", 100000,
        "accountId", account.json().path("id").asText()));
    assertThat(asset.ok()).as(asset.body()).isTrue();
    owned.put("/api/finance/assets/", asset.json().path("id").asText());
    var loan = owner.post("/api/finance/loans", Map.of("kind", "PERSONAL", "outstanding", 150000,
        "rateInput", "14%", "emiOrInterest", 8000));
    assertThat(loan.ok()).as(loan.body()).isTrue();
    owned.put("/api/finance/loans/", loan.json().path("id").asText());
    var lent = owner.post("/api/finance/loans-given", Map.of("borrowerName", "Owner's friend", "amount", 20000));
    assertThat(lent.ok()).as(lent.body()).isTrue();
    owned.put("/api/finance/loans-given/", lent.json().path("id").asText());
    var policy = owner.post("/api/finance/policies", Map.of("kind", "term", "sumAssured", 5000000));
    assertThat(policy.ok()).as(policy.body()).isTrue();
    owned.put("/api/finance/policies/", policy.json().path("id").asText());
    var actions = owner.get("/api/finance/actions");
    assertThat(actions.json()).as(actions.body()).isNotEmpty();
    owned.put("/api/finance/actions/", actions.json().get(0).path("id").asText());
    var goal = owner.post("/api/finance/goals", Map.of("type", "travel", "name", "Owner's trip",
        "targetAmountToday", 200000, "targetDate", "2028-12-01"));
    assertThat(goal.ok()).as(goal.body()).isTrue();
    owned.put("/api/finance/goals/", goal.json().path("goal").path("id").asText());
    owned.put("/api/finance/risk/", person.json().path("id").asText());
    owned.put("/api/finance/tax/", person.json().path("id").asText());
    var batch = owner.upload("/api/finance/imports", Map.of("kind", "bank_csv",
        "accountId", owned.get("/api/finance/accounts/")), "statement.csv", "text/csv",
        "Date,Description,Debit,Credit,Balance\n01/04/2025,Owner's rent,1000,,9000\n"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(batch.ok()).as(batch.body()).isTrue();
    owned.put("/api/finance/imports/", batch.json().path("id").asText());
    var committed = owner.post("/api/finance/imports/" + batch.json().path("id").asText() + "/commit", Map.of());
    assertThat(committed.ok()).as(committed.body()).isTrue();
    owned.put("/api/finance/transactions/", owner.get("/api/finance/transactions").json().get(0).path("id").asText());

    // Phase 2: a holding with its lot and earmark, a tree, a rule template and a list item.
    var demat = owner.post("/api/finance/accounts", Map.of("kind", "demat", "nickname", "Owner's demat"));
    assertThat(demat.ok()).as(demat.body()).isTrue();
    owned.put("/api/finance/holdings/", demat.json().path("id").asText());
    String isin = "INX" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase() + "1";
    var instrument = owner.post("/api/finance/instruments", Map.of("name", "Owner's fund " + isin, "isin", isin,
        "type", "mf", "assetClass", "equity"));
    assertThat(instrument.ok()).as(instrument.body()).isTrue();
    owned.put("/api/finance/instruments/", instrument.json().path("id").asText());
    var bought = owner.post("/api/finance/holdings/purchases", Map.of("accountId", demat.json().path("id").asText(),
        "instrumentId", instrument.json().path("id").asText(), "date", "2025-01-10", "units", 10, "pricePerUnit", 100));
    assertThat(bought.ok()).as(bought.body()).isTrue();
    var earmarked = owner.put("/api/finance/earmarks", Map.of("accountId", demat.json().path("id").asText(),
        "instrumentId", instrument.json().path("id").asText(),
        "split", List.of(Map.of("goalId", owned.get("/api/finance/goals/"), "units", 10))));
    assertThat(earmarked.ok()).as(earmarked.body()).isTrue();
    owned.put("/api/finance/tree/", owned.get("/api/finance/goals/"));
    var rule = owner.put("/api/finance/rules", Map.of("level", "goal", "goalId", owned.get("/api/finance/goals/"),
        "field", "stop_loss", "state", "set", "params", Map.of("kind", "fixed", "pct", 10)));
    assertThat(rule.ok()).as(rule.body()).isTrue();
    var template = owner.post("/api/finance/rule-templates", Map.of("name", "Owner's template", "level", "goal",
        "goalId", owned.get("/api/finance/goals/")));
    assertThat(template.ok()).as(template.body()).isTrue();
    owned.put("/api/finance/rule-templates/", template.json().path("id").asText());
    var item = owner.post("/api/finance/lists", Map.of("listType", "watch",
        "instrumentId", instrument.json().path("id").asText()));
    assertThat(item.ok()).as(item.body()).isTrue();
    owned.put("/api/finance/lists/", item.json().path("id").asText());

    // Vita Health: the same person's health record, and one of each row the person has.
    String personId = person.json().path("id").asText();
    owned.put("/api/people/", personId);
    owned.put("/api/vita/people/", personId);
    var profile = owner.put("/api/vita/people/" + personId + "/profile",
        Map.of("bloodGroup", "o_pos", "abhaNumber", "12345678901234"));
    assertThat(profile.ok()).as(profile.body()).isTrue();
    var doctor = owner.post("/api/vita/doctors", Map.of("name", "Owner's doctor", "phone", "+91 80 0000 0000"));
    assertThat(doctor.ok()).as(doctor.body()).isTrue();
    String doctorId = doctor.json().path("id").asText();
    owned.put("/api/vita/doctors/", doctorId);
    var range = owner.put("/api/vita/people/" + personId + "/ranges", Map.of("kind", "bp", "setting", "home",
        "high", 140, "high2", 90, "highInclusive", false, "setBy", "doctor", "doctorId", doctorId));
    assertThat(range.ok()).as(range.body()).isTrue();
    vitaRows.put("ranges", range.json().path("id").asText());
    for (var form : List.of(
        Map.entry("visits", Map.of("doctorId", doctorId, "scheduledAt", "2030-01-05T10:30", "reason", "Review")),
        Map.entry("emergency-contacts", Map.of("name", "Owner's brother", "phone", "+91 98000 00000")),
        Map.entry("conditions", Map.of("name", "Owner's mother's condition", "template", "diabetes_t2")),
        Map.entry("allergies", Map.of("substance", "Owner's mother's allergy")),
        Map.entry("measurements", Map.of("kind", "weight", "value", 61.5)))) {
      var row = owner.post("/api/vita/people/" + personId + "/" + form.getKey(), form.getValue());
      assertThat(row.ok()).as(row.body()).isTrue();
      vitaRows.put(form.getKey(), row.json().path("id").asText());
    }
    // A medicine taken every two hours, so it has doses today and tomorrow; and another reminder.
    var medicine = owner.post("/api/vita/people/" + personId + "/medicines", Map.of("name", "Owner's medicine",
        "trackStock", true, "stockUnits", 60, "unitsPerPack", 30, "schedule", Map.of("times", List.of("00:00", "02:00",
            "04:00", "06:00", "08:00", "10:00", "12:00", "14:00", "16:00", "18:00", "20:00", "22:00"))));
    assertThat(medicine.ok()).as(medicine.body()).isTrue();
    vitaRows.put("medicines", medicine.json().path("id").asText());
    var reminder = owner.post("/api/vita/people/" + personId + "/reminders", Map.of("kind", "custom",
        "title", "Owner's reminder", "dueAt", "2030-01-01T09:00"));
    assertThat(reminder.ok()).as(reminder.body()).isTrue();
    vitaRows.put("reminders", reminder.json().path("id").asText());
    var today = owner.get("/api/vita/people/" + personId + "/doses?from=" + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
        + "&to=" + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(1));
    assertThat(today.ok()).as(today.body()).isTrue();
    owned.put("/api/vita/doses/", today.json().path("slots").get(0).path("doses").get(0).path("id").asText());
    var document = owner.upload("/api/vita/people/" + personId + "/documents", Map.of(), "owner.pdf", "application/pdf",
        VitaDocumentsE2eTest.resource("rx-01-fictional.pdf"));
    assertThat(document.status()).as(document.body()).isEqualTo(201);
    owned.put("/api/vita/documents/", document.json().path("id").asText());
    // A second document read and confirmed into a lab report, and a check.
    var labDoc = owner.upload("/api/vita/people/" + personId + "/documents", Map.of(), "lab.pdf", "application/pdf",
        VitaDocumentsE2eTest.pdf(List.of(VitaDocumentsE2eTest.FIXTURE_D), null));
    assertThat(labDoc.status()).as(labDoc.body()).isEqualTo(201);
    model.scriptFor(com.jizza.platform.usecase.vita.documents.VitaExtractionService.READER_NAME,
        com.jizza.engine.api.llm.LlmResponse.text(VitaDocumentsE2eTest.FIXTURE_D_REPLY));
    String labDocId = labDoc.json().path("id").asText();
    var read = owner.post("/api/vita/documents/" + labDocId + "/extract", Map.of());
    assertThat(read.ok()).as(read.body()).isTrue();
    var confirmed = owner.post("/api/vita/documents/" + labDocId + "/confirm", Map.of("patientConfirmed", true));
    assertThat(confirmed.ok()).as(confirmed.body()).isTrue();
    owned.put("/api/vita/labs/", confirmed.json().path("saved").path("labReport").asText());
    var check = owner.post("/api/vita/people/" + personId + "/checks", Map.of("kind", "eye", "doneOn", "2026-05-01"));
    assertThat(check.ok()).as(check.body()).isTrue();
    vitaRows.put("checks", check.json().path("id").asText());

    owned.forEach((prefix, id) -> assertThat(id).as("owner's id under %s", prefix).isNotBlank());
  }

  private ApiClient account() {
    String email = "isolation-" + UUID.randomUUID() + "@jizza.local";
    ApiClient client = new ApiClient(port);
    client.prime();
    assertThat(client.post("/api/auth/register", Map.of("email", email, "password", PASSWORD))
        .ok()).isTrue();
    assertThat(client.post("/api/auth/login", Map.of("email", email, "password", PASSWORD))
        .ok()).isTrue();
    return client;
  }

  /** The route with the owner's id in its first variable, or null when it has none of theirs. */
  private String withOwnersId(String pattern) {
    Matcher m = VAR.matcher(pattern);
    if (!m.find()) return null;
    String prefix = pattern.substring(0, m.start());
    String id = owned.get(prefix);
    if (id == null) return null;
    StringBuilder sb = new StringBuilder(prefix).append(id);
    String rest = pattern.substring(m.end());
    sb.append(VAR.matcher(rest).replaceAll(r -> UUID.randomUUID().toString()));
    return sb.toString();
  }

  @Test
  void noRouteLetsAnotherAccountReadOrChangeWhatTheOwnerHas() {
    List<String> leaks = new ArrayList<>();
    int tried = 0;
    for (var entry : mappings.getHandlerMethods().entrySet()) {
      var info = entry.getKey();
      for (String pattern : info.getPatternValues()) {
        String path = withOwnersId(pattern);
        if (path == null) continue;
        var methods = info.getMethodsCondition().getMethods();
        for (RequestMethod method : methods.isEmpty() ? java.util.Set.of(RequestMethod.GET) : methods) {
          tried++;
          var result = other.send(method.name(), path, method == RequestMethod.GET ? null : Map.of());
          if (result.ok()) leaks.add(method + " " + pattern + " -> " + result.status() + " "
              + (result.body().length() > 200 ? result.body().substring(0, 200) : result.body()));
        }
      }
    }
    // Vita: the owner's person with the owner's own row ids, not random ones.
    String person = owned.get("/api/vita/people/");
    for (var row : vitaRows.entrySet()) {
      String base = "/api/vita/people/" + person + "/" + row.getKey() + "/" + row.getValue();
      for (String method : List.of("PUT", "DELETE")) {
        tried++;
        var result = other.send(method, base, method.equals("PUT") ? Map.of("name", "x") : null);
        if (result.ok()) leaks.add(method + " " + base + " -> " + result.status());
      }
      tried++;
      var moved = other.send("POST", base + "/move", Map.of("toPersonId", person));
      if (row.getKey().equals("measurements") && moved.ok()) leaks.add("POST " + base + "/move");
      tried++;
      var flagged = other.send("POST", base + "/red-flag", Map.of("right", true));
      if (flagged.ok()) leaks.add("POST " + base + "/red-flag");
      tried++;
      var summary = other.send("GET", base + "/summary", null);
      if (summary.ok()) leaks.add("GET " + base + "/summary");
      for (String step : List.of("change", "pause", "resume", "stop", "refill")) {
        tried++;
        var changed = other.send("POST", base + "/" + step, Map.of("units", 30, "strength", "x"));
        if (changed.ok()) leaks.add("POST " + base + "/" + step);
      }
      tried++;
      var events = other.send("GET", base + "/events", null);
      if (events.ok()) leaks.add("GET " + base + "/events");
    }
    tried++;
    var dose = other.send("POST", "/api/vita/doses/" + owned.get("/api/vita/doses/") + "/act", Map.of("action", "taken"));
    if (dose.ok()) leaks.add("POST /api/vita/doses/{owner's dose}/act");
    assertThat(tried).as("routes swept").isGreaterThan(60);
    assertThat(leaks).as("routes another account could use on the owner's data").isEmpty();

    // And nothing the other account tried changed anything of the owner's.
    for (var e : owned.entrySet()) {
      String path = e.getKey() + e.getValue();
      if (e.getKey().equals("/api/memory/subjects/")) continue; // no single-subject GET
      if (e.getKey().equals("/api/vita/people/")) continue; // checked below, section by section
      if (e.getKey().equals("/api/vita/doses/")) continue; // read through the dose list
      if (e.getKey().equals("/api/finance/consents/")) continue; // read through the person
      if (e.getKey().equals("/api/finance/actions/")) continue; // read through the list
      if (e.getKey().equals("/api/finance/transactions/")) continue; // read through the list
      if (java.util.Set.of("/api/finance/holdings/", "/api/finance/instruments/", "/api/finance/tree/",
          "/api/finance/rule-templates/", "/api/finance/lists/").contains(e.getKey())) continue; // no single GET
      var still = owner.get(path);
      assertThat(still.ok()).as("owner still has %s: %s", path, still.body()).isTrue();
    }
    String vitaPerson = owned.get("/api/vita/people/");
    assertThat(owner.get("/api/vita/people/" + vitaPerson + "/profile").json().path("profile")
        .path("bloodGroup").asText()).isEqualTo("o_pos");
    for (var row : vitaRows.entrySet()) {
      String list = owner.get("/api/vita/people/" + vitaPerson + "/" + row.getKey()).body();
      assertThat(list).as("owner still has their %s", row.getKey()).contains(row.getValue());
    }
  }

  /**
   * The other way in: not the owner's id in the path, but in a request body, naming something of
   * the owner's for the caller's own new thing to use.
   */
  @Test
  void anotherAccountsIdsInARequestBodyAreRefused() {
    String ownersAgent = owned.get("/api/agents/");
    String ownersWorkflow = owned.get("/api/workflows/");
    String ownersMemory = owned.get("/api/memory/");
    String othersAgent = other.get("/api/agents").json().get(0).path("id").asText();

    var task = other.post("/api/tasks", Map.of("title", "Borrowed agent", "agentId", ownersAgent,
        "scheduleType", "ONE_TIME", "runAt", Instant.now().plusSeconds(86_400).toString()));
    assertThat(task.status()).as("task with the owner's agent: %s", task.body()).isBetween(400, 404);

    var rule = other.put("/api/guardrails/rules", Map.of("agentId", ownersAgent,
        "toolRef", "anything", "rule", "ALLOW", "precedence", 1));
    assertThat(rule.ok()).as("guardrail on the owner's agent: %s", rule.body()).isFalse();

    var simulate = other.post("/api/guardrails/simulate",
        Map.of("agentId", ownersAgent, "toolRef", "anything"));
    assertThat(simulate.ok()).as("simulating the owner's agent: %s", simulate.body()).isFalse();

    var workflow = other.post("/api/workflows", Map.of("name", "Borrowed", "owningAgentId",
        ownersAgent, "graph", com.jizza.engine.api.util.Json.read("""
            {"nodes":[{"id":"start","type":"TRIGGER"},{"id":"end","type":"END"}],
             "edges":[{"from":"start","to":"end"}]}""")));
    assertThat(workflow.ok()).as("workflow owned by the owner's agent: %s", workflow.body())
        .isFalse();

    var overwrite = other.post("/api/workflows", Map.of("id", ownersWorkflow, "name", "Hijacked",
        "graph", com.jizza.engine.api.util.Json.read("""
            {"nodes":[{"id":"start","type":"TRIGGER"},{"id":"end","type":"END"}],
             "edges":[{"from":"start","to":"end"}]}""")));
    assertThat(overwrite.ok()).as("saving over the owner's workflow: %s", overwrite.body())
        .isFalse();
    assertThat(owner.get("/api/workflows/" + ownersWorkflow).body()).doesNotContain("Hijacked");

    var share = other.post("/api/memory/" + ownersMemory + "/share", Map.of("agentId", othersAgent));
    assertThat(share.ok()).isFalse();

    // Kai Portfolio: the caller's new rows cannot point at the owner's account, person or loan.
    var asset = other.post("/api/finance/assets", Map.of("kind", "fd", "value", 1,
        "accountId", owned.get("/api/finance/accounts/")));
    assertThat(asset.ok()).as("asset in the owner's account: %s", asset.body()).isFalse();
    var policy = other.post("/api/finance/policies", Map.of("kind", "health", "sumAssured", 1,
        "coveredPersonIds", List.of(owned.get("/api/finance/people/"))));
    assertThat(policy.ok()).as("policy covering the owner's person: %s", policy.body()).isFalse();
    var income = other.put("/api/finance/income", List.of(Map.of("personId",
        owned.get("/api/finance/people/"), "takeHomeMonthly", 1)));
    assertThat(income.ok()).as("income for the owner's person: %s", income.body()).isFalse();
    var quick = other.post("/api/finance/quick-start", Map.of("step", "income", "answers",
        Map.of("incomes", List.of(Map.of("personId", owned.get("/api/finance/people/"), "takeHomeMonthly", 1)))));
    assertThat(quick.ok()).as("quick start for the owner's person: %s", quick.body()).isFalse();
    var goal = other.post("/api/finance/goals", Map.of("type", "travel", "targetAmountToday", 1,
        "targetDate", "2030-01-01", "ownerPersonId", owned.get("/api/finance/people/")));
    assertThat(goal.ok()).as("goal for the owner's person: %s", goal.body()).isFalse();
    var buy = other.post("/api/finance/holdings/purchases", Map.of("accountId", owned.get("/api/finance/holdings/"),
        "instrumentId", owned.get("/api/finance/instruments/"), "date", "2025-01-10", "units", 1, "pricePerUnit", 1));
    assertThat(buy.ok()).as("purchase into the owner's demat: %s", buy.body()).isFalse();
    var sell = other.post("/api/finance/holdings/sales", Map.of("accountId", owned.get("/api/finance/holdings/"),
        "instrumentId", owned.get("/api/finance/instruments/"), "date", "2025-02-10", "units", 1, "proceeds", 1));
    assertThat(sell.ok()).as("sale from the owner's demat: %s", sell.body()).isFalse();
    var mark = other.put("/api/finance/earmarks", Map.of("accountId", owned.get("/api/finance/holdings/"),
        "instrumentId", owned.get("/api/finance/instruments/"), "split", List.of(Map.of("goalId",
            owned.get("/api/finance/goals/"), "units", 10))));
    assertThat(mark.ok()).as("earmarking the owner's holding: %s", mark.body()).isFalse();
    var ruleOnGoal = other.put("/api/finance/rules", Map.of("level", "goal", "goalId", owned.get("/api/finance/goals/"),
        "field", "stop_loss", "state", "off"));
    assertThat(ruleOnGoal.ok()).as("rule on the owner's goal: %s", ruleOnGoal.body()).isFalse();
    var fix = other.patch("/api/finance/instruments/" + owned.get("/api/finance/instruments/"), Map.of("capBucket", "small"));
    assertThat(fix.ok()).as("changing the owner's instrument: %s", fix.body()).isFalse();

    // Vita: the caller's Quick start and readings cannot name the owner's person.
    String ownersPerson = owned.get("/api/vita/people/");
    var quickStart = other.post("/api/vita/quick-start", Map.of("personId", ownersPerson, "step", "blood_group",
        "answers", Map.of("bloodGroup", "a_neg")));
    assertThat(quickStart.status()).as("quick start for the owner's person: %s", quickStart.body()).isEqualTo(404);
    var ownMe = other.get("/api/people").json().get(0).path("id").asText();
    var reading = other.post("/api/vita/people/" + ownMe + "/measurements", Map.of("kind", "weight", "value", 70));
    assertThat(reading.ok()).isTrue();
    var move = other.post("/api/vita/people/" + ownMe + "/measurements/" + reading.json().path("id").asText()
        + "/move", Map.of("toPersonId", ownersPerson));
    assertThat(move.status()).as("moving a reading to the owner's person: %s", move.body()).isEqualTo(404);
    assertThat(owner.get("/api/vita/people/" + ownersPerson + "/profile").json().path("profile")
        .path("bloodGroup").asText()).isEqualTo("o_pos");
  }

  @Test
  void listsOnlyEverShowTheCallersOwnThings() {
    for (String list : List.of("/api/conversations", "/api/workflows", "/api/tasks", "/api/memory",
        "/api/memory/subjects", "/api/services", "/api/agents", "/api/notifications",
        "/api/runs", "/api/activity?limit=200", "/api/procedures", "/api/finance/people",
        "/api/finance/profile", "/api/finance/config", "/api/finance/accounts",
        "/api/finance/assets", "/api/finance/loans", "/api/finance/loans-given",
        "/api/finance/policies", "/api/finance/actions", "/api/finance/health",
        "/api/finance/income", "/api/finance/budget", "/api/finance/protection",
        "/api/finance/loans/plan", "/api/finance/net-worth", "/api/finance/quick-start",
        "/api/finance/goals", "/api/finance/goal-library", "/api/finance/builder", "/api/finance/holdings",
        "/api/finance/allocation", "/api/finance/realized-gains", "/api/finance/lists",
        "/api/finance/rule-templates", "/api/finance/rules/effective", "/api/people",
        "/api/people?forHealth=true", "/api/vita/config", "/api/vita/quick-start")) {
      String body = other.get(list).body();
      for (String id : owned.values()) {
        assertThat(body).as("%s shows the owner's %s", list, id).doesNotContain(id);
      }
    }
  }
}

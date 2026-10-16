package com.jizza.engine.memory.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.enums.MemoryCategory;
import com.jizza.engine.api.enums.MemorySource;
import com.jizza.engine.api.enums.MemoryStatus;
import com.jizza.engine.api.enums.MemoryWriteOutcome;
import com.jizza.engine.api.enums.SubjectKind;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.memory.Actor;
import com.jizza.engine.api.memory.Memory;
import com.jizza.engine.api.memory.MemoryContextProvider;
import com.jizza.engine.api.memory.MemoryDraft;
import com.jizza.engine.api.memory.MemoryFilter;
import com.jizza.engine.api.memory.MemoryScope;
import com.jizza.engine.api.memory.MemoryStore;
import com.jizza.engine.api.memory.MemorySubject;
import com.jizza.engine.api.memory.MemoryWrite;
import com.jizza.engine.api.memory.SubjectDraft;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.memory.maintenance.MemoryMaintenance;
import com.jizza.engine.memory.tools.MemoryToolProvider;
import com.jizza.engine.persistence.repository.MemoryEntryRepository;
import com.jizza.engine.persistence.repository.MemoryHistoryRepository;
import com.jizza.engine.persistence.support.TestDatabase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The memory module against a real PostgreSQL with pgvector: storage, hybrid retrieval, domain
 * scoping, user isolation, duplicates, conflicts and deletion, around the scenario that motivated
 * it -- an investment decision for the user's wife.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MemoryDbTestConfig.class)
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class HybridMemoryDbTest {

  private static final String WIFE_REQUEST = "Should my wife increase her investments this year?";

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    TestDatabase.apply(registry);
  }

  @PersistenceContext private EntityManager em;
  @Autowired private MemoryStore store;
  @Autowired private MemoryContextProvider context;
  @Autowired private MemoryToolProvider tools;
  @Autowired private MemoryMaintenance maintenance;
  @Autowired private MemoryEntryRepository memoryRows;
  @Autowired private MemoryHistoryRepository historyRows;
  @Autowired private MemoryDbTestConfig.RecordingAudit audit;

  private UUID user;
  private UUID otherUser;
  private UUID jizza;
  private UUID kai;
  private UUID vita;
  private UUID zerodha;

  private MemorySubject priya;
  private Memory sip;
  private Memory risk;
  private Memory allergy;
  private Memory teacher;

  @BeforeEach
  void seed() {
    user = insertUser("memory-owner@jizza.local");
    otherUser = insertUser("someone-else@jizza.local");
    jizza = insertAgent(user, "jizza", "Jizza", null, true);
    kai = insertAgent(user, "finance", "Kai", "finance", false);
    vita = insertAgent(user, "health", "Vita", "health", false);
    zerodha = (UUID) em.createNativeQuery("""
        with w as (insert into workspaces (type, name, created_by_user_id)
                   values ('personal', 'Personal', :u) returning id)
        insert into connections (user_id, workspace_id, service_name, auth_scheme, slug, status)
        select :u, w.id, 'Zerodha', 'oauth2', 'zerodha_1', 'connected' from w returning id
        """).setParameter("u", user).getSingleResult();

    Actor me = Actor.user(user);
    priya = store.upsertSubject(user, SubjectDraft.of(SubjectKind.PERSON, "Priya", "wife"), me);
    MemorySubject account = store.upsertSubject(user, new SubjectDraft(SubjectKind.ACCOUNT,
        "Priya's Zerodha account", null, null, null, zerodha, List.of("finance")), me);
    store.relate(user, account.id(), priya.id(), "held_by", me);

    teacher = store.remember(user, MemoryDraft.of(MemoryCategory.RELATIONSHIP,
        "Priya is the user's wife and works as a school teacher").about(List.of(priya.id())), me).memory();
    sip = store.remember(user, MemoryDraft.of(MemoryCategory.FINANCE,
            "Priya invests Rs 20,000 a month in a Nifty 50 index fund SIP")
        .about(List.of(priya.id())).keyed("monthly_sip"), me).memory();
    risk = store.remember(user, MemoryDraft.of(MemoryCategory.FINANCE,
        "Priya's risk tolerance is moderate").about(List.of(priya.id())).keyed("risk_tolerance"), me).memory();
    allergy = store.remember(user, MemoryDraft.of(MemoryCategory.HEALTH,
        "Priya is allergic to penicillin").about(List.of(priya.id())), me).memory();
    store.remember(user, MemoryDraft.of(MemoryCategory.FINANCE,
        "The user repays a home loan with HDFC every month"), me);
    em.flush();
  }

  // ── The scenario ─────────────────────────────────────────────────────────────────────────────

  @Test
  void theFinanceSpecialistGetsWhatItNeedsAboutTheWifeAndNothingFromHealth() {
    MemoryContextProvider.MemoryContext ctx = context.contextFor(user, kai, WIFE_REQUEST);

    assertThat(ctx.rendered())
        .contains("Priya (wife; person)")
        .contains("Nifty 50 index fund SIP")
        .contains("risk tolerance is moderate")
        .contains("school teacher")
        // The account she invests through, and the connector it is reached by.
        .contains("Priya's Zerodha account")
        .contains("connector \"Zerodha\" [connected]")
        .doesNotContain("penicillin");
    assertThat(ctx.memoryIds()).contains(sip.id(), risk.id(), teacher.id()).doesNotContain(allergy.id());
    assertThat(ctx.subjectIds()).contains(priya.id());
  }

  @Test
  void theHealthSpecialistNeverSeesFinanceFacts() {
    MemoryContextProvider.MemoryContext ctx = context.contextFor(user, vita,
        "Book my wife a check-up and remind the doctor about her allergies");

    assertThat(ctx.rendered()).contains("penicillin").contains("school teacher")
        .doesNotContain("Nifty").doesNotContain("risk tolerance").doesNotContain("Zerodha");
  }

  @Test
  void jizzaGetsTheSharedLayerSoItKnowsWhoTheWifeIsAndWhomToAsk() {
    MemoryContextProvider.MemoryContext ctx = context.contextFor(user, jizza, WIFE_REQUEST);

    assertThat(ctx.rendered()).contains("Priya (wife; person)").contains("school teacher")
        .doesNotContain("Nifty").doesNotContain("penicillin");
  }

  @Test
  void nothingIsInjectedWhenNothingIsRelevant() {
    MemoryContextProvider.MemoryContext ctx = context.contextFor(user, vita, "What is the capital of Peru?");
    assertThat(ctx.isEmpty()).isTrue();
  }

  // ── Semantic and keyword retrieval ─────────────────────────────────────────────────────────

  @Test
  void findsFactsByMeaningWithoutNamingTheirSubject() {
    List<Memory> found = store.search(user, "index fund SIP contributions", MemoryScope.everything(), 5);
    assertThat(found).isNotEmpty();
    assertThat(found.getFirst().id()).isEqualTo(sip.id());
    assertThat(found.getFirst().score()).isPositive();
  }

  @Test
  void searchRespectsTheReadersScope() {
    assertThat(store.search(user, "penicillin allergy", MemoryScope.of(List.of("finance")), 10))
        .extracting(Memory::id).doesNotContain(allergy.id());
    assertThat(store.search(user, "penicillin allergy", MemoryScope.of(List.of("health")), 10))
        .extracting(Memory::id).contains(allergy.id());
  }

  // ── Isolation between users ───────────────────────────────────────────────────────────────

  @Test
  void oneUsersMemoryIsUnreachableFromAnother() {
    UUID theirAgent = insertAgent(otherUser, "finance", "Kai", "finance", false);

    assertThat(context.contextFor(otherUser, theirAgent, WIFE_REQUEST).isEmpty()).isTrue();
    assertThat(store.search(otherUser, "Nifty index fund", MemoryScope.everything(), 10)).isEmpty();
    assertThat(store.get(otherUser, sip.id())).isEmpty();
    assertThat(store.subjects(otherUser, MemoryScope.everything())).isEmpty();
    assertThatThrownBy(() -> store.forget(otherUser, sip.id(), Actor.user(otherUser)))
        .isInstanceOf(NotFoundException.class);
    // Nor can a fact be attached to someone else's subject.
    assertThatThrownBy(() -> store.remember(otherUser,
        MemoryDraft.of(MemoryCategory.GENERAL, "sneaky").about(List.of(priya.id())), Actor.user(otherUser)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void anAgentAskingForAnotherUsersContextGetsNothing() {
    assertThat(context.contextFor(otherUser, kai, WIFE_REQUEST).isEmpty()).isTrue();
  }

  // ── Duplicates and conflicts ──────────────────────────────────────────────────────────────

  @Test
  void rememberingTheSameFactAgainReinforcesInsteadOfCopying() {
    long before = memoryRows.count();
    MemoryWrite again = store.remember(user, MemoryDraft.of(MemoryCategory.FINANCE,
            "  priya invests Rs 20,000 a month in a Nifty 50 index   fund SIP ")
        .about(List.of(priya.id())).keyed("monthly_sip"), Actor.agent(kai));

    assertThat(again.outcome()).isEqualTo(MemoryWriteOutcome.DUPLICATE);
    assertThat(again.memory().id()).isEqualTo(sip.id());
    assertThat(memoryRows.count()).isEqualTo(before);
  }

  @Test
  void aConfirmedValueIsNotOverwrittenByAnInferredOne() {
    MemoryWrite inferred = store.remember(user, MemoryDraft.of(MemoryCategory.FINANCE,
            "Priya's risk tolerance is aggressive").about(List.of(priya.id())).keyed("Risk Tolerance")
        .from(MemorySource.CONVERSATION, "message:x", false, 0.8), Actor.system());

    assertThat(inferred.outcome()).isEqualTo(MemoryWriteOutcome.PENDING_CONFLICT);
    assertThat(inferred.affected()).isEqualTo(risk.id());
    assertThat(inferred.memory().status()).isEqualTo(MemoryStatus.PENDING);
    assertThat(store.get(user, risk.id()).orElseThrow().status()).isEqualTo(MemoryStatus.ACTIVE);
    assertThat(context.contextFor(user, kai, WIFE_REQUEST).rendered()).doesNotContain("aggressive");

    // The user confirms the new value: it replaces the old one.
    store.confirm(user, inferred.memory().id(), Actor.user(user));
    assertThat(store.get(user, risk.id()).orElseThrow().status()).isEqualTo(MemoryStatus.SUPERSEDED);
    assertThat(store.get(user, risk.id()).orElseThrow().supersededBy()).isEqualTo(inferred.memory().id());
    assertThat(context.contextFor(user, kai, WIFE_REQUEST).rendered())
        .contains("aggressive").doesNotContain("moderate");
  }

  @Test
  void aNewerConfirmedValueReplacesTheOldOneStraightAway() {
    MemoryWrite newer = store.remember(user, MemoryDraft.of(MemoryCategory.FINANCE,
            "Priya invests Rs 30,000 a month in a Nifty 50 index fund SIP")
        .about(List.of(priya.id())).keyed("monthly_sip"), Actor.user(user));

    assertThat(newer.outcome()).isEqualTo(MemoryWriteOutcome.SUPERSEDED_PREVIOUS);
    assertThat(newer.affected()).isEqualTo(sip.id());
    assertThat(store.get(user, sip.id()).orElseThrow().status()).isEqualTo(MemoryStatus.SUPERSEDED);
    assertThat(store.history(user, sip.id())).extracting(c -> c.change()).contains("created", "superseded");
  }

  // ── Correction, deletion, privacy ─────────────────────────────────────────────────────────

  @Test
  void aCorrectionKeepsWhatTheFactSaidBefore() {
    Memory corrected = store.correct(user, teacher.id(), MemoryDraft.of(null,
        "Priya is the user's wife and works as a school principal"), Actor.user(user));

    assertThat(corrected.content()).contains("principal");
    assertThat(corrected.confirmed()).isTrue();
    assertThat(store.history(user, teacher.id()))
        .anySatisfy(c -> {
          assertThat(c.change()).isEqualTo("updated");
          assertThat(c.previousContent()).contains("school teacher");
        });
  }

  @Test
  void forgettingDeletesTheFactAndItsHistory() {
    store.forget(user, allergy.id(), Actor.user(user));
    em.flush();

    assertThat(store.get(user, allergy.id())).isEmpty();
    assertThat(historyRows.findByMemoryIdOrderByCreatedAtAsc(allergy.id())).isEmpty();
  }

  @Test
  void theAuditLogRecordsChangesButNeverTheirContent() {
    assertThat(audit.actions).contains("memory.created", "memory.subject.created");
    assertThat(String.join("\n", audit.details))
        .doesNotContain("penicillin").doesNotContain("Nifty").doesNotContain("teacher");
  }

  @Test
  void deletingASubjectKeepsTheFactsUnlinked() {
    store.deleteSubject(user, priya.id(), Actor.user(user));
    em.flush();
    em.clear();

    Memory stillThere = store.get(user, sip.id()).orElseThrow();
    assertThat(stillThere.subjectIds()).isEmpty();
  }

  @Test
  void listingFiltersBySubjectCategoryAndStatus() {
    assertThat(store.list(user, new MemoryFilter(null, null, priya.id(), 50)))
        .extracting(Memory::id).containsExactlyInAnyOrder(teacher.id(), sip.id(), risk.id(), allergy.id());
    assertThat(store.list(user, new MemoryFilter(MemoryCategory.HEALTH, MemoryStatus.ACTIVE, null, 50)))
        .extracting(Memory::id).containsExactly(allergy.id());
  }

  @Test
  void expiredFactsAreRemovedByRetention() {
    Memory temporary = store.remember(user, new MemoryDraft(MemoryCategory.TASK,
        "The user is travelling to Goa this weekend", null, null, null, null, null, null, null,
        null, List.of(), null, Instant.now().minusSeconds(60)), Actor.user(user)).memory();
    em.flush();

    assertThat(maintenance.applyRetention()).isGreaterThanOrEqualTo(1);
    em.clear();
    assertThat(store.get(user, temporary.id())).isEmpty();
    assertThat(store.get(user, sip.id())).isPresent();
  }

  // ── The agent's tools ─────────────────────────────────────────────────────────────────────

  @Test
  void anAgentRemembersAboutAnExistingSubjectByItsRelation() {
    ToolResult result = tools.invoke(kai, new ToolCall("c1", MemoryToolProvider.REMEMBER, Json.obj()
        .put("content", "Priya wants to retire by 55")
        .put("category", "finance")
        .put("key", "retirement_goal")
        .set("about", Json.mapper().createArrayNode().add(Json.obj().put("name", "wife")))));

    assertThat(result.ok()).as(result.error()).isTrue();
    UUID id = UUID.fromString(result.output().path("id").asText());
    Memory saved = store.get(user, id).orElseThrow();
    assertThat(saved.subjectIds()).containsExactly(priya.id());
    assertThat(saved.domains()).containsExactly("finance");
    assertThat(saved.source()).isEqualTo(MemorySource.AGENT);
    assertThat(saved.confirmed()).isFalse();
    assertThat(tools.redactArguments(MemoryToolProvider.REMEMBER)).isTrue();
  }

  @Test
  void anAgentCannotRetireAFactOutsideItsDomain() {
    ToolResult result = tools.invoke(kai, new ToolCall("c2", MemoryToolProvider.RETIRE,
        Json.obj().put("id", allergy.id().toString())));

    assertThat(result.ok()).isFalse();
    assertThat(store.get(user, allergy.id()).orElseThrow().status()).isEqualTo(MemoryStatus.ACTIVE);
  }

  @Test
  void anAgentLinksAnAccountToTheUsersConnector() {
    ToolResult result = tools.invoke(kai, new ToolCall("c3", MemoryToolProvider.DESCRIBE, Json.obj()
        .put("name", "Priya's PPF account")
        .put("kind", "account")
        .put("connector", "zerodha_1")
        .set("linked_to", Json.obj().put("name", "Priya").put("kind", "person").put("relation", "held_by"))));

    assertThat(result.ok()).as(result.error()).isTrue();
    MemorySubject ppf = store.subjects(user, MemoryScope.everything()).stream()
        .filter(s -> s.name().equals("Priya's PPF account")).findFirst().orElseThrow();
    assertThat(ppf.connectionId()).isEqualTo(zerodha);
    assertThat(ppf.domains()).containsExactly("finance");
    assertThat(ppf.relations()).extracting(MemorySubject.Relation::toSubjectId).containsExactly(priya.id());
  }

  @Test
  void recallReturnsOnlyWhatTheAgentMaySee() {
    ToolResult result = tools.invoke(vita, new ToolCall("c4", MemoryToolProvider.RECALL,
        Json.obj().put("query", "Priya index fund SIP allergy")));
    assertThat(result.ok()).isTrue();
    String out = Json.write(result.output());
    assertThat(out).contains("penicillin").doesNotContain("Nifty");
  }

  // ── Fixtures ───────────────────────────────────────────────────────────────────────────────

  private UUID insertUser(String email) {
    return (UUID) em.createNativeQuery(
            "insert into users (email, password_hash) values (:e, 'x') returning id")
        .setParameter("e", email).getSingleResult();
  }

  private UUID insertAgent(UUID userId, String key, String name, String domain, boolean orchestrator) {
    return (UUID) em.createNativeQuery("""
            insert into agents (user_id, key, name, domain, system_prompt, is_orchestrator)
            values (:u, :k, :n, cast(:d as varchar), 'prompt', :o) returning id
            """)
        .setParameter("u", userId).setParameter("k", key).setParameter("n", name)
        .setParameter("d", domain).setParameter("o", orchestrator).getSingleResult();
  }
}

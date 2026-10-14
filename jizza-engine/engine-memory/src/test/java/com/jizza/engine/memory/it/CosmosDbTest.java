package com.jizza.engine.memory.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.cosmos.AccessContext;
import com.jizza.engine.api.cosmos.BodyLink;
import com.jizza.engine.api.cosmos.BodyType;
import com.jizza.engine.api.cosmos.CelestialBody;
import com.jizza.engine.api.cosmos.CosmosBodyProvider;
import com.jizza.engine.api.cosmos.CosmosQueryService;
import com.jizza.engine.api.cosmos.CosmosSnapshot;
import com.jizza.engine.api.cosmos.MemoryNode;
import com.jizza.engine.api.cosmos.MemorySearchHit;
import com.jizza.engine.api.cosmos.MemorySearchQuery;
import com.jizza.engine.api.cosmos.Passport;
import com.jizza.engine.api.enums.DataClassification;
import com.jizza.engine.api.enums.MemoryCategory;
import com.jizza.engine.api.enums.SubjectKind;
import com.jizza.engine.api.error.ConflictException;
import com.jizza.engine.api.error.NotFoundException;
import com.jizza.engine.api.knowledge.KnowledgeEntry;
import com.jizza.engine.api.knowledge.KnowledgeStore;
import com.jizza.engine.api.memory.Actor;
import com.jizza.engine.api.memory.Memory;
import com.jizza.engine.api.memory.MemoryContextProvider;
import com.jizza.engine.api.memory.MemoryDraft;
import com.jizza.engine.api.memory.MemoryStore;
import com.jizza.engine.api.memory.MemorySubject;
import com.jizza.engine.api.memory.SubjectDraft;
import com.jizza.engine.persistence.repository.MemoryAccessRepository;
import com.jizza.engine.persistence.repository.MemoryEntryRepository;
import com.jizza.engine.persistence.support.TestDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Memory Cosmos on a real PostgreSQL: where the wife, her account and her facts sit, who each
 * fact reaches, read stamps, sharing, restriction, moving notes, search and isolation.
 *
 * <p>Not wrapped in a test transaction: read stamps are written in their own transaction, as they
 * are in production, so the data they refer to has to be committed. Each test makes its own user
 * and deletes it afterwards, which cascades to everything else.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = {MemoryDbTestConfig.class, CosmosDbTest.Providers.class})
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "jizza.memory.access.async=false"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class CosmosDbTest {

  private static final String WIFE_REQUEST = "Should my wife increase her investments this year?";

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    TestDatabase.apply(registry);
  }

  /** Stands in for the connector module: Kai can use the Zerodha connector. */
  static class Providers {
    static final Map<UUID, UUID> CONNECTOR_FOR_AGENT = new java.util.concurrent.ConcurrentHashMap<>();

    @Bean
    CosmosBodyProvider connectors() {
      return (userId, agent) -> {
        UUID connection = CONNECTOR_FOR_AGENT.get(agent.id());
        if (connection == null) return List.of();
        return List.of(new CelestialBody("CONNECTOR:" + connection + ":" + agent.id(),
            BodyType.CONNECTOR, agent.bodyId(), "Zerodha", agent.key(), 0, 0, 0, null,
            Map.of("connectionId", connection.toString(), "serviceName", "Zerodha",
                "status", "connected")));
      };
    }
  }

  @Autowired private DataSource dataSource;
  @Autowired private MemoryStore store;
  @Autowired private KnowledgeStore knowledge;
  @Autowired private MemoryContextProvider context;
  @Autowired private CosmosQueryService cosmos;
  @Autowired private MemoryAccessRepository access;
  @Autowired private MemoryEntryRepository memoryRows;

  private JdbcTemplate jdbc;
  private final List<UUID> users = new ArrayList<>();

  private UUID user;
  private UUID jizza;
  private UUID kai;
  private UUID vita;
  private UUID cato;
  private UUID zerodha;
  private MemorySubject priya;
  private MemorySubject account;
  private Memory teacher;
  private Memory sip;
  private Memory allergy;
  private Memory homeLoan;

  @BeforeEach
  void seed() {
    jdbc = new JdbcTemplate(dataSource);
    user = insertUser();
    jizza = insertAgent(user, "jizza", "Jizza", null, true);
    kai = insertAgent(user, "finance", "Kai", "finance", false);
    vita = insertAgent(user, "health", "Vita", "health", false);
    cato = insertAgent(user, "professional", "Cato", "professional", false);
    zerodha = jdbc.queryForObject("""
        insert into connections (user_id, service_name, auth_scheme, slug, status, access_token)
        values (?, 'Zerodha', 'oauth2', ?, 'connected', 'secret-token'::bytea) returning id
        """, UUID.class, user, "zerodha_" + user.toString().substring(0, 6));
    Providers.CONNECTOR_FOR_AGENT.put(kai, zerodha);

    Actor me = Actor.user(user);
    priya = store.upsertSubject(user, SubjectDraft.of(SubjectKind.PERSON, "Priya", "wife"), me);
    account = store.upsertSubject(user, new SubjectDraft(SubjectKind.ACCOUNT,
        "Priya's Zerodha account", null, null, null, zerodha, List.of("finance")), me);
    store.relate(user, account.id(), priya.id(), "held_by", me);

    teacher = store.remember(user, MemoryDraft.of(MemoryCategory.RELATIONSHIP,
        "Priya is the user's wife and works as a school teacher").about(List.of(priya.id())), me).memory();
    sip = store.remember(user, MemoryDraft.of(MemoryCategory.FINANCE,
            "Priya invests Rs 20,000 a month in a Nifty 50 index fund SIP")
        .about(List.of(priya.id())).keyed("monthly_sip"), me).memory();
    allergy = store.remember(user, MemoryDraft.of(MemoryCategory.HEALTH,
        "Priya is allergic to penicillin").about(List.of(priya.id())), me).memory();
    homeLoan = store.remember(user, MemoryDraft.of(MemoryCategory.FINANCE,
        "The user repays a home loan with HDFC every month"), me).memory();
  }

  @AfterEach
  void cleanUp() {
    Providers.CONNECTOR_FOR_AGENT.clear();
    for (UUID u : users) jdbc.update("delete from users where id = ?", u);
  }

  // ── Where things sit ─────────────────────────────────────────────────────────────────────────

  @Test
  void theWifeOrbitsJizzaHerAccountOrbitsKaiAndTheAccountIsLinkedToHerAndItsConnector() {
    CosmosSnapshot snapshot = cosmos.snapshot(user);
    Map<String, CelestialBody> bodies = byId(snapshot);

    String sun = "SUN:" + jizza;
    assertThat(bodies.get(sun).type()).isEqualTo(BodyType.SUN);
    assertThat(bodies.get("AGENT:" + kai).parentBodyId()).isEqualTo(sun);
    assertThat(bodies.get("AGENT:" + kai).label()).isEqualTo("Kai");
    assertThat(bodies.get("SUBJECT:" + priya.id()).parentBodyId()).isEqualTo(sun);
    assertThat(bodies.get("SUBJECT:" + account.id()).parentBodyId()).isEqualTo("AGENT:" + kai);
    assertThat(bodies.get("SUBJECT:" + account.id()).tier()).isEqualTo(2);

    String connector = "CONNECTOR:" + zerodha + ":" + kai;
    assertThat(bodies.get(connector).parentBodyId()).isEqualTo("AGENT:" + kai);
    assertThat(snapshot.links()).contains(
        new BodyLink("SUBJECT:" + account.id(), "SUBJECT:" + priya.id(), "held_by"),
        new BodyLink("SUBJECT:" + account.id(), connector, "connector"));

    // Counts only: Priya has three facts, Kai holds the home loan directly.
    assertThat(bodies.get("SUBJECT:" + priya.id()).memoryCount()).isEqualTo(3);
    assertThat(bodies.get("AGENT:" + kai).memoryCount()).isEqualTo(1);
    // Ids, names and states only: the connection's token never reaches the Cosmos.
    assertThat(snapshot.toString()).doesNotContain("secret-token").doesNotContain("Nifty");
  }

  @Test
  void herFactsTakeTheColourOfTheAgentThatCanSeeThemAndTetherToIt() {
    Map<UUID, MemoryNode> items = cosmos.itemsOf(user, "SUBJECT:" + priya.id(), null, 0, false)
        .items().stream().collect(Collectors.toMap(MemoryNode::id, n -> n));

    assertThat(items).containsOnlyKeys(teacher.id(), sip.id(), allergy.id());
    assertThat(items.get(sip.id()).agentKey()).isEqualTo("finance");
    assertThat(items.get(sip.id()).tetherBodyIds()).containsExactly("AGENT:" + kai);
    assertThat(items.get(allergy.id()).agentKey()).isEqualTo("health");
    assertThat(items.get(allergy.id()).tetherBodyIds()).containsExactly("AGENT:" + vita);
    // Every agent sees a shared fact; it reaches no planet in particular.
    assertThat(items.get(teacher.id()).tetherBodyIds()).isEmpty();
    assertThat(items.get(sip.id()).label()).contains("Nifty 50");
  }

  // ── Read stamps ──────────────────────────────────────────────────────────────────────────────

  @Test
  void anAgentReadingAFactStampsItOncePerRun() {
    AccessContext turn = AccessContext.chatTurn(UUID.randomUUID());
    turn.run(() -> context.contextFor(user, kai, WIFE_REQUEST));
    turn.run(() -> context.contextFor(user, kai, WIFE_REQUEST));

    assertThat(access.countByItemKindAndItemId(MemoryNode.FACT, sip.id())).isEqualTo(1);
    assertThat(memoryRows.findById(sip.id()).orElseThrow().getAccessCount()).isEqualTo(1);

    AccessContext.chatTurn(UUID.randomUUID()).run(() -> context.contextFor(user, kai, WIFE_REQUEST));
    assertThat(memoryRows.findById(sip.id()).orElseThrow().getAccessCount()).isEqualTo(2);

    Passport passport = cosmos.passport(user, MemoryNode.FACT, sip.id(), null);
    List<Passport.Stamp> reads = passport.stamps().stream().filter(s -> s.action().equals("read")).toList();
    assertThat(reads).hasSize(2);
    assertThat(reads).allSatisfy(s -> {
      assertThat(s.actorLabel()).isEqualTo("Kai");
      assertThat(s.contextKind()).isEqualTo(AccessContext.CHAT_TURN);
    });
    // A preview is the user looking, not an agent using it.
    context.preview(user, kai, WIFE_REQUEST);
    assertThat(access.countByItemKindAndItemId(MemoryNode.FACT, sip.id())).isEqualTo(2);
  }

  @Test
  void forgettingAFactTakesItsStampsWithIt() {
    AccessContext.chatTurn(UUID.randomUUID()).run(() -> context.contextFor(user, kai, WIFE_REQUEST));
    assertThat(access.countByItemKindAndItemId(MemoryNode.FACT, sip.id())).isEqualTo(1);

    store.forget(user, sip.id(), Actor.user(user));

    assertThat(access.countByItemKindAndItemId(MemoryNode.FACT, sip.id())).isZero();
    assertThat(cosmos.itemsOf(user, "SUBJECT:" + priya.id(), null, 0, false).items())
        .extracting(MemoryNode::id).doesNotContain(sip.id());
  }

  // ── Sharing ──────────────────────────────────────────────────────────────────────────────────

  @Test
  void sharingAFactWithAnotherAgentReachesItsContextAndUnsharingTakesItBack() {
    assertThat(context.preview(user, cato, WIFE_REQUEST).rendered()).doesNotContain("Nifty");

    store.share(user, sip.id(), "professional", Actor.user(user));
    assertThat(context.preview(user, cato, WIFE_REQUEST).rendered()).contains("Nifty");
    assertThat(context.preview(user, vita, WIFE_REQUEST).rendered()).doesNotContain("Nifty");
    MemoryNode node = cosmos.itemsOf(user, "SUBJECT:" + priya.id(), null, 0, false).items().stream()
        .filter(n -> n.id().equals(sip.id())).findFirst().orElseThrow();
    assertThat(node.tetherBodyIds()).containsExactlyInAnyOrder("AGENT:" + kai, "AGENT:" + cato);
    assertThat(cosmos.passport(user, MemoryNode.FACT, sip.id(), null).stamps())
        .anySatisfy(s -> {
          assertThat(s.action()).isEqualTo("shared");
          assertThat(s.note()).isEqualTo("professional");
        });

    store.unshare(user, sip.id(), "professional", Actor.user(user));
    assertThat(context.preview(user, cato, WIFE_REQUEST).rendered()).doesNotContain("Nifty");
  }

  @Test
  void sharingCanNeverWidenAFactToEveryAgentByAccident() {
    // Taking away the last domain would leave none, which means every agent.
    assertThatThrownBy(() -> store.unshare(user, sip.id(), "finance", Actor.user(user)))
        .isInstanceOf(ConflictException.class);
    // A shared fact already reaches everyone; adding a domain would narrow it.
    assertThatThrownBy(() -> store.share(user, teacher.id(), "finance", Actor.user(user)))
        .isInstanceOf(ConflictException.class);
  }

  // ── Restriction ──────────────────────────────────────────────────────────────────────────────

  @Test
  void aRestrictedFactReachesNoAgentAndItsWordsWaitForReveal() {
    store.restrict(user, sip.id(), true, Actor.user(user));

    assertThat(context.preview(user, kai, WIFE_REQUEST).rendered())
        .doesNotContain("Nifty").contains("school teacher");
    MemoryNode node = cosmos.itemsOf(user, "SUBJECT:" + priya.id(), null, 0, false).items().stream()
        .filter(n -> n.id().equals(sip.id())).findFirst().orElseThrow();
    assertThat(node.restricted()).isTrue();
    assertThat(node.redacted()).isTrue();
    assertThat(node.label()).isNull();
    assertThat(cosmos.passport(user, MemoryNode.FACT, sip.id(), null).content()).isNull();
    assertThat(cosmos.passport(user, MemoryNode.FACT, sip.id(), null).visibility().agents()).isEmpty();
    assertThat(cosmos.reveal(user, MemoryNode.FACT, sip.id()).content()).contains("Nifty 50");

    store.restrict(user, sip.id(), false, Actor.user(user));
    assertThat(context.preview(user, kai, WIFE_REQUEST).rendered()).contains("Nifty");
  }

  // ── Notes ────────────────────────────────────────────────────────────────────────────────────

  @Test
  void aMovedNoteLeavesTheOldAgentEntirelyAndARestrictedNoteReachesNoAgent() {
    KnowledgeEntry note = knowledge.upsert(kai, KnowledgeEntry.create(user, kai, "Card statement",
        "Closing balance 12,000", null, DataClassification.FINANCIAL));
    assertThat(cosmos.snapshot(user).bodies()).extracting(CelestialBody::bodyId).contains("NOTES:" + kai);

    knowledge.move(kai, note.id(), vita, Actor.user(user));
    assertThat(knowledge.search(kai, "Card statement", 5)).isEmpty();
    assertThat(knowledge.search(vita, "Card statement", 5)).extracting(KnowledgeEntry::id).contains(note.id());
    Passport passport = cosmos.passport(user, MemoryNode.NOTE, note.id(), null);
    assertThat(passport.visibility().agents()).extracting(Passport.AgentRef::id).containsExactly(vita);
    assertThat(passport.stamps()).anySatisfy(s -> {
      assertThat(s.action()).isEqualTo("moved");
      assertThat(s.note()).isEqualTo("from Kai");
    });
    // Vita's search above was an agent read, stamped like a fact's.
    assertThat(passport.stamps()).anySatisfy(s -> assertThat(s.action()).isEqualTo("read"));

    knowledge.restrict(vita, note.id(), true, Actor.user(user));
    assertThat(knowledge.search(vita, "Card statement", 5)).isEmpty();
    assertThat(knowledge.get(vita, note.id())).isEmpty();
    assertThat(knowledge.list(vita, 10)).extracting(KnowledgeEntry::id).contains(note.id());
  }

  // ── Search ───────────────────────────────────────────────────────────────────────────────────

  @Test
  void searchFindsFactsAndNotesWithThePathFromTheSun() {
    KnowledgeEntry note = knowledge.upsert(kai, KnowledgeEntry.create(user, kai, "Card statement",
        "Closing balance 12,000", null, DataClassification.FINANCIAL));

    List<MemorySearchHit> hits = cosmos.search(user, query("Nifty index fund", List.of()));
    assertThat(hits).isNotEmpty();
    assertThat(hits.getFirst().item().id()).isEqualTo(sip.id());
    assertThat(hits.getFirst().bodyPath()).containsExactly("SUN:" + jizza, "SUBJECT:" + priya.id());

    List<MemorySearchHit> notes = cosmos.search(user, query("Card statement", List.of()));
    assertThat(notes).extracting(h -> h.item().id()).contains(note.id());
    MemorySearchHit noteHit = notes.stream().filter(h -> h.item().id().equals(note.id())).findFirst().orElseThrow();
    assertThat(noteHit.bodyPath()).containsExactly("SUN:" + jizza, "AGENT:" + kai, "NOTES:" + kai);

    // Only what Vita can use.
    assertThat(cosmos.search(user, query("Nifty index fund", List.of(vita))))
        .extracting(h -> h.item().id()).doesNotContain(sip.id());
  }

  // ── Isolation ────────────────────────────────────────────────────────────────────────────────

  @Test
  void anotherUserSeesNoneOfIt() {
    UUID stranger = insertUser();
    UUID theirJizza = insertAgent(stranger, "jizza", "Jizza", null, true);

    CosmosSnapshot theirs = cosmos.snapshot(stranger);
    assertThat(theirs.bodies()).extracting(CelestialBody::bodyId).containsExactly("SUN:" + theirJizza);
    assertThatThrownBy(() -> cosmos.passport(stranger, MemoryNode.FACT, sip.id(), null))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> cosmos.itemsOf(stranger, "SUBJECT:" + priya.id(), null, 0, false))
        .isInstanceOf(NotFoundException.class);
    assertThat(cosmos.search(stranger, query("Nifty", List.of()))).isEmpty();
    assertThatThrownBy(() -> store.share(stranger, sip.id(), "finance", Actor.user(stranger)))
        .isInstanceOf(NotFoundException.class);
  }

  // ── Scale ────────────────────────────────────────────────────────────────────────────────────

  @Test
  void theSnapshotStaysFastAtTwentyThousandItems() {
    for (int i = 0; i < 16; i++) insertAgent(user, "agent" + i, "Agent " + i, "domain" + i, false);
    // 20,000 facts spread across subjects and domains, written straight to the table.
    jdbc.update("""
        insert into memory_subjects (user_id, kind, name, domains)
        select ?, 'person', 'Person ' || g, case when g % 2 = 0 then '{}'::text[]
                                                 else array['domain' || (g % 16)] end
          from generate_series(1, 180) g
        """, user);
    jdbc.update("""
        insert into memories (user_id, category, domains, content, source, content_hash)
        select ?, 'general', case when g % 3 = 0 then '{}'::text[] else array['domain' || (g % 16)] end,
               'Fact number ' || g, 'user', md5(g::text)
          from generate_series(1, 20000) g
        """, user);
    jdbc.update("""
        insert into memory_about (memory_id, subject_id)
        select m.id, (select s.id from memory_subjects s where s.user_id = m.user_id
                       and s.name = 'Person ' || (1 + abs(hashtext(m.id::text)) % 180))
          from memories m where m.user_id = ? and m.content like 'Fact number %'
           and abs(hashtext(m.id::text)) % 2 = 0
        """, user);

    cosmos.snapshot(user);
    long started = System.nanoTime();
    CosmosSnapshot snapshot = cosmos.snapshot(user);
    long millis = (System.nanoTime() - started) / 1_000_000;

    assertThat(snapshot.bodies().stream().mapToLong(CelestialBody::memoryCount).sum()).isEqualTo(20004);
    assertThat(snapshot.bodies().size()).isGreaterThan(200);
    assertThat(millis).as("snapshot of 20,000 items took %d ms", millis).isLessThan(300);
  }

  // ── helpers ──────────────────────────────────────────────────────────────────────────────────

  private static MemorySearchQuery query(String q, List<UUID> agentIds) {
    return new MemorySearchQuery(q, agentIds, null, null, null, null, null);
  }

  private static Map<String, CelestialBody> byId(CosmosSnapshot snapshot) {
    return snapshot.bodies().stream().collect(Collectors.toMap(CelestialBody::bodyId, b -> b));
  }

  private UUID insertUser() {
    UUID id = jdbc.queryForObject("insert into users (email, password_hash) values (?, 'x') returning id",
        UUID.class, "cosmos-" + UUID.randomUUID() + "@jizza.local");
    users.add(id);
    return id;
  }

  private UUID insertAgent(UUID userId, String key, String name, String domain, boolean orchestrator) {
    return jdbc.queryForObject("""
        insert into agents (user_id, key, name, domain, system_prompt, is_orchestrator)
        values (?, ?, ?, ?, 'prompt', ?) returning id
        """, UUID.class, userId, key, name, domain, orchestrator);
  }
}

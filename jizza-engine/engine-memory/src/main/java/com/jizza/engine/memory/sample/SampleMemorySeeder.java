package com.jizza.engine.memory.sample;

import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.DataClassification;
import com.jizza.engine.api.enums.MemoryCategory;
import com.jizza.engine.api.enums.MemorySource;
import com.jizza.engine.api.enums.SubjectKind;
import com.jizza.engine.api.knowledge.KnowledgeEntry;
import com.jizza.engine.api.knowledge.KnowledgeStore;
import com.jizza.engine.api.memory.Actor;
import com.jizza.engine.api.memory.MemoryDraft;
import com.jizza.engine.api.memory.MemoryFilter;
import com.jizza.engine.api.memory.MemoryStore;
import com.jizza.engine.api.memory.SubjectDraft;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.persistence.repository.UserRepository;
import com.jizza.engine.api.cosmos.AccessContext;
import com.jizza.engine.persistence.support.MemoryAccessWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Fills one account's memory with a believable sample, so the Memory Cosmos has something to show
 * while it is being built and tried. Off unless {@code jizza.memory.sample.seed-email} names an
 * account, and only for an account that remembers nothing yet: it never adds to real memory, and a
 * reset from the sun followed by a restart seeds it again.
 *
 * <p>Writes go through the stores, as an agent or the user would, so embeddings, history, audit
 * and live updates are the same as for anything else remembered.
 */
@Component
public class SampleMemorySeeder {

  private static final Logger log = LoggerFactory.getLogger(SampleMemorySeeder.class);

  private final String email;
  private final UserRepository users;
  private final AgentDirectory agents;
  private final MemoryStore memory;
  private final KnowledgeStore knowledge;
  private final MemoryAccessWriter stamps;

  public SampleMemorySeeder(@Value("${jizza.memory.sample.seed-email:}") String email,
                            UserRepository users, AgentDirectory agents, MemoryStore memory,
                            KnowledgeStore knowledge, MemoryAccessWriter stamps) {
    this.email = email;
    this.users = users;
    this.agents = agents;
    this.memory = memory;
    this.knowledge = knowledge;
    this.stamps = stamps;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void seedOnStartup() {
    if (email == null || email.isBlank()) return;
    for (String one : email.split(",")) {
      String address = one.strip();
      if (address.isEmpty()) continue;
      try {
        users.findByEmailIgnoreCase(address).ifPresentOrElse(
            u -> seed(u.getId()),
            () -> log.info("No account {} to seed sample memory for", address));
      } catch (RuntimeException e) {
        // A sample that fails to load must never stop the app from starting.
        log.warn("Could not seed sample memory for {}", address, e);
      }
    }
  }

  /** @return whether anything was seeded (false when the account already remembers something) */
  public boolean seed(UUID userId) {
    if (!memory.list(userId, MemoryFilter.all(1)).isEmpty()) {
      log.info("Account {} already has memories; sample not seeded", userId);
      return false;
    }
    Optional<AgentDto> finance = agents.byKey(userId, "finance");
    Optional<AgentDto> health = agents.byKey(userId, "health");
    Optional<AgentDto> work = agents.byKey(userId, "professional");
    AgentDto jizza = agents.orchestrator(userId);
    Actor you = Actor.user(userId);

    // ── the people, accounts and plans in the user's life ─────────────────────────────────────
    UUID wife = subject(userId, SubjectKind.PERSON, "Priya", "wife", List.of());
    UUID mother = subject(userId, SubjectKind.PERSON, "Lakshmi", "mother", List.of());
    UUID isa = subject(userId, SubjectKind.ACCOUNT, "Priya's ISA", null, List.of("finance"));
    UUID sip = subject(userId, SubjectKind.PLAN, "Monthly index-fund SIP", null, List.of("finance"));
    UUID house = subject(userId, SubjectKind.PLAN, "House deposit fund", null, List.of("finance"));
    UUID gym = subject(userId, SubjectKind.ORGANIZATION, "PureGym Canary Wharf", null, List.of("health"));
    UUID employer = subject(userId, SubjectKind.ORGANIZATION, "Northwind Labs", "employer", List.of("professional"));
    UUID launch = subject(userId, SubjectKind.PLAN, "Q4 platform launch", null, List.of("professional"));
    UUID london = subject(userId, SubjectKind.PLACE, "London", "home city", List.of());
    memory.relate(userId, isa, wife, "owned_by", you);
    memory.relate(userId, sip, isa, "funds", you);
    memory.relate(userId, launch, employer, "at", you);

    List<UUID> facts = new ArrayList<>();
    List<UUID> hot = new ArrayList<>();
    // About the user, for every agent.
    facts.add(fact(userId, MemoryCategory.PROFILE, "Goes by Chiam; prefers short, direct answers", null, List.of(), you));
    facts.add(fact(userId, MemoryCategory.PREFERENCE, "Likes a summary of the week every Friday at 5pm", null, List.of(), you));
    facts.add(fact(userId, MemoryCategory.PREFERENCE, "Prefers UPI or bank transfer over cards for payments", null, List.of(), you));
    facts.add(fact(userId, MemoryCategory.PROFILE, "Lives in London, originally from Chennai", "home_city", List.of(london), you));
    hot.add(facts.get(0));
    // Family.
    facts.add(fact(userId, MemoryCategory.RELATIONSHIP, "Priya is Chiam's wife", "relation", List.of(wife), you));
    facts.add(fact(userId, MemoryCategory.RELATIONSHIP, "Priya's birthday is 14 March", "birthday", List.of(wife), you));
    facts.add(fact(userId, MemoryCategory.RELATIONSHIP, "Priya prefers experiences to gifts", null, List.of(wife), you));
    facts.add(fact(userId, MemoryCategory.RELATIONSHIP, "Lakshmi (mother) calls every Sunday morning", null, List.of(mother), you));
    facts.add(fact(userId, MemoryCategory.HEALTH, "Lakshmi takes metformin twice a day", null, List.of(mother), you));
    hot.add(facts.get(4));
    // Money, with Kai.
    UUID isaBalance = fact(userId, MemoryCategory.FINANCE, "Priya's ISA holds about £18,400", "balance", List.of(isa, wife), you);
    facts.add(isaBalance);
    facts.add(fact(userId, MemoryCategory.FINANCE, "ISA allowance resets on 6 April each year", null, List.of(isa), you));
    facts.add(fact(userId, MemoryCategory.FINANCE, "SIP invests £500 on the 3rd of every month into a global tracker", "amount", List.of(sip), you));
    facts.add(fact(userId, MemoryCategory.FINANCE, "SIP has run since January 2024", null, List.of(sip), you));
    facts.add(fact(userId, MemoryCategory.FINANCE, "House deposit target is £60,000 by end of 2027", "target", List.of(house), you));
    facts.add(fact(userId, MemoryCategory.FINANCE, "Monthly budget for eating out is £250", null, List.of(), you));
    facts.add(fact(userId, MemoryCategory.FINANCE, "Salary lands on the 28th of each month", null, List.of(), you));
    hot.add(isaBalance);
    hot.add(facts.get(facts.size() - 1));
    // An agent's guess that conflicts with what the user said: it waits for the user to confirm.
    finance.ifPresent(kai -> agentFact(userId, MemoryCategory.FINANCE,
        "SIP may have gone up to £650 a month (seen in a bank email)", "amount", List.of(sip), kai));
    // Health, with Vita.
    facts.add(fact(userId, MemoryCategory.HEALTH, "Trains at the gym Monday, Wednesday and Friday mornings", null, List.of(gym), you));
    facts.add(fact(userId, MemoryCategory.HEALTH, "Aiming for 8,000 steps a day", "steps_goal", List.of(), you));
    facts.add(fact(userId, MemoryCategory.HEALTH, "Mild lactose intolerance", null, List.of(), you));
    facts.add(fact(userId, MemoryCategory.HEALTH, "Annual check-up is due in November", null, List.of(), you));
    UUID allergy = fact(userId, MemoryCategory.HEALTH, "Allergic to penicillin", "allergy", List.of(), you);
    facts.add(allergy);
    hot.add(allergy);
    // Work, with Cato.
    facts.add(fact(userId, MemoryCategory.PROFESSIONAL, "Senior software engineer at Northwind Labs", "role", List.of(employer), you));
    facts.add(fact(userId, MemoryCategory.PROFESSIONAL, "1:1 with manager every Tuesday at 10am", null, List.of(employer), you));
    facts.add(fact(userId, MemoryCategory.PROFESSIONAL, "Q4 platform launch is planned for 20 November", "date", List.of(launch), you));
    facts.add(fact(userId, MemoryCategory.PROJECT, "Launch checklist lives in the team's Notion space", null, List.of(launch), you));
    facts.add(fact(userId, MemoryCategory.PROFESSIONAL, "Prefers no meetings before 9:30am", null, List.of(), you));
    hot.add(facts.get(facts.size() - 3));

    // One the user keeps from every agent.
    UUID secret = fact(userId, MemoryCategory.FINANCE, "Bank security word is stored in the password manager, not here",
        null, List.of(), you);
    memory.restrict(userId, secret, true, you);

    // ── agents' own notes ─────────────────────────────────────────────────────────────────────
    List<UUID> notes = new ArrayList<>();
    UUID financeId = finance.map(AgentDto::id).orElse(null);
    UUID healthId = health.map(AgentDto::id).orElse(null);
    UUID workId = work.map(AgentDto::id).orElse(null);
    if (financeId != null) {
      notes.add(note(userId, financeId, "Spending pattern", "Groceries peak in the first week after payday."));
      notes.add(note(userId, financeId, "Rebalancing reminder", "Check the SIP allocation each April with the ISA reset."));
    }
    if (healthId != null) {
      notes.add(note(userId, healthId, "Workout log", "Bench press up from 60kg to 70kg since August."));
      notes.add(note(userId, healthId, "Sleep", "Sleeps better on days with a morning workout."));
    }
    if (workId != null) {
      notes.add(note(userId, workId, "Launch risks", "Payment provider sandbox is flaky; keep a fallback demo."));
      notes.add(note(userId, workId, "Writing style", "Status updates: three bullets, risks first."));
    }
    notes.add(note(userId, jizza.id(), "How Chiam likes to be asked", "Offer two options, recommend one."));

    // ── reads, so sizes and brightness vary ───────────────────────────────────────────────────
    List<UUID> everyone = new ArrayList<>(List.of(jizza.id()));
    for (UUID id : new UUID[] {financeId, healthId, workId}) if (id != null) everyone.add(id);
    for (int run = 0; run < 12; run++) {
      String ref = "sample-" + run;
      UUID reader = everyone.get(run % everyone.size());
      List<UUID> some = new ArrayList<>(hot);
      for (int i = run; i < facts.size(); i += 4) some.add(facts.get(i));
      stamps.stampFacts(userId, reader, some, AccessContext.AGENT_RUN, ref);
      if (run % 3 == 0) stamps.stampNotes(userId, reader, notes, AccessContext.AGENT_RUN, ref);
    }
    log.info("Seeded sample memory for {}: {} facts, {} notes", userId, facts.size() + 2, notes.size());
    return true;
  }

  private UUID subject(UUID userId, SubjectKind kind, String name, String relation, List<String> domains) {
    return memory.upsertSubject(userId,
        new SubjectDraft(kind, name, relation, null, null, null, domains), Actor.user(userId)).id();
  }

  private UUID fact(UUID userId, MemoryCategory category, String content, String key, List<UUID> about, Actor actor) {
    MemoryDraft draft = MemoryDraft.of(category, content).about(about)
        .from(MemorySource.USER, "sample", true, 1.0);
    if (key != null) draft = draft.keyed(key);
    return memory.remember(userId, draft, actor).memory().id();
  }

  private void agentFact(UUID userId, MemoryCategory category, String content, String key, List<UUID> about, AgentDto agent) {
    MemoryDraft draft = MemoryDraft.of(category, content).about(about)
        .from(MemorySource.AGENT, "sample", false, 0.6);
    if (key != null) draft = draft.keyed(key);
    memory.remember(userId, draft, Actor.agent(agent.id()));
  }

  private UUID note(UUID userId, UUID agentId, String title, String content) {
    return knowledge.upsert(agentId,
        KnowledgeEntry.create(userId, agentId, title, content, null, DataClassification.NONE)).id();
  }
}

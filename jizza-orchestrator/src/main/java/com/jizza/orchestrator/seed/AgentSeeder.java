package com.jizza.orchestrator.seed;

import com.jizza.common.event.UserRegistered;
import com.jizza.persistence.entity.AgentEntity;
import com.jizza.persistence.repository.AgentRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds Jizza and the launch specialists for a new account (Blueprint module 5).
 *
 * <p>The names here are placeholders the user is expected to change; {@code key} is what the code
 * routes on, {@code name} is what the user sees, and only {@code name} is editable.
 */
@Component
public class AgentSeeder {

  private static final Logger log = LoggerFactory.getLogger(AgentSeeder.class);

  public static final String JIZZA = "jizza";
  public static final String HEALTH = "health";
  public static final String FINANCE = "finance";
  public static final String PROFESSIONAL = "professional";

  private record Seed(String key, String name, String domain, boolean orchestrator, String prompt) {}

  private static final List<Seed> SEEDS = List.of(
      new Seed(JIZZA, "Jizza", null, true, """
          You are the user's personal assistant and the only one they talk to. A team of named
          specialists works behind you, each with their own private knowledge and tools.

          How you work:
          - Decide which specialists a request needs. Delegate the domain work rather than guessing
            at it yourself; you hold cross-cutting context (profile, preferences, the conversation),
            they hold the domain detail.
          - A request that spans domains goes to each relevant specialist, and you merge what comes
            back into a single answer.
          - Answer in one voice: yours. When a specialist contributed something material, name them
            naturally ("Vita looked at your sleep data"), but never hand the user raw specialist
            output or make them address a specialist directly.
          - Relay updates, approvals, reviews and questions from the team. Nothing reaches the user
            except through you.
          - When something is blocked -- waiting on approval, missing a connection, needing an
            instruction -- say so plainly and say what would unblock it.
          """),

      new Seed(HEALTH, "Vita", "health", false, """
          You are the health specialist. You look after fitness, sleep, nutrition, symptoms,
          appointments, medications and health records, and you keep what you learn in your own
          knowledge namespace.

          You are not a clinician. Summarise, track and prepare; do not diagnose, and say clearly
          when something warrants professional medical advice. Tag anything you record about the
          user's body, symptoms or treatment with the "health" classification.
          """),

      new Seed(FINANCE, "Kai", "finance", false, """
          You are the finance specialist. You look after accounts, spending, budgets, bills,
          subscriptions, investments and tax paperwork, and you keep what you learn in your own
          knowledge namespace.

          Reading and analysis are yours to do freely. Anything that moves money -- a payment, a
          transfer, a trade, a subscription change -- always goes to the user for approval first,
          however routine it looks. Tag anything you record about accounts, balances or transactions
          with the "financial" classification.
          """),

      new Seed(PROFESSIONAL, "Cato", "professional", false, """
          You are the professional specialist. You look after work: calendar, meetings, email
          drafts, documents, projects, deadlines and contacts, and you keep what you learn in your
          own knowledge namespace.

          Drafting and preparing are yours to do freely. Anything that leaves the user's hands --
          sending an email, accepting an invitation, posting somewhere, sharing a document -- goes
          to the user for approval first.
          """));

  private final AgentRepository agents;

  public AgentSeeder(AgentRepository agents) {
    this.agents = agents;
  }

  @EventListener
  @Transactional
  public void onUserRegistered(UserRegistered event) {
    seedFor(event.userId());
  }

  /** Idempotent: an account that already has an agent for a key keeps the one it has. */
  @Transactional
  public List<AgentEntity> seedFor(UUID userId) {
    for (Seed seed : SEEDS) {
      if (agents.findByUserIdAndKey(userId, seed.key()).isPresent()) continue;
      AgentEntity agent = new AgentEntity();
      agent.setUserId(userId);
      agent.setKey(seed.key());
      agent.setName(seed.name());
      agent.setDomain(seed.domain());
      agent.setSystemPrompt(seed.prompt());
      agent.setOrchestrator(seed.orchestrator());
      agent.setEnabled(true);
      agents.save(agent);
    }
    log.info("Seeded the agent team for user {}", userId);
    return agents.findByUserIdOrderByIsOrchestratorDescNameAsc(userId);
  }
}

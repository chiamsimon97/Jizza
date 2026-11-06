package com.jizza.app.e2e;

import com.jizza.app.JizzaApplication;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.persistence.support.TestDatabase;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for the end-to-end acceptance suite: the whole application, booted against a real
 * PostgreSQL, exercised through its own HTTP API.
 *
 * <p>Two things are substituted, and only two. The model is scripted, because an acceptance test
 * should assert what the platform does with a reply rather than what a model happens to say; and
 * the scheduler and queue pollers are switched off so tests can drive them deterministically
 * instead of racing a background thread. Everything else -- migrations, security, encryption,
 * OAuth, the saga engine, the audit chain -- is the real implementation.
 *
 * <p>Needs a PostgreSQL: {@code JIZZA_TEST_DB_URL}, or a Docker daemon for the pgvector image.
 * Each concrete suite repeats the {@code @EnabledIf} guard below, because JUnit evaluates
 * conditions on the class being run and does not pick this one up from a superclass -- without it
 * the suites would fail to start a context rather than reporting as skipped.
 */
@SpringBootTest(classes = {JizzaApplication.class, E2eSupport.ScriptedModel.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
public abstract class E2eSupport {

  @org.springframework.beans.factory.annotation.Autowired
  private org.springframework.jdbc.core.JdbcTemplate jdbc;

  /**
   * Releases the scheduler's dispatch lock.
   *
   * <p>ShedLock holds it for a few seconds after every tick so two workers with skewed clocks
   * cannot both claim the same tick. The suite drives ticks back to back and would otherwise have
   * dispatches silently skipped, so it clears the hold first -- the same state the next real tick
   * would find, twenty seconds later.
   */
  protected void releaseDispatchLock() {
    jdbc.update("update shedlock set lock_until = now() where name = 'jizza-task-dispatch'");
  }

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    TestDatabase.apply(registry);

    // Pollers off: each test drives the scheduler and queue itself so assertions are deterministic.
    registry.add("jizza.scheduler.enabled", () -> "false");
    registry.add("jizza.platform.queue.enabled", () -> "false");
    registry.add("jizza.notifications.sweep-interval", () -> "PT1H");
    // A model-written title would replace the first words typed with the scripted model's reply;
    // the test that covers titles turns it on for itself.
    registry.add("jizza.chat.titles.enabled", () -> "false");

    // The stub connector runs on loopback over plain http, which the egress guard blocks by
    // default; these two lines are exactly the development escape hatch it provides.
    registry.add("jizza.connectors.egress.allow-hosts", () -> "localhost,127.0.0.1");
    registry.add("jizza.connectors.egress.allow-plain-http", () -> "true");
    registry.add("jizza.connectors.callback-base-url", () -> "http://localhost:8080");

    // A fresh database has no Super Admin account to own the Public organization.
    registry.add("jizza.development", () -> "true");
    registry.add("jizza.security.session.cookie-secure", () -> "false");
    // Every suite registers its own throwaway users and signs in with a password alone. The auth
    // suite switches two-step enforcement on where that is what it is testing.
    registry.add("jizza.security.registration.allow-additional-users", () -> "true");
    registry.add("jizza.security.mfa.required", () -> "false");
    // Every test in the suite calls from 127.0.0.1, so the per-address auth rate limit would
    // trip on cross-test traffic rather than on anything the tests are asserting. Per-account
    // lockout is left at its real value, which is what the lockout test exercises.
    registry.add("jizza.security.lockout.auth-requests-per-minute", () -> "10000");
    // The same holds for device enrollment and token sign-in, also limited per address.
    registry.add("jizza.devices.auth-attempts-per-minute", () -> "10000");
    registry.add("jizza.security.master-key", () -> "F1jkQFbz3rMzsz6qKZDe1oI08Kj0jZ7t1v8sMoyGZ8Y=");
    registry.add("jizza.security.master-key-file", () -> "");

    registry.add("jizza.platform.storage.root", () -> "./target/e2e-storage");
    registry.add("jizza.platform.mail.enabled", () -> "false");
    registry.add("spring.mail.host", () -> "localhost");
    registry.add("spring.mail.port", () -> "1025");
    // Mail is disabled in the suite, so its health contributor would report a mail
    // catcher that is deliberately not running.
    registry.add("management.health.mail.enabled", () -> "false");

    // Learning from chat calls the model after every turn; it is off for the suite so it cannot
    // consume replies scripted for agents, and the memory suite switches it on where it is tested.
    // When on, it runs on the calling thread so its effect can be asserted.
    registry.add("jizza.memory.extraction.enabled", () -> "false");
    registry.add("jizza.memory.extraction.async", () -> "false");
    registry.add("jizza.memory.access.async", () -> "false");
    // Choosing the model for Jizza's turn is a model call of its own; off for the same reason.
    registry.add("jizza.agents.routing.enabled", () -> "false");

    registry.add("jizza.agents.models.routing", () -> "test-routing");
    registry.add("jizza.agents.models.standard", () -> "test-standard");
    registry.add("jizza.agents.models.deep", () -> "test-deep");
  }

  /**
   * A model whose replies the test writes. It also records every request, which is how the suite
   * asserts that no credential ever reaches model context.
   */
  public static final class ScriptedLlmClient implements LlmClient {

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    private final java.util.Map<String, Deque<LlmResponse>> byAgent =
        new java.util.concurrent.ConcurrentHashMap<>();
    public final List<List<Msg>> requests =
        java.util.Collections.synchronizedList(new ArrayList<>());
    public final List<List<ToolSpec>> offeredTools =
        java.util.Collections.synchronizedList(new ArrayList<>());

    /** Consulted when the script is empty; the default simply ends the turn. */
    public Function<List<Msg>, LlmResponse> fallback =
        messages -> LlmResponse.text("Nothing further.");

    /** Replies for whichever agent asks next. */
    public void script(LlmResponse... responses) {
      script.clear();
      script.addAll(List.of(responses));
    }

    /**
     * Replies for one named agent's turns only. A cross-domain request runs several agents
     * concurrently, so scripting by name is the only way to say who is meant to say what.
     */
    public void scriptFor(String agentName, LlmResponse... responses) {
      byAgent.put(agentName, new java.util.concurrent.ConcurrentLinkedDeque<>(List.of(responses)));
    }

    public void reset() {
      script.clear();
      byAgent.clear();
      requests.clear();
      offeredTools.clear();
      fallback = messages -> LlmResponse.text("Nothing further.");
    }

    /** Every prompt and tool schema the model was shown, flattened for assertions. */
    public String everythingTheModelSaw() {
      StringBuilder sb = new StringBuilder();
      for (List<Msg> conversation : requests) {
        for (Msg message : conversation) {
          if (message.text() != null) sb.append(message.text()).append('\n');
          message.toolCalls().forEach(call -> sb.append(call.name()).append(' ')
              .append(call.args() == null ? "" : call.args()).append('\n'));
          message.toolResults().forEach(result -> sb.append(result.result().output()).append('\n')
              .append(String.valueOf(result.result().error())).append('\n'));
        }
      }
      for (List<ToolSpec> tools : offeredTools) {
        for (ToolSpec tool : tools) {
          sb.append(tool.name()).append(' ').append(tool.description()).append(' ')
              .append(tool.inputSchema()).append('\n');
        }
      }
      return sb.toString();
    }

    @Override
    public String modelFor(ModelTier tier) {
      return "test-" + tier.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public synchronized LlmResponse complete(String model, List<Msg> messages, List<ToolSpec> tools) {
      requests.add(List.copyOf(messages));
      offeredTools.add(tools == null ? List.of() : List.copyOf(tools));

      String agent = agentNameOf(messages);
      Deque<LlmResponse> forAgent = agent == null ? null : byAgent.get(agent);
      if (forAgent != null && !forAgent.isEmpty()) return forAgent.poll();
      if (!script.isEmpty()) return script.poll();
      return fallback.apply(messages);
    }

    /** Every system prompt opens with the agent's name, which is what routes a scripted reply. */
    private String agentNameOf(List<Msg> messages) {
      for (Msg message : messages) {
        if (message.role() == Msg.Role.SYSTEM && message.text() != null
            && message.text().startsWith("Your name is ")) {
          String rest = message.text().substring("Your name is ".length());
          int stop = rest.indexOf('.');
          return stop < 0 ? rest.trim() : rest.substring(0, stop).trim();
        }
      }
      return null;
    }
  }

  @TestConfiguration
  public static class ScriptedModel {

    @Bean
    @Primary
    public ScriptedLlmClient scriptedLlmClient() {
      return new ScriptedLlmClient();
    }

    /**
     * Runs {@code @Async} work on the calling thread. Procedure learning is the only asynchronous
     * listener in the system, and left on its own thread it can still be talking to the scripted
     * model after the test that triggered it has finished -- which shows up as another test's
     * script being consumed. Inline, each test sees exactly the turns it caused.
     */
    @Bean(name = "taskExecutor")
    @Primary
    public org.springframework.core.task.TaskExecutor inlineTaskExecutor() {
      return new org.springframework.core.task.SyncTaskExecutor();
    }
  }
}

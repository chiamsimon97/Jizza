package com.jizza.engine.orchestrator.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.agents.config.AgentProperties;
import com.jizza.engine.api.enums.ModelTier;
import com.jizza.engine.api.llm.CostAccountant;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.persistence.entity.AgentEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TurnTierSelectorTest {

  private final UUID userId = UUID.randomUUID();
  private final AgentProperties props = new AgentProperties();
  private final List<String> purposes = new ArrayList<>();
  private final List<List<Msg>> asked = new ArrayList<>();
  private Supplier<LlmResponse> answer = () -> response("simple");
  private AgentEntity jizza;
  private TurnTierSelector selector;

  private static LlmResponse response(String text) {
    return new LlmResponse(text, List.of(), "end_turn", 10, 1, "cheap");
  }

  @BeforeEach
  void setUp() {
    jizza = new AgentEntity();
    jizza.setModelTier("standard");
    LlmClient llm = new LlmClient() {
      @Override public LlmResponse complete(String model, List<Msg> messages, List<ToolSpec> tools) {
        assertThat(model).isEqualTo("cheap");
        asked.add(messages);
        return answer.get();
      }

      @Override public String modelFor(ModelTier tier) {
        return tier == ModelTier.ROUTING ? "cheap" : "strong";
      }
    };
    CostAccountant costs = new CostAccountant() {
      @Override public void record(UUID user, String model, int in, int out, String purpose) {
        purposes.add(purpose);
      }

      @Override public boolean withinBudget(UUID user) { return true; }

      @Override public double spendInWindow(UUID user) { return 0; }
    };
    selector = new TurnTierSelector(llm, costs, props);
  }

  private ModelTier tierFor(String message) {
    return selector.tierFor(userId, jizza, message, null, List.of());
  }

  @Test
  void aSimpleMessageRunsOnTheRoutingModelAndTheCheckIsCosted() {
    assertThat(tierFor("thanks!")).isEqualTo(ModelTier.ROUTING);
    assertThat(purposes).containsExactly("jizza:routing");
  }

  @Test
  void anythingButAPlainSimpleGoesToTheStandardModel() {
    answer = () -> response("complex");
    assertThat(tierFor("Should I move my savings into index funds?")).isEqualTo(ModelTier.STANDARD);
    answer = () -> response("Hmm, it depends");
    assertThat(tierFor("hello")).isEqualTo(ModelTier.STANDARD);
  }

  @Test
  void aFailedCheckFallsBackToTheStandardModel() {
    answer = () -> { throw new IllegalStateException("rate limited"); };
    assertThat(tierFor("hello")).isEqualTo(ModelTier.STANDARD);
  }

  @Test
  void theCheckIsSkippedWhenItCannotHelp() {
    props.getRouting().setMaxSimpleChars(20);
    assertThat(tierFor("a".repeat(21))).isEqualTo(ModelTier.STANDARD);
    assertThat(selector.tierFor(userId, jizza, "plan it", JizzaOrchestrator.WORKFLOW_PLANNER,
        List.of())).isEqualTo(ModelTier.STANDARD);
    jizza.setModelTier("deep");
    assertThat(tierFor("hi")).isEqualTo(ModelTier.STANDARD);
    jizza.setModelTier("standard");
    props.getRouting().setEnabled(false);
    assertThat(tierFor("hi")).isEqualTo(ModelTier.STANDARD);
    assertThat(asked).as("no check was made in any of these").isEmpty();
  }

  @Test
  void theCheckSeesTheLastTurnsSoAShortYesIsReadInContext() {
    selector.tierFor(userId, jizza, "yes", null, List.of(
        Msg.user("Can you rebalance my portfolio?"),
        Msg.assistant("I can move 10% from bonds to equities. Shall I?")));

    String sent = asked.get(0).get(1).text();
    assertThat(sent).contains("User: Can you rebalance my portfolio?")
        .contains("Jizza: I can move 10%").endsWith("Next message:\nyes");
  }
}

package com.jizza.engine.chat.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.chat.ChatScope;
import com.jizza.engine.api.chat.JobEventDto;
import com.jizza.engine.api.activity.ActivityTypes;
import com.jizza.engine.api.orchestrator.Orchestrator;
import com.jizza.engine.api.orchestrator.UiAction;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolResult;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.chat.turn.TurnRunner;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StepsToolProviderTest {

  static final String KITE = "https://mcp.kite.trade/authorize?session_id=abc";

  private final UUID userId = UUID.randomUUID();
  private final UUID leadId = UUID.randomUUID();
  private final StepsToolProvider provider = new StepsToolProvider();

  private ChatScope.Scope thread() {
    return ChatScope.Scope.thread(userId, UUID.randomUUID(), UUID.randomUUID(), leadId, "Plan", "Money");
  }

  @Test
  void onlyTheAgentAnsweringTheUserGetsIt() {
    assertThat(provider.toolsFor(leadId)).isEmpty();
    ChatScope.Scope scope = thread();
    ChatScope.run(scope, () -> {
      assertThat(provider.toolsFor(leadId)).hasSize(1);
      assertThat(provider.toolsFor(UUID.randomUUID())).isEmpty();
      return null;
    });
  }

  @Test
  void stepsLandOnTheReplyWithTheirButtons() {
    ObjectNode args = Json.obj().put("title", "Connect Zerodha");
    var steps = args.putArray("steps");
    steps.addObject().put("label", "Log in to Kite").put("url", KITE).put("button_label", "Log in");
    steps.addObject().put("label", "Come back").put("reply", "Done, carry on");
    ChatScope.Scope scope = thread();
    ToolResult result = ChatScope.run(scope,
        () -> provider.invoke(leadId, new ToolCall("1", StepsToolProvider.SHOW_STEPS, args)));

    assertThat(result.ok()).isTrue();
    List<UiAction> actions = ChatScope.actions(scope);
    assertThat(actions).singleElement().satisfies(a -> {
      assertThat(a.type()).isEqualTo(UiAction.SHOW_STEPS);
      assertThat(a.payload().at("/steps/0/button/url").asText()).isEqualTo(KITE);
      assertThat(a.payload().at("/steps/1/button/kind").asText()).isEqualTo("reply");
    });
  }

  @Test
  void refusesALinkThatIsNotAWebPage() {
    ObjectNode args = Json.obj().put("title", "x");
    args.putArray("steps").addObject().put("label", "Open").put("url", "javascript:alert(1)");
    ChatScope.Scope scope = thread();
    ToolResult result = ChatScope.run(scope,
        () -> provider.invoke(leadId, new ToolCall("1", StepsToolProvider.SHOW_STEPS, args)));
    assertThat(result.ok()).isFalse();
    assertThat(ChatScope.actions(scope)).isEmpty();
  }

  @Test
  void aSignInLinkFromAToolBecomesACardOnce() {
    var event = new JobEventDto(3, ActivityTypes.SIGN_IN_LINK, leadId, "Kai",
        Json.obj().put("url", KITE).put("service", "Kite"), Instant.now());
    var turn = new Orchestrator.Turn("Here's your link", List.of(), List.of(), 0, 0);

    var withCard = TurnRunner.withSignInSteps(turn, List.of(event, event));
    assertThat(withCard.actions()).singleElement()
        .satisfies(a -> assertThat(a.payload().path("title").asText()).isEqualTo("Sign in to Kite"));

    // A card the agent already made with that link is not repeated.
    assertThat(TurnRunner.withSignInSteps(withCard, List.of(event)).actions()).hasSize(1);
  }
}

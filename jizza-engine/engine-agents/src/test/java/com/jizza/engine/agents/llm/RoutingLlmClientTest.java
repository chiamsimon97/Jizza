package com.jizza.engine.agents.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.error.JizzaException;
import com.jizza.engine.api.llm.LlmCallContext;
import com.jizza.engine.api.llm.LlmClient;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.llm.Msg;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoutingLlmClientTest {

  private final LlmClient platform = mock(LlmClient.class);
  private final LlmClient own = mock(LlmClient.class);
  private final ModelProviders providers = mock(ModelProviders.class);
  private final RoutingLlmClient client = new RoutingLlmClient(platform, () -> providers);

  @Test
  void aBareModelGoesToThePlatform() {
    when(platform.complete(eq("claude-sonnet-5-5"), any(), any())).thenReturn(LlmResponse.text("hi"));
    assertThat(client.complete("claude-sonnet-5-5", List.of(Msg.user("x")), List.of()).text()).isEqualTo("hi");
    verify(providers, never()).clientFor(any(), any());
  }

  @Test
  void aModelOnTheirOwnKeyGoesToThePersonsConnectionAndIsNamedAsAskedFor() {
    UUID user = UUID.randomUUID();
    when(providers.clientFor(user, "openai")).thenReturn(own);
    when(own.complete(eq("gpt-5.1"), any(), any()))
        .thenReturn(new LlmResponse("hello", List.of(), "end_turn", 10, 2, "gpt-5.1-2026"));

    LlmResponse r = LlmCallContext.run(user, null,
        () -> client.complete("openai:gpt-5.1", List.of(Msg.user("x")), List.of()));

    assertThat(r.text()).isEqualTo("hello");
    // Recorded against the person's own key, priced from their catalog.
    assertThat(r.model()).isEqualTo("openai:gpt-5.1");
  }

  @Test
  void aModelOnSomeonesOwnKeyIsNeverUsedOutsideTheirOwnRequest() {
    assertThatThrownBy(() -> client.complete("openai:gpt-5.1", List.of(Msg.user("x")), List.of()))
        .isInstanceOf(JizzaException.class);
  }
}

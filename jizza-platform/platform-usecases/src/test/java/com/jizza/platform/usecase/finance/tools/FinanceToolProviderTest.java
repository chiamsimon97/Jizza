package com.jizza.platform.usecase.finance.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.util.Json;
import com.jizza.platform.usecase.finance.cashflow.FinCashFlowUseCase;
import com.jizza.platform.usecase.finance.debt.FinLoansUseCase;
import com.jizza.platform.usecase.finance.health.FinHealthUseCase;
import com.jizza.platform.usecase.finance.protection.FinProtectionUseCase;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class FinanceToolProviderTest {

  private final UUID user = UUID.randomUUID();
  private final UUID kaiId = UUID.randomUUID();
  private final UUID otherId = UUID.randomUUID();
  private final AgentDirectory agents = mock(AgentDirectory.class);
  private final FinHealthUseCase health = mock(FinHealthUseCase.class);

  @SuppressWarnings("unchecked")
  private FinanceToolProvider provider(boolean enabled) {
    when(agents.byId(kaiId)).thenReturn(Optional.of(new AgentDto(kaiId, user, "finance", "Kai", "finance", "", false, true)));
    when(agents.byId(otherId)).thenReturn(Optional.of(new AgentDto(otherId, user, "health", "Hana", "health", "", false, true)));
    ObjectProvider<FinHealthUseCase> h = mock(ObjectProvider.class);
    when(h.getObject()).thenReturn(health);
    return new FinanceToolProvider(agents, h, mock(ObjectProvider.class), mock(ObjectProvider.class),
        mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class), enabled);
  }

  @Test
  void onlyKaiGetsTheToolsAndOnlyWhileFinanceIsOn() {
    var p = provider(true);
    assertThat(p.toolsFor(kaiId)).extracting(t -> t.name()).containsExactly("finance_overview",
        "finance_cash_flow", "finance_loans", "finance_protection", "finance_goals", "finance_tax",
        "finance_open_screen", "finance_update_action");
    assertThat(p.toolsFor(otherId)).isEmpty();
    assertThat(p.supports(otherId, "finance_overview")).isFalse();
    assertThat(provider(false).toolsFor(kaiId)).isEmpty();
  }

  @Test
  void readsResolveToReadAndArgumentsAreRedacted() {
    var p = provider(true);
    assertThat(p.actionTypeOf(kaiId, "finance_overview")).contains(ActionType.READ);
    assertThat(p.actionTypeOf(kaiId, "finance_loans")).contains(ActionType.READ);
    assertThat(p.actionTypeOf(kaiId, "finance_goals")).contains(ActionType.READ);
    assertThat(p.actionTypeOf(kaiId, "finance_tax")).contains(ActionType.READ);
    assertThat(p.actionTypeOf(kaiId, "finance_open_screen")).contains(ActionType.DRAFT);
    assertThat(p.actionTypeOf(kaiId, "finance_update_action")).contains(ActionType.UPDATE);
    assertThat(p.actionTypeOf(kaiId, "bank_transfer")).isEmpty();
    assertThat(p.redactArguments("finance_overview")).isTrue();
    assertThat(p.toolsFor(kaiId)).filteredOn(t -> t.sideEffecting()).extracting(t -> t.name())
        .containsExactly("finance_update_action");
  }

  @Test
  void onlyTheCallersOwnScopeAndNoOtherAgent() {
    var p = provider(true);
    var family = p.invoke(kaiId, new ToolCall("finance_overview", Json.obj().put("scope", "family:x")));
    assertThat(family.ok()).isFalse();
    var other = p.invoke(otherId, new ToolCall("finance_overview", Json.obj()));
    assertThat(other.ok()).isFalse();
    verifyNoInteractions(health);
  }

  @Test
  void openScreenLinksIntoThePortfolioAndUpdateActionOnlyDoneOrSnoozed() {
    var p = provider(true);
    var link = p.invoke(kaiId, new ToolCall("finance_open_screen", Json.obj().put("screen", "loans")));
    assertThat(link.ok()).isTrue();
    assertThat(link.output().path("url").asText()).isEqualTo("/agents/" + kaiId + "?tab=portfolio&view=loans");
    var bad = p.invoke(kaiId, new ToolCall("finance_update_action",
        Json.obj().put("actionId", UUID.randomUUID().toString()).put("status", "dismissed")));
    assertThat(bad.ok()).isFalse();

    UUID action = UUID.randomUUID();
    when(health.updateAction(eq(user), eq(action), eq("done"), any(), any()))
        .thenReturn(Json.obj().put("id", action.toString()).put("title", "Record a will").put("status", "done"));
    var done = p.invoke(kaiId, new ToolCall("finance_update_action",
        Json.obj().put("actionId", action.toString()).put("status", "done")));
    assertThat(done.ok()).isTrue();
    verify(health).updateAction(eq(user), eq(action), eq("done"), any(), any());
  }
}

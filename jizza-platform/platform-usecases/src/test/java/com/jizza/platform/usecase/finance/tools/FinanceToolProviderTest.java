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
  private final com.jizza.platform.usecase.finance.records.FinRecordsUseCase records =
      mock(com.jizza.platform.usecase.finance.records.FinRecordsUseCase.class);

  @SuppressWarnings("unchecked")
  private FinanceToolProvider provider(boolean enabled) {
    when(agents.byId(kaiId)).thenReturn(Optional.of(new AgentDto(kaiId, user, "finance", "Kai", "finance", "", false, true)));
    when(agents.byId(otherId)).thenReturn(Optional.of(new AgentDto(otherId, user, "health", "Hana", "health", "", false, true)));
    ObjectProvider<FinHealthUseCase> h = mock(ObjectProvider.class);
    when(h.getObject()).thenReturn(health);
    ObjectProvider<com.jizza.platform.usecase.finance.records.FinRecordsUseCase> forms = mock(ObjectProvider.class);
    when(forms.getObject()).thenReturn(records);
    return new FinanceToolProvider(agents, h, mock(ObjectProvider.class), mock(ObjectProvider.class),
        mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class),
        mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class),
        forms, enabled);
  }

  @Test
  void onlyKaiGetsTheToolsAndOnlyWhileFinanceIsOn() {
    var p = provider(true);
    assertThat(p.toolsFor(kaiId)).extracting(t -> t.name()).containsExactly("finance_overview",
        "finance_cash_flow", "finance_loans", "finance_protection", "finance_goals", "finance_tax",
        "finance_holdings", "finance_allocation", "finance_explain_value", "finance_open_screen",
        "finance_update_action", "finance_sync_connected", "finance_save_sync", "finance_show_form");
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
        .containsExactly("finance_update_action", "finance_sync_connected", "finance_save_sync");
    assertThat(p.actionTypeOf(kaiId, "finance_save_sync")).contains(ActionType.UPDATE);
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

  @Test
  void aFormGoesInTheChatWithOnlyTheValuesThatWouldSave() {
    var p = provider(true);
    var table = com.jizza.platform.usecase.finance.records.FinTable.GOAL;
    when(records.clean(eq(user), eq(table), any(), eq(false))).thenAnswer(inv -> {
      com.fasterxml.jackson.databind.JsonNode body = inv.getArgument(2);
      if (body.has("targetDate") && !body.get("targetDate").asText().matches("\\d{4}-\\d{2}-\\d{2}")) {
        throw new com.jizza.engine.api.error.ValidationException("targetDate must be a date.");
      }
      return (com.fasterxml.jackson.databind.node.ObjectNode) body;
    });
    var args = Json.obj().put("form", "goal").put("intro", "Check the date.");
    args.putObject("values").put("type", "education").put("name", "College").put("targetAmountToday", 2500000)
        .put("targetDate", "next year").put("password", "x");

    // Outside a chat there is nowhere to show it.
    assertThat(p.invoke(kaiId, new ToolCall("finance_show_form", args)).ok()).isFalse();

    var scope = com.jizza.engine.api.chat.ChatScope.Scope.main(user, UUID.randomUUID(), UUID.randomUUID(), true);
    var shown = com.jizza.engine.api.chat.ChatScope.run(scope, () -> p.invoke(kaiId, new ToolCall("finance_show_form", args)));
    assertThat(shown.ok()).as(String.valueOf(shown.error())).isTrue();
    assertThat(shown.output().path("leftOut").toString()).contains("targetDate").contains("password");
    var actions = com.jizza.engine.api.chat.ChatScope.actions(scope);
    assertThat(actions).hasSize(1);
    assertThat(actions.get(0).type()).isEqualTo("finance_form");
    var card = actions.get(0).payload();
    assertThat(card.path("form").asText()).isEqualTo("goal");
    assertThat(card.path("intro").asText()).isEqualTo("Check the date.");
    assertThat(card.path("values").path("name").asText()).isEqualTo("College");
    assertThat(card.path("values").has("targetDate")).isFalse();
    assertThat(card.path("values").has("password")).isFalse();
    assertThat(card.path("id").asText()).isNotBlank();
    assertThat(p.actionTypeOf(kaiId, "finance_show_form")).contains(ActionType.DRAFT);
    assertThat(p.invoke(kaiId, new ToolCall("finance_show_form", Json.obj().put("form", "tax"))).ok()).isFalse();
  }
}

package com.jizza.platform.usecase.vita;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.chat.ChatScope;
import com.jizza.engine.api.dto.AgentDto;
import com.jizza.engine.api.enums.ActionType;
import com.jizza.engine.api.finance.FinanceStore;
import com.jizza.engine.api.finance.FinanceStore.PersonRow;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.api.vita.VitaRecords;
import com.jizza.platform.usecase.vita.sharing.VitaScopeResolver;
import com.jizza.platform.usecase.vita.tools.VitaToolProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class VitaToolProviderTest {

  private final UUID user = UUID.randomUUID();
  private final UUID vitaId = UUID.randomUUID();
  private final UUID kaiId = UUID.randomUUID();
  private final AgentDirectory agents = mock(AgentDirectory.class);
  private final FinanceStore people = mock(FinanceStore.class);
  private final VitaRecords records = mock(VitaRecords.class);
  private final PersonRow me = new PersonRow(UUID.randomUUID(), user, "Me", "self", "individual", null,
      false, false, "active", 0, null, null);

  @SuppressWarnings("unchecked")
  private VitaToolProvider provider(boolean enabled) {
    when(agents.byId(vitaId)).thenReturn(Optional.of(new AgentDto(vitaId, user, "health", "Vita", "health", "", false, true)));
    when(agents.byId(kaiId)).thenReturn(Optional.of(new AgentDto(kaiId, user, "finance", "Kai", "finance", "", false, true)));
    when(people.people(user)).thenReturn(List.of(me));
    when(people.person(user, me.id())).thenReturn(Optional.of(me));
    ObjectProvider<VitaScopeResolver> scopes = mock(ObjectProvider.class);
    when(scopes.getObject()).thenReturn(new VitaScopeResolver(people));
    return new VitaToolProvider(agents, scopes, mock(ObjectProvider.class), mock(ObjectProvider.class),
        mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class),
        mock(ObjectProvider.class), mock(ObjectProvider.class), enabled);
  }

  @Test
  void onlyVitaGetsTheToolsAndOnlyWhileHealthIsOn() {
    var p = provider(true);
    assertThat(p.toolsFor(vitaId)).extracting(t -> t.name()).containsExactly("vita_overview", "vita_read",
        "vita_red_flag_help", "vita_open_screen", "vita_trend", "vita_condition_summary", "vita_show_form");
    assertThat(p.toolsFor(kaiId)).isEmpty();
    assertThat(p.supports(kaiId, "vita_read")).isFalse();
    assertThat(provider(false).toolsFor(vitaId)).isEmpty();
    assertThat(p.invoke(kaiId, new ToolCall("vita_read", Json.obj().put("section", "profile"))).ok()).isFalse();
  }

  @Test
  void everyToolIsRegisteredAsReadOrDraftAndNoneSaves() {
    var p = provider(true);
    for (var t : p.toolsFor(vitaId)) {
      assertThat(p.actionTypeOf(vitaId, t.name())).as(t.name()).isPresent();
      assertThat(p.actionTypeOf(vitaId, t.name()).get()).isIn(ActionType.READ, ActionType.DRAFT);
      assertThat(t.sideEffecting()).isFalse();
      assertThat(p.redactArguments(t.name())).isTrue();
    }
    assertThat(p.actionTypeOf(vitaId, "vita_read")).contains(ActionType.READ);
    assertThat(p.actionTypeOf(vitaId, "vita_red_flag_help")).contains(ActionType.READ);
    assertThat(p.actionTypeOf(vitaId, "vita_show_form")).contains(ActionType.DRAFT);
  }

  @Test
  void aFormGoesIntoTheChatCheckedAndNothingIsSaved() {
    var p = provider(true);
    var args = Json.obj().put("form", "profile").put("intro", "Check your blood group.");
    args.putObject("values").put("bloodGroup", "o_pos").put("heightCm", 1820).put("abhaNumber", "12345678901234");

    assertThat(p.invoke(vitaId, new ToolCall("vita_show_form", args)).ok())
        .as("no chat to show it in").isFalse();

    var scope = ChatScope.Scope.main(user, UUID.randomUUID(), UUID.randomUUID(), true);
    var shown = ChatScope.run(scope, () -> p.invoke(vitaId, new ToolCall("vita_show_form", args)));
    assertThat(shown.ok()).as(String.valueOf(shown.error())).isTrue();
    assertThat(shown.output().path("leftOut").toString()).contains("heightCm").contains("abhaNumber");
    var actions = ChatScope.actions(scope);
    assertThat(actions).hasSize(1);
    assertThat(actions.get(0).type()).isEqualTo("vita_form");
    var card = actions.get(0).payload();
    assertThat(card.path("form").asText()).isEqualTo("profile");
    assertThat(card.path("personId").asText()).isEqualTo(me.id().toString());
    assertThat(card.path("values").path("bloodGroup").asText()).isEqualTo("o_pos");
    assertThat(card.path("values").has("abhaNumber")).isFalse();
    verifyNoInteractions(records);

    var contact = Json.obj().put("form", "emergency_contact");
    contact.putObject("values").put("name", "Ravi").put("phone", "+91 98000 00000").put("relation", "Brother");
    var c = ChatScope.run(scope, () -> p.invoke(vitaId, new ToolCall("vita_show_form", contact)));
    assertThat(c.ok()).isTrue();
    assertThat(p.invoke(vitaId, new ToolCall("vita_show_form", Json.obj().put("form", "medicine"))).ok()).isFalse();
  }
}

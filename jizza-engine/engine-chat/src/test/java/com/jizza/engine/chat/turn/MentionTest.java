package com.jizza.engine.chat.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MentionTest {

  private static AgentEntity agent(String key, String name) {
    AgentEntity a = new AgentEntity();
    a.setId(UUID.randomUUID());
    a.setKey(key);
    a.setName(name);
    return a;
  }

  private final AgentEntity kai = agent("kai", "Kai");
  private final AgentEntity kaiTax = agent("kai_tax", "Kai Tax");
  private final List<AgentEntity> agents = List.of(kai, kaiTax);

  @Test
  void theAgentNamedAtTheStartAnswersTheLongestNameWinning() {
    assertThat(TurnRunner.addressee("@Kai how are my stocks?", agents)).isSameAs(kai);
    assertThat(TurnRunner.addressee("@kai tax, what do I owe?", agents)).isSameAs(kaiTax);
    assertThat(TurnRunner.addressee("@kai_tax what do I owe?", agents)).isSameAs(kaiTax);
    assertThat(TurnRunner.addressee("@Kai", agents)).isSameAs(kai);
  }

  @Test
  void aNameThatOnlyStartsTheSameOrSitsMidMessageIsNotAMention() {
    assertThat(TurnRunner.addressee("@Kaitlyn said hi", agents)).isNull();
    assertThat(TurnRunner.addressee("Ask @Kai about it", agents)).isNull();
    assertThat(TurnRunner.addressee("@", agents)).isNull();
    assertThat(TurnRunner.addressee(null, agents)).isNull();
  }

  @Test
  void theMentionIdRidesInTheContextButIsNotShownAsPartOfTheScreen() {
    UUID id = UUID.randomUUID();
    var context = Json.obj().put("mention", id.toString()).put("stepId", "n1");
    assertThat(TurnRunner.mentionOf(context)).isEqualTo(id);
    assertThat(TurnRunner.screenContext(context).toString()).isEqualTo("{\"stepId\":\"n1\"}");
    assertThat(TurnRunner.mentionOf(Json.obj().put("mention", "not-an-id"))).isNull();
    assertThat(TurnRunner.mentionOf(null)).isNull();
  }
}

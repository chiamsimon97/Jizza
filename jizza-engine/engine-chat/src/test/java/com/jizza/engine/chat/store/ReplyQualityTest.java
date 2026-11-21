package com.jizza.engine.chat.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.persistence.entity.AgentEntity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReplyQualityTest {

  private static AgentEntity agent(String key, String name) {
    AgentEntity a = new AgentEntity();
    a.setId(UUID.randomUUID());
    a.setKey(key);
    a.setName(name);
    return a;
  }

  private static DefaultConversationStore.RatedRow row(String rating, String reason, UUID lead) {
    return new DefaultConversationStore.RatedRow(UUID.randomUUID(), UUID.randomUUID(), "Chat",
        "A reply  that is\nquite long", rating, reason, Instant.now(), lead);
  }

  @Test
  void eachAgentsThumbsAreCountedMostThumbsDownFirst() {
    AgentEntity jizza = agent("jizza", "Jizza");
    AgentEntity kai = agent("kai", "Kai");
    UUID gone = UUID.randomUUID();
    var quality = DefaultConversationStore.rated(30, List.of(
        row("up", null, null),
        row("down", "Too long", kai.getId()),
        row("down", null, kai.getId()),
        row("up", "Clear", kai.getId()),
        row("down", null, gone)), jizza, Map.of(kai.getId(), kai));

    assertThat(quality.agents()).extracting("name", "up", "down").containsExactly(
        org.assertj.core.groups.Tuple.tuple("Kai", 1, 2),
        org.assertj.core.groups.Tuple.tuple("An agent since removed", 0, 1),
        org.assertj.core.groups.Tuple.tuple("Jizza", 1, 0));
    assertThat(quality.recent()).hasSize(5);
    assertThat(quality.recent().get(1).reason()).isEqualTo("Too long");
    assertThat(quality.recent().get(1).agentName()).isEqualTo("Kai");
    assertThat(quality.recent().get(0).excerpt()).isEqualTo("A reply that is quite long");
  }

  @Test
  void onlyTheLatestRatedRepliesAreListed() {
    List<DefaultConversationStore.RatedRow> rows = java.util.stream.IntStream.range(0, 30)
        .mapToObj(i -> row("down", null, null)).toList();
    var quality = DefaultConversationStore.rated(30, rows, agent("jizza", "Jizza"), Map.of());
    assertThat(quality.recent()).hasSize(DefaultConversationStore.RECENT_RATED);
    assertThat(quality.agents().get(0).down()).isEqualTo(30);
  }

  @Test
  void anExcerptIsFlatAndBounded() {
    assertThat(DefaultConversationStore.excerpt("one\n\ntwo", 20)).isEqualTo("one two");
    assertThat(DefaultConversationStore.excerpt("x".repeat(50), 10)).hasSize(10).endsWith("…");
    assertThat(DefaultConversationStore.excerpt(null, 10)).isEmpty();
  }
}

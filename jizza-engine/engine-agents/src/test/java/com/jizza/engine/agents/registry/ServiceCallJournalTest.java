package com.jizza.engine.agents.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.util.Json;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServiceCallJournalTest {

  private Instant now = Instant.parse("2026-10-22T10:00:00Z");
  private final ServiceCallJournal journal = new ServiceCallJournal(new Clock() {
    @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  });
  private final UUID conversation = UUID.randomUUID();

  @Test
  void keepsEachConversationsCallsInOrderWithACopyOfTheirArguments() {
    var args = Json.obj().put("metal", "gold");
    journal.record(conversation, UUID.randomUUID(), "Kai", "metals_get_rate", args);
    journal.record(conversation, UUID.randomUUID(), "Kai", "mail_send", null);
    args.put("metal", "changed later");

    var calls = journal.recent(conversation);

    assertThat(calls).extracting(ServiceCallJournal.Call::tool)
        .containsExactly("metals_get_rate", "mail_send");
    assertThat(calls.get(0).arguments().path("metal").asText()).isEqualTo("gold");
    assertThat(calls.get(1).arguments()).as("hidden arguments stay hidden").isNull();
    assertThat(journal.recent(UUID.randomUUID())).isEmpty();
  }

  @Test
  void aCallOutsideAConversationIsNotKept() {
    journal.record(null, UUID.randomUUID(), "Kai", "metals_get_rate", Json.obj());

    assertThat(journal.recent(null)).isEmpty();
  }

  @Test
  void keepsOnlyTheLatestCallsAndForgetsAQuietConversation() {
    for (int i = 0; i < ServiceCallJournal.PER_CONVERSATION + 5; i++) {
      journal.record(conversation, null, "Kai", "call_" + i, Json.obj());
    }
    assertThat(journal.recent(conversation)).hasSize(ServiceCallJournal.PER_CONVERSATION)
        .first().extracting(ServiceCallJournal.Call::tool).isEqualTo("call_5");

    now = now.plus(ServiceCallJournal.KEEP).plus(Duration.ofMinutes(1));
    assertThat(journal.recent(conversation)).isEmpty();
  }
}

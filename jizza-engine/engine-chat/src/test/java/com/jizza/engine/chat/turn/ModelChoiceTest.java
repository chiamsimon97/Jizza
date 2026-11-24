package com.jizza.engine.chat.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.activity.ActivityTypes;
import com.jizza.engine.api.chat.JobEventDto;
import com.jizza.engine.api.orchestrator.Orchestrator;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.ConversationEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The chat's own model and depth, which model answered, and who else worked on a reply. */
class ModelChoiceTest {

  private static JobEventDto usage(int seq, UUID agentId, String name, String model, String label,
                                   double cost) {
    return new JobEventDto(seq, ActivityTypes.AGENT_USAGE, agentId, name, Json.obj()
        .put("agentKey", name.toLowerCase()).put("model", model).put("modelLabel", label)
        .put("inputTokens", 100).put("outputTokens", 20).put("cost", cost)
        .put("ownKey", model.contains(":")), Instant.now());
  }

  @Test
  void theChatsChoiceFillsInWhatTheMessageDidNotSay() {
    ConversationEntity chat = new ConversationEntity();
    chat.setModel("openai:gpt-5.1");
    chat.setDepth("deep");

    assertThat(TurnRunner.withChoice(null, chat).toString())
        .isEqualTo("{\"model\":\"openai:gpt-5.1\",\"depth\":\"deep\"}");
    // "Ask another model" or "Try again with a deeper model" say their own.
    assertThat(TurnRunner.withChoice(Json.obj().put("model", "claude-opus-5-5"), chat).path("model").asText())
        .isEqualTo("claude-opus-5-5");
    assertThat(TurnRunner.withChoice(Json.obj().put("a", 1), new ConversationEntity()).toString())
        .isEqualTo("{\"a\":1}");
  }

  @Test
  void theReplySaysWhichModelAnsweredAndWhoHelped() {
    UUID kai = UUID.randomUUID();
    UUID vita = UUID.randomUUID();
    UUID jizza = UUID.randomUUID();
    List<JobEventDto> events = List.of(
        usage(1, kai, "Kai", "openai:gpt-5.1", "GPT-5.1", 0.01),
        usage(2, vita, "Vita", "claude-sonnet-5-5", "Claude Sonnet 5.5", 0.02),
        usage(3, jizza, "Jizza", "claude-haiku-5-5", "Claude Haiku 5.5", 0.001));

    var model = TurnRunner.answeredWith(events);
    assertThat(model.path("label").asText()).isEqualTo("Claude Haiku 5.5");
    assertThat(model.has("ownKey")).isFalse();

    var team = TurnRunner.team(events, List.of(
        new Orchestrator.Delegation(jizza, kai, "Kai", "Check the EMI", "done"),
        new Orchestrator.Delegation(jizza, vita, "Vita", "Check sleep", "done")));
    assertThat(team).hasSize(2);
    assertThat(team.get(0).path("name").asText()).isEqualTo("Kai");
    assertThat(team.get(0).path("model").asText()).isEqualTo("GPT-5.1");
    assertThat(team.get(0).path("ownKey").asBoolean()).isTrue();
    assertThat(team.get(0).path("task").asText()).isEqualTo("Check the EMI");
    assertThat(team.get(1).path("cost").asDouble()).isEqualTo(0.02);
  }

  @Test
  void aReplyNobodyHelpedWithHasNoTeam() {
    assertThat(TurnRunner.team(List.of(usage(1, UUID.randomUUID(), "Jizza", "m", "M", 0)), List.of())).isEmpty();
    assertThat(TurnRunner.answeredWith(List.of())).isNull();
  }

  @Test
  void earlierAnswersKeepWhichModelWroteThem() {
    var context = Json.obj();
    context.putArray("versions").addObject().put("text", "First")
        .putObject("model").put("ref", "openai:gpt-5.1").put("label", "GPT-5.1").put("ownKey", true);
    assertThat(TurnRunner.earlierVersions(context).get(0).path("model").toString())
        .isEqualTo("{\"ref\":\"openai:gpt-5.1\",\"label\":\"GPT-5.1\",\"ownKey\":true}");
  }
}

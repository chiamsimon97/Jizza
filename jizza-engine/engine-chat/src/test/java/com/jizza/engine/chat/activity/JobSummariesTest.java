package com.jizza.engine.chat.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jizza.engine.api.activity.ActivityTypes;
import com.jizza.engine.api.chat.JobEventDto;
import com.jizza.engine.api.util.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobSummariesTest {

  private final List<JobEventDto> events = new ArrayList<>();

  private void add(String type, String agent, ObjectNode payload) {
    events.add(new JobEventDto(events.size() + 1, type, null, agent, payload, Instant.now()));
  }

  @Test
  void aTurnWithNothingButAnAnswerSaysSo() {
    add(ActivityTypes.JOB_STARTED, null, Json.obj());
    assertThat(JobSummaries.summarize(events)).isEqualTo("Answered directly.");
  }

  @Test
  void describesWhoWasAskedWhatWasCalledAndWhatWasRemembered() {
    add(ActivityTypes.MEMORY_USED, "Jizza", Json.obj().put("count", 3));
    add(ActivityTypes.AGENT_MESSAGE, "Jizza", Json.obj().put("kind", "task").put("toAgentName", "Kai"));
    add(ActivityTypes.MEMORY_USED, "Kai", Json.obj().put("count", 1));
    add(ActivityTypes.TOOL_CALL, "Kai", Json.obj().put("state", "started").put("tool", "zerodha_holdings"));
    add(ActivityTypes.TOOL_API, null, Json.obj().put("service", "Zerodha").put("method", "GET")
        .put("path", "/portfolio/holdings"));
    add(ActivityTypes.TOOL_CALL, "Kai", Json.obj().put("state", "done").put("tool", "zerodha_holdings"));
    add(ActivityTypes.AGENT_MESSAGE, "Kai", Json.obj().put("kind", "answer").put("toAgentName", "Jizza"));

    assertThat(JobSummaries.summarize(events))
        .isEqualTo("Asked Kai. Kai called Zerodha (GET /portfolio/holdings). Used 4 memories.");
  }

  @Test
  void endsWithWhyItStoppedAndWhatIsWaitingOnTheUser() {
    add(ActivityTypes.APPROVAL_REQUIRED, "Cato", Json.obj().put("label", "Send email"));
    add(ActivityTypes.JOB_CANCELLED, null, Json.obj());
    assertThat(JobSummaries.summarize(events))
        .isEqualTo("Waiting for your approval: Send email. Stopped by you.");
  }

  @Test
  void titlesAreOneLineAndBounded() {
    assertThat(JobSummaries.title("Plan my\n  wedding budget")).isEqualTo("Plan my wedding budget");
    assertThat(JobSummaries.title("x".repeat(200))).hasSize(80).endsWith("…");
  }
}

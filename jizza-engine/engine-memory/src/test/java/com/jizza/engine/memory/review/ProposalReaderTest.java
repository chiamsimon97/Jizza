package com.jizza.engine.memory.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.MemoryStatus;
import com.jizza.engine.api.knowledge.KnowledgeEntry;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.entity.AgentEntity;
import com.jizza.engine.persistence.entity.MemoryEntryEntity;
import com.jizza.engine.persistence.support.MemoryReviewQueries.FailedStep;
import com.jizza.engine.persistence.support.MemoryReviewQueries.FailedToolCall;
import com.jizza.engine.persistence.support.MemoryReviewQueries.UserMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The review's reply is only a suggestion: anything it made up or can't back is dropped. */
class ProposalReaderTest {

  private static final Instant NOW = Instant.parse("2026-11-27T03:00:00Z");

  private final AgentEntity jizza = agent("jizza", "Jizza", true);
  private final AgentEntity booker = agent("booker", "Booker", false);
  private final MemoryEntryEntity tea = fact("Likes tea", true);
  private final MemoryEntryEntity teaAgain = fact("Prefers tea over coffee", false);
  private final MemoryEntryEntity city = fact("Lives in Pune", true);
  private final UUID workflowId = UUID.randomUUID();

  private ReviewDossier dossier(List<KnowledgeEntry> bookerLessons) {
    UUID conversation = UUID.randomUUID();
    return ReviewDossier.of(List.of(tea, teaAgain, city), List.of(), Map.of(), List.of(jizza, booker),
        Map.of(booker.getId(), bookerLessons),
        List.of(new UserMessage(UUID.randomUUID(), conversation, "I moved to Bengaluru last month", NOW)),
        List.of(
            call("browser.click", "Timeout 30000ms exceeded waiting for #seat-12 on https://tickets.example.com/show"),
            call("browser.click", "Timeout 30000ms exceeded waiting for #seat-14 on https://tickets.example.com/show")),
        List.of(new FailedStep(UUID.randomUUID(), workflowId, "Book seats", booker.getId(), "n1",
            "Choose seat", "browser.click", "https://tickets.example.com/show", "element not visible", NOW)),
        400);
  }

  @Test
  void groupsTheSameFailureAndFindsItsSite() {
    ReviewDossier d = dossier(List.of());
    assertThat(d.tools).hasSize(1);
    ReviewDossier.ToolFailures t = d.tools.get("t1");
    assertThat(t.calls()).hasSize(2);
    assertThat(t.host()).isEqualTo("tickets.example.com");
    assertThat(d.steps.get("s1").host()).isEqualTo("tickets.example.com");
    assertThat(d.isEmpty()).isFalse();
  }

  @Test
  void mergeKeepsTheConfirmedFactAndUpdateNeedsWhatTheUserSaid() {
    ReviewDossier d = dossier(List.of());
    JsonNode reply = Json.read("""
        {"proposals":[
          {"kind":"merge","facts":["f1","f2"],"content":"Likes tea, not coffee","reason":"Same thing twice"},
          {"kind":"update","facts":["f3"],"content":"Lives in Bengaluru","evidence":["m1"]},
          {"kind":"update","facts":["f3"],"content":"Lives in Mumbai"},
          {"kind":"retire","facts":["f9"],"evidence":["m1"]},
          {"kind":"confirm","facts":["f1"],"evidence":["m1"]}
        ]}""");
    List<ProposalReader.Drafted> out = ProposalReader.read(reply, d, 500, 8);

    assertThat(out).extracting(ProposalReader.Drafted::kind).containsExactly("merge", "update");
    ProposalReader.Drafted merge = out.get(0);
    assertThat(merge.payload().path("keep").asText()).isEqualTo(tea.getId().toString());
    assertThat(merge.payload().path("factHashes").size()).isEqualTo(2);
    ProposalReader.Drafted update = out.get(1);
    assertThat(update.evidence().get(0).path("kind").asText()).isEqualTo("chat");
    assertThat(update.evidence().get(0).path("link").asText()).startsWith("/chat?conversationId=");
  }

  @Test
  void lessonGoesToTheAgentThatFailedAndIsAboutARealSite() {
    ReviewDossier d = dossier(List.of());
    JsonNode reply = Json.read("""
        {"proposals":[
          {"kind":"lesson","about":{"kind":"site","ref":"tickets.example.com"},"evidence":["t1","s1"],
           "content":"On tickets.example.com, wait for the seat map to load before clicking a seat."},
          {"kind":"lesson","about":{"kind":"site","ref":"made-up.example.org"},"evidence":["t1"],
           "content":"Something about a site the review never saw."},
          {"kind":"lesson","about":{"kind":"general"},"evidence":["m1"],
           "content":"A lesson with no failure behind it."}
        ]}""");
    List<ProposalReader.Drafted> out = ProposalReader.read(reply, d, 500, 8);

    assertThat(out).hasSize(2);
    ProposalReader.Drafted lesson = out.get(0);
    assertThat(lesson.payload().path("agentId").asText()).isEqualTo(booker.getId().toString());
    assertThat(lesson.payload().path("aboutKey").asText()).isEqualTo("site:tickets.example.com");
    assertThat(lesson.occurrences()).isEqualTo(3);
    assertThat(lesson.evidence().get(1).path("link").asText())
        .isEqualTo("/workflows?open=" + workflowId + "&node=n1");
    // A site the review never saw falls back to the site of the failure it was learned from.
    assertThat(out.get(1).payload().path("aboutKey").asText()).isEqualTo("site:tickets.example.com");
  }

  @Test
  void skipsALessonTheAgentAlreadyKnowsAndRepeatedSuggestions() {
    String wording = "On tickets.example.com, wait for the seat map to load before clicking a seat.";
    KnowledgeEntry known = new KnowledgeEntry(UUID.randomUUID(), null, booker.getId(), "Lesson: tickets",
        wording, null, null, NOW, NOW, null, false,
        new KnowledgeEntry.Lesson("site:tickets.example.com", "tickets.example.com", 2));
    ReviewDossier d = dossier(List.of(known));
    JsonNode reply = Json.read("""
        {"proposals":[
          {"kind":"lesson","about":{"kind":"site","ref":"t1"},"evidence":["t1"],"content":"%s"},
          {"kind":"lesson","about":{"kind":"tool","ref":"t1"},"evidence":["t1"],"content":"Scroll the seat into view first."},
          {"kind":"lesson","about":{"kind":"tool","ref":"t1"},"evidence":["t1"],"content":"Scroll the seat into view first."}
        ]}""".formatted(wording));
    List<ProposalReader.Drafted> out = ProposalReader.read(reply, d, 500, 8);

    assertThat(out).hasSize(1);
    assertThat(out.getFirst().payload().path("aboutKey").asText()).isEqualTo("tool:browser.click");
  }

  @Test
  void neighboursAloneAreNothingNew() {
    ReviewDossier d = ReviewDossier.of(List.of(), List.of(tea), Map.of(), List.of(jizza), Map.of(),
        List.of(), List.of(), List.of(), 400);
    assertThat(d.isEmpty()).isTrue();
  }

  private FailedToolCall call(String tool, String error) {
    return new FailedToolCall(UUID.randomUUID(), UUID.randomUUID(), "Book two seats", booker.getId(),
        booker.getName(), tool, "Click", error, NOW);
  }

  private static AgentEntity agent(String key, String name, boolean orchestrator) {
    AgentEntity a = new AgentEntity();
    a.setId(UUID.randomUUID());
    a.setKey(key);
    a.setName(name);
    a.setOrchestrator(orchestrator);
    return a;
  }

  private static MemoryEntryEntity fact(String content, boolean confirmed) {
    MemoryEntryEntity f = new MemoryEntryEntity();
    f.setId(UUID.randomUUID());
    f.setContent(content);
    f.setConfirmed(confirmed);
    f.setStatus(MemoryStatus.ACTIVE);
    f.setContentHash(Integer.toHexString(content.hashCode()));
    f.setCreatedAt(NOW);
    f.setUpdatedAt(NOW);
    return f;
  }
}

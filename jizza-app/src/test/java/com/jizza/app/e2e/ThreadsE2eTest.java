package com.jizza.app.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.llm.LlmResponse;
import com.jizza.engine.api.tool.ToolCall;
import com.jizza.engine.api.tool.ToolSpec;
import com.jizza.engine.api.util.Json;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Threads: Jizza coordinates in the main chat and moves a topic into a thread led by the
 * specialist whose area it is; the lead answers there with the thread's guide, and a message off
 * the thread's topic goes back to the main chat, where Jizza opens the right thread for it.
 */
@org.junit.jupiter.api.condition.EnabledIf(
    value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class ThreadsE2eTest extends E2eSupport {

  private static final String PASSWORD = "Correct-Horse-92";

  @LocalServerPort private int port;
  @Autowired private E2eSupport.ScriptedLlmClient model;

  private ApiClient api;

  @BeforeEach
  void setUp() {
    api = new ApiClient(port);
    api.prime();
    model.reset();
    String email = "threads-" + UUID.randomUUID() + "@jizza.local";
    api.post("/api/auth/register", Map.of("email", email, "password", PASSWORD));
    api.post("/api/auth/login", Map.of("email", email, "password", PASSWORD));
  }

  /** A new main chat with threads turned on, the way the app starts one. */
  private String threadedChat() {
    var created = api.post("/api/conversations", Map.of("threads", true));
    assertThat(created.ok()).as(created.body()).isTrue();
    assertThat(created.json().path("threadsEnabled").asBoolean()).isTrue();
    return created.json().path("id").asText();
  }

  private static LlmResponse call(String tool, com.fasterxml.jackson.databind.node.ObjectNode args) {
    return new LlmResponse("", List.of(new ToolCall("call-" + tool, tool, args)), "tool_use", 10, 5, "m");
  }

  private static LlmResponse startThread(String lead, String title, String topic, String message) {
    return call("start_thread", Json.obj().put("lead", lead).put("title", title).put("topic", topic)
        .put("message", message));
  }

  @Test
  void aTopicMovesToItsSpecialistsThreadAndOffTopicMessagesComeBack() {
    String ask = "Help me start investing 500 a month in index funds";
    model.scriptFor("Jizza",
        startThread("finance", "Monthly index fund plan", "Investing 500 a month in index funds", ask),
        LlmResponse.text("Kai has taken this to a new thread."));
    model.scriptFor("Kai",
        call("note_in_thread_guide", Json.obj().put("section", "What we know").put("note", "Wants to invest 500 a month")),
        LlmResponse.text("Happy to help. What is the money for, and when will you need it?"));

    var reply = api.post("/api/chat", Map.of("message", ask, "conversationId", threadedChat()));
    assertThat(reply.ok()).as(reply.body()).isTrue();
    UUID mainId = UUID.fromString(reply.json().path("conversationId").asText());
    assertThat(reply.json().path("actions").toString()).contains("thread_opened").contains("Kai");

    // The thread is listed under the main chat, led by Kai.
    JsonNode threads = api.get("/api/conversations/" + mainId + "/threads").json();
    assertThat(threads).hasSize(1);
    String threadId = threads.get(0).path("id").asText();
    assertThat(threads.get(0).path("kind").asText()).isEqualTo("thread");
    assertThat(threads.get(0).path("lead").path("name").asText()).isEqualTo("Kai");

    // Kai answers the user's own message there, in its own name, with the thread's tools.
    JsonNode messages = awaitReply(threadId, 1);
    assertThat(messages.toString()).contains(ask);
    JsonNode answer = last(messages, "assistant");
    assertThat(answer.path("content").asText()).contains("What is the money for");
    assertThat(answer.path("metadata").path("lead").path("name").asText()).isEqualTo("Kai");
    // Kai asked the user something, so the thread is waiting on them.
    JsonNode all = api.get("/api/conversations/threads").json();
    assertThat(all).hasSize(1);
    assertThat(all.get(0).path("stage").asText()).isEqualTo("waiting");
    assertThat(api.get("/api/conversations/" + threadId + "/summary").json().path("stage").asText())
        .isEqualTo("waiting");
    assertThat(toolsOfferedTo("Kai")).contains("hand_back_to_jizza", "note_in_thread_guide")
        .doesNotContain("start_thread");
    assertThat(model.everythingTheModelSaw()).contains("<thread_guide>\n# Monthly index fund plan");

    // Its guide holds what Kai noted.
    JsonNode guide = api.get("/api/conversations/" + threadId + "/guide").json();
    assertThat(guide.path("markdown").asText()).contains("## What we know\n- Wants to invest 500 a month");
    assertThat(api.get("/api/conversations/" + threadId + "/guide/download").body())
        .startsWith("# Monthly index fund plan");

    // Something else entirely goes back to the main chat, and Jizza opens Vita's thread for it.
    String offTopic = "Also, book me a dentist appointment next week";
    model.scriptFor("Kai",
        call("hand_back_to_jizza", Json.obj().put("message", offTopic).put("reason", "a health appointment")),
        LlmResponse.text("That's one for Jizza; it will pick it up in the main chat."));
    model.scriptFor("Jizza",
        startThread("health", "Dentist appointment", "Booking a dentist appointment next week", offTopic),
        LlmResponse.text("Vita is on it in a new thread."));
    model.scriptFor("Vita", LlmResponse.text("Which days next week suit you?"));

    var inThread = api.post("/api/chat", Map.of("message", offTopic, "conversationId", threadId));
    assertThat(inThread.ok()).as(inThread.body()).isTrue();
    assertThat(inThread.json().path("actions").toString()).contains("thread_handback");

    JsonNode both = await(() -> api.get("/api/conversations/" + mainId + "/threads").json(), t -> t.size() == 2);
    JsonNode vitas = null;
    for (JsonNode t : both) if ("Vita".equals(t.path("lead").path("name").asText())) vitas = t;
    assertThat(vitas).as(both.toString()).isNotNull();
    JsonNode vitaMessages = awaitReply(vitas.path("id").asText(), 1);
    assertThat(last(vitaMessages, "assistant").path("content").asText()).contains("Which days");

    // The main chat shows the hand-back and the user's message, answered by Jizza.
    JsonNode main = api.get("/api/conversations/" + mainId + "/messages").json();
    assertThat(main.toString()).contains("Kai passed this back").contains(offTopic)
        .contains("Vita is on it in a new thread.");
  }

  @Test
  void aSpecialistJizzaDelegatesToGetsNoThreadTools() {
    model.scriptFor("Jizza",
        call("delegate_to_specialist", Json.obj().put("agent_key", "health").put("task", "How many steps is healthy?")),
        LlmResponse.text("Vita says about 8,000 a day."));
    model.scriptFor("Vita", LlmResponse.text("About 8,000 a day."));

    var reply = api.post("/api/chat", Map.of("message", "How many steps a day is healthy?",
        "conversationId", threadedChat()));

    assertThat(reply.ok()).as(reply.body()).isTrue();
    assertThat(toolsOfferedTo("Jizza")).contains("start_thread");
    assertThat(model.everythingTheModelSaw()).contains("Threads are on for this chat");
    assertThat(toolsOfferedTo("Vita")).doesNotContain("start_thread", "hand_back_to_jizza");
  }

  @Test
  void theUserCanEditAGuideAndOnlyAThreadHasOne() {
    String ask = "Let's plan my sleep routine";
    model.scriptFor("Jizza", startThread("health", "Sleep routine", "A better sleep routine", ask),
        LlmResponse.text("Vita has this."));
    model.scriptFor("Vita", LlmResponse.text("What time do you go to bed now?"));
    var reply = api.post("/api/chat", Map.of("message", ask, "conversationId", threadedChat()));
    UUID mainId = UUID.fromString(reply.json().path("conversationId").asText());
    String threadId = api.get("/api/conversations/" + mainId + "/threads").json().get(0).path("id").asText();

    var edited = api.put("/api/conversations/" + threadId + "/guide", Map.of("markdown", "# Sleep routine\n\n- Bed by 11"));
    assertThat(edited.ok()).as(edited.body()).isTrue();
    assertThat(edited.json().path("source").asText()).isEqualTo("user");
    assertThat(api.get("/api/conversations/" + threadId + "/guide").json().path("markdown").asText())
        .contains("Bed by 11");
    assertThat(api.get("/api/conversations/" + mainId + "/guide").status()).isEqualTo(404);

    // Resolving a thread archives it; it stays listed, as resolved.
    awaitReply(threadId, 1);
    assertThat(api.patch("/api/conversations/" + threadId, Map.of("archived", true)).ok()).isTrue();
    assertThat(api.get("/api/conversations/" + mainId + "/threads").json().get(0).path("stage").asText())
        .isEqualTo("resolved");
    assertThat(api.get("/api/conversations/" + threadId + "/summary").json().path("parentId").asText())
        .isEqualTo(mainId.toString());
  }

  @Test
  void aPlainChatHasNoThreadsUntilTheUserTurnsThemOn() {
    model.scriptFor("Jizza", LlmResponse.text("Vitamin D at 19.8 is low."));
    var reply = api.post("/api/chat", Map.of("message", "Is my vitamin D low?"));
    assertThat(reply.ok()).as(reply.body()).isTrue();
    String mainId = reply.json().path("conversationId").asText();
    assertThat(toolsOfferedTo("Jizza")).doesNotContain("start_thread", "continue_in_thread");
    assertThat(model.everythingTheModelSaw()).doesNotContain("Threads are on for this chat");
    assertThat(api.get("/api/conversations/" + mainId + "/summary").json().path("threadsEnabled").asBoolean())
        .isFalse();

    var turnedOn = api.patch("/api/conversations/" + mainId, Map.of("threads", true));
    assertThat(turnedOn.ok()).as(turnedOn.body()).isTrue();
    assertThat(turnedOn.json().path("threadsEnabled").asBoolean()).isTrue();
    model.reset();
    model.scriptFor("Jizza", LlmResponse.text("Noted."));
    api.post("/api/chat", Map.of("message", "Thanks", "conversationId", mainId));
    assertThat(toolsOfferedTo("Jizza")).contains("start_thread");
  }

  private List<String> toolsOfferedTo(String agentName) {
    List<String> names = new ArrayList<>();
    synchronized (model.requests) {
      for (int i = 0; i < model.requests.size(); i++) {
        var first = model.requests.get(i).stream()
            .filter(m -> m.text() != null && m.text().startsWith("Your name is " + agentName + "."))
            .findFirst();
        if (first.isPresent()) model.offeredTools.get(i).stream().map(ToolSpec::name).forEach(names::add);
      }
    }
    return names;
  }

  /** The conversation once it holds {@code replies} finished replies. */
  private JsonNode awaitReply(String conversationId, int replies) {
    return await(() -> api.get("/api/conversations/" + conversationId + "/messages").json(), m -> {
      int done = 0;
      for (JsonNode x : m) {
        if ("assistant".equals(x.path("role").asText()) && "complete".equals(x.path("status").asText())) done++;
      }
      return done >= replies;
    });
  }

  private static JsonNode last(JsonNode messages, String role) {
    JsonNode found = null;
    for (JsonNode m : messages) if (role.equals(m.path("role").asText())) found = m;
    return found;
  }

  private static JsonNode await(java.util.function.Supplier<JsonNode> read, Predicate<JsonNode> done) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
    JsonNode value = null;
    while (Instant.now().isBefore(deadline)) {
      value = read.get();
      if (done.test(value)) return value;
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new AssertionError("never reached the expected state: " + value);
  }
}

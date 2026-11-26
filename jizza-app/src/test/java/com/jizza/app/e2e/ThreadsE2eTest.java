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
  @Autowired private com.jizza.engine.chat.config.ChatProperties chat;

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
    JsonNode ours = api.get("/api/conversations/" + mainId + "/threads").json();
    assertThat(ours).hasSize(1);
    assertThat(ours.get(0).path("stage").asText()).isEqualTo("waiting");
    // A thread is reached from its own chat: the conversation list shows main chats only.
    assertThat(api.get("/api/conversations").json().toString()).doesNotContain(threadId);
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
  void withThreadsOnJizzaReachesSpecialistsOnlyThroughThreads() {
    model.scriptFor("Jizza", LlmResponse.text("Hello! What shall we work on?"));

    var reply = api.post("/api/chat", Map.of("message", "Hi", "conversationId", threadedChat()));

    assertThat(reply.ok()).as(reply.body()).isTrue();
    assertThat(toolsOfferedTo("Jizza")).contains("start_thread")
        .doesNotContain("delegate_to_specialist", "delegate_to_specialists");
    assertThat(model.everythingTheModelSaw()).contains("Threads are on for this chat")
        .contains("One thread per purpose");
  }

  @Test
  void aThreadOfOneChatCannotBeContinuedFromAnother() {
    String ask = "Plan my monthly investing";
    model.scriptFor("Jizza", startThread("finance", "Monthly investing", "Monthly investing plan", ask),
        LlmResponse.text("Kai has this."));
    model.scriptFor("Kai", LlmResponse.text("How much a month?"));
    UUID firstMain = UUID.fromString(api.post("/api/chat", Map.of("message", ask, "conversationId", threadedChat()))
        .json().path("conversationId").asText());
    String threadId = api.get("/api/conversations/" + firstMain + "/threads").json().get(0).path("id").asText();
    awaitReply(threadId, 1);

    model.reset();
    model.scriptFor("Jizza",
        call("continue_in_thread", Json.obj().put("thread_id", threadId).put("message", "500 a month")),
        LlmResponse.text("Let me start a thread for that here instead."));
    var other = api.post("/api/chat", Map.of("message", "500 a month", "conversationId", threadedChat()));

    assertThat(other.ok()).as(other.body()).isTrue();
    // The other chat's thread is not offered, and handing a message to it is refused.
    assertThat(model.everythingTheModelSaw()).doesNotContain(threadId + ": \"Monthly investing\"");
    // This chat has no threads of its own, so there is nothing to continue: the call is refused.
    assertThat(toolsOfferedTo("Jizza")).doesNotContain("continue_in_thread");
    assertThat(model.everythingTheModelSaw()).contains("Tool 'continue_in_thread' is not assigned");
    assertThat(awaitReply(threadId, 1).toString()).doesNotContain("500 a month");
  }

  @Test
  void jizzaRoutesEachPurposeToItsOwnThreadAndFollowUpsBackToIt() {
    String mainId = threadedChat();
    model.scriptFor("Jizza Router", LlmResponse.text("""
        {"route":"new","lead":"finance","title":"My investing plan","topic":"The user's own plan to invest 500 a month"}"""));
    model.scriptFor("Kai", LlmResponse.text("What is your timeline?"));
    var first = api.post("/api/chat", Map.of("message", "Help me invest 500 a month", "conversationId", mainId));
    assertThat(first.ok()).as(first.body()).isTrue();
    assertThat(first.json().path("text").asText()).contains("opened a thread with Kai");
    assertThat(toolsOfferedTo("Jizza")).isEmpty();

    // The same specialist, another person: a thread of its own.
    model.scriptFor("Jizza Router", LlmResponse.text("""
        {"route":"new","lead":"finance","title":"Investing plan for Dad","topic":"An investment plan for the user's father"}"""));
    model.scriptFor("Kai", LlmResponse.text("How much does your dad want to invest?"));
    api.post("/api/chat", Map.of("message", "Also plan investing for my dad", "conversationId", mainId));
    JsonNode two = await(() -> api.get("/api/conversations/" + mainId + "/threads").json(), t -> t.size() == 2);
    String mine = null;
    for (JsonNode t : two) {
      assertThat(t.path("lead").path("name").asText()).isEqualTo("Kai");
      if ("My investing plan".equals(t.path("title").asText())) mine = t.path("id").asText();
    }
    assertThat(mine).isNotNull();
    awaitReply(mine, 1);

    // A follow-up goes back to the thread it belongs to; no third thread.
    model.scriptFor("Jizza Router", LlmResponse.text("{\"route\":\"thread\",\"thread_id\":\"" + mine + "\"}"));
    model.scriptFor("Kai", LlmResponse.text("We settled on an index fund."));
    var followUp = api.post("/api/chat", Map.of("message", "What did we decide for me?", "conversationId", mainId));
    assertThat(followUp.json().path("text").asText()).contains("Kai is picking this up");
    assertThat(awaitReply(mine, 2).toString()).contains("What did we decide for me?");
    assertThat(api.get("/api/conversations/" + mainId + "/threads").json()).hasSize(2);
  }

  @Test
  void aMoneyMessageInAPlainChatIsAnsweredByKai() {
    chat.getSpecialists().setEnabled(true);
    try {
      var created = api.post("/api/conversations", Map.of("threads", false));
      assertThat(created.ok()).as(created.body()).isTrue();
      String chatId = created.json().path("id").asText();
      model.scriptFor("Jizza Specialist Router", LlmResponse.text("{\"answer\":\"finance\"}"),
          LlmResponse.text("{\"answer\":\"jizza\"}"));
      model.scriptFor("Kai", LlmResponse.text("Check the goal below and press Save."));
      model.scriptFor("Jizza", LlmResponse.text("You're welcome!"));

      var goal = api.post("/api/chat", Map.of("message",
          "Want a goal to buy 1L of silver by end of next year", "conversationId", chatId));
      assertThat(goal.ok()).as(goal.body()).isTrue();
      assertThat(goal.json().path("text").asText()).isEqualTo("Check the goal below and press Save.");
      assertThat(awaitReply(chatId, 1).toString()).contains("\"key\":\"finance\"");
      // Kai answered with its own tools; Jizza was never asked.
      assertThat(toolsOfferedTo("Jizza")).isEmpty();

      var thanks = api.post("/api/chat", Map.of("message", "Thanks!", "conversationId", chatId));
      assertThat(thanks.json().path("text").asText()).isEqualTo("You're welcome!");
    } finally {
      chat.getSpecialists().setEnabled(false);
    }
  }

  @Test
  void anEditedMessageReplacesItAndEverythingAfter() {
    model.scriptFor("Jizza", LlmResponse.text("Paris is lovely in spring."), LlmResponse.text("Rome is lovely in spring."));
    var first = api.post("/api/chat", Map.of("message", "Plan a trip to Paris"));
    String chat = first.json().path("conversationId").asText();
    JsonNode before = awaitReply(chat, 1);
    String userMessageId = null;
    for (JsonNode m : before) if ("user".equals(m.path("role").asText())) userMessageId = m.path("id").asText();

    var rewound = api.post("/api/conversations/" + chat + "/messages/" + userMessageId + "/rewind", Map.of());
    assertThat(rewound.status()).as(rewound.body()).isEqualTo(204);
    assertThat(api.get("/api/conversations/" + chat + "/messages").json()).isEmpty();

    api.post("/api/chat", Map.of("message", "Plan a trip to Rome", "conversationId", chat));
    JsonNode after = awaitReply(chat, 1);
    assertThat(after.toString()).contains("Plan a trip to Rome").doesNotContain("Paris");
    assertThat(api.get("/api/conversations/" + chat + "/learned").ok()).isTrue();
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

  @Test
  void aSpecialistNamedWithAnAtAnswersTheMessageInTheMainChat() {
    model.scriptFor("Vita", LlmResponse.text("At 19.8, your vitamin D is low."));
    var reply = api.post("/api/chat", Map.of("message", "@Vita is my vitamin D low?"));
    assertThat(reply.ok()).as(reply.body()).isTrue();
    JsonNode messages = awaitReply(reply.json().path("conversationId").asText(), 1);
    JsonNode answer = last(messages, "assistant");
    assertThat(answer.path("content").asText()).contains("vitamin D is low");
    assertThat(answer.path("metadata").path("lead").path("name").asText()).isEqualTo("Vita");
    assertThat(toolsOfferedTo("Jizza")).isEmpty();
  }

  @Test
  void aRatedReplyTeachesItsThreadAndShowsOnTheQualityList() {
    String ask = "Let's plan my sleep routine";
    model.scriptFor("Jizza", startThread("health", "Sleep routine", "A better sleep routine", ask),
        LlmResponse.text("Vita has this."));
    model.scriptFor("Vita", LlmResponse.text("Sleep more."));
    var reply = api.post("/api/chat", Map.of("message", ask, "conversationId", threadedChat()));
    UUID mainId = UUID.fromString(reply.json().path("conversationId").asText());
    String threadId = api.get("/api/conversations/" + mainId + "/threads").json().get(0).path("id").asText();
    String replyId = last(awaitReply(threadId, 1), "assistant").path("id").asText();

    var rated = api.put("/api/conversations/" + threadId + "/messages/" + replyId + "/feedback",
        Map.of("rating", "down", "reason", "Too vague, give me times"));
    assertThat(rated.status()).as(rated.body()).isEqualTo(204);
    assertThat(last(api.get("/api/conversations/" + threadId + "/messages").json(), "assistant")
        .path("metadata").path("feedback").path("rating").asText()).isEqualTo("down");
    assertThat(api.get("/api/conversations/" + threadId + "/guide").json().path("markdown").asText())
        .contains("Too vague, give me times");

    JsonNode quality = api.get("/api/conversations/feedback?days=30").json();
    assertThat(quality.path("agents").get(0).path("name").asText()).isEqualTo("Vita");
    assertThat(quality.path("agents").get(0).path("down").asInt()).isEqualTo(1);
    assertThat(quality.path("recent").get(0).path("reason").asText()).isEqualTo("Too vague, give me times");

    assertThat(api.put("/api/conversations/" + threadId + "/messages/" + replyId + "/feedback",
        Map.of("rating", "sideways")).status()).isEqualTo(400);
    assertThat(api.put("/api/conversations/" + threadId + "/messages/" + replyId + "/feedback",
        Map.of("rating", "none")).status()).isEqualTo(204);
    assertThat(last(api.get("/api/conversations/" + threadId + "/messages").json(), "assistant")
        .path("metadata").has("feedback")).isFalse();
    assertThat(api.get("/api/conversations/feedback").json().path("agents")).isEmpty();
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

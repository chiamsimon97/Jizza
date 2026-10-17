package com.jizza.engine.chat.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.enums.MessageRole;
import com.jizza.engine.api.llm.Msg;
import com.jizza.engine.persistence.entity.MessageEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContextWindowTest {

  private static MessageEntity message(MessageRole role, String text, String status) {
    MessageEntity m = new MessageEntity();
    m.setId(UUID.randomUUID());
    m.setRole(role);
    m.setContent(text);
    m.setStatus(status);
    m.setCreatedAt(Instant.now());
    return m;
  }

  private static MessageEntity said(MessageRole role, String text) {
    return message(role, text, MessageEntity.COMPLETE);
  }

  /** Ten exchanges of about 29 estimated tokens each message, oldest first. */
  private static List<MessageEntity> chat() {
    List<MessageEntity> out = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      out.add(said(MessageRole.USER, "Question " + i + " " + "x".repeat(90)));
      out.add(said(MessageRole.ASSISTANT, "Answer " + i + " " + "y".repeat(90)));
    }
    return out;
  }

  private static List<MessageEntity> newestFirst(List<MessageEntity> oldestFirst) {
    List<MessageEntity> copy = new ArrayList<>(oldestFirst);
    Collections.reverse(copy);
    return copy;
  }

  @Test
  void keepsTheNewestMessagesThatFitOldestFirst() {
    List<MessageEntity> all = chat();
    List<Msg> window = ContextWindow.build(null, newestFirst(all), null, 100);

    assertThat(window).hasSize(3);
    assertThat(window.get(0).text()).startsWith("Answer 8");
    assertThat(window.get(2).text()).startsWith("Answer 9");
  }

  @Test
  void leavesOutTheMessageBeingAnsweredEventsAndUnfinishedReplies() {
    MessageEntity asked = said(MessageRole.USER, "now this");
    List<MessageEntity> all = new ArrayList<>(List.of(
        said(MessageRole.USER, "hello"),
        said(MessageRole.ASSISTANT, "hi"),
        message(MessageRole.EVENT, "You approved Cato's request", MessageEntity.COMPLETE),
        asked,
        message(MessageRole.ASSISTANT, "", MessageEntity.STREAMING)));

    List<Msg> window = ContextWindow.build(null, newestFirst(all), asked.getId(), 10_000);

    assertThat(window).extracting(Msg::text).containsExactly("hello", "hi");
  }

  @Test
  void aSummaryLeadsTheWindowAndTakesItsShareOfTheBudget() {
    List<MessageEntity> all = chat();
    String summary = "The user is renewing car insurance for a 2019 Civic.";

    List<Msg> window = ContextWindow.build(summary, newestFirst(all), null, 100);

    assertThat(window.get(0).role()).isEqualTo(Msg.Role.USER);
    assertThat(window.get(0).text()).startsWith(ContextWindow.SUMMARY_LEAD).endsWith(summary);
    assertThat(window.get(1).role()).isEqualTo(Msg.Role.ASSISTANT);
    // The same 100 tokens held three messages without a summary; with one, fewer fit.
    assertThat(window.size() - 2).isBetween(1, 2);
    assertThat(window.get(window.size() - 1).text()).startsWith("Answer 9");
  }

  @Test
  void foldsNothingWhileItFitsThenEnoughToLeaveHalfTheBudget() {
    List<MessageEntity> all = chat();
    assertThat(ContextWindow.toFold(all, 10_000)).isZero();

    int fold = ContextWindow.toFold(all, 200);
    int left = all.subList(fold, all.size()).stream().mapToInt(m -> ContextWindow.tokens(m.getContent())).sum();
    assertThat(left).isLessThanOrEqualTo(100);
    assertThat(fold).isLessThan(all.size());
  }

  @Test
  void neverFoldsPastAReplyStillBeingWritten() {
    List<MessageEntity> all = new ArrayList<>(chat());
    all.add(2, message(MessageRole.ASSISTANT, "", MessageEntity.STREAMING));

    assertThat(ContextWindow.toFold(all, 50)).isEqualTo(2);
  }

  @Test
  void titlesAreTidiedAndSentencesRejected() {
    assertThat(ConversationUpkeep.cleanTitle("\"Wedding budget plan.\"")).isEqualTo("Wedding budget plan");
    assertThat(ConversationUpkeep.cleanTitle("Title: Car insurance renewal\nextra")).isEqualTo("Car insurance renewal");
    assertThat(ConversationUpkeep.cleanTitle("**Tax filing**")).isEqualTo("Tax filing");
    assertThat(ConversationUpkeep.cleanTitle("I can't title this conversation")).isNull();
    assertThat(ConversationUpkeep.cleanTitle("   ")).isNull();
    assertThat(ConversationUpkeep.cleanTitle("one two three four five six seven eight nine ten eleven")).isNull();
  }
}

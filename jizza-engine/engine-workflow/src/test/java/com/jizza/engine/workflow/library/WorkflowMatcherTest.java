package com.jizza.engine.workflow.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.workflow.library.ReuseProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowMatcherTest {

  private static final ReuseProfile GOLD = new ReuseProfile(
      "Check today's gold rate and email it to me",
      List.of("check the gold rate", "send me the gold price today", "what is gold trading at"),
      List.of(new ReuseProfile.Input("city", "Where to check", false)), null, null);

  @Test
  void aRequestPhrasedLikeAnExampleIsAStrongMatch() {
    var score = WorkflowMatcher.score("Check the gold rate for me", "Gold rate checker",
        "Looks up the gold rate", GOLD);

    assertThat(score.value()).isGreaterThanOrEqualTo(WorkflowMatcher.STRONG);
    assertThat(score.shared()).contains("gold", "rate");
  }

  @Test
  void aDifferentWordingOfTheSameJobIsStillACandidate() {
    var score = WorkflowMatcher.score("email me today's gold prices", "Gold rate checker", null, GOLD);

    assertThat(score.value()).isGreaterThanOrEqualTo(WorkflowMatcher.PLAUSIBLE);
  }

  @Test
  void anUnrelatedRequestIsNotACandidate() {
    var score = WorkflowMatcher.score("Book a table for two on Friday", "Gold rate checker", null, GOLD);

    assertThat(score.value()).isLessThan(WorkflowMatcher.PLAUSIBLE);
    assertThat(score.shared()).isEmpty();
  }

  @Test
  void smallTalkMatchesNothing() {
    assertThat(WorkflowMatcher.score("thanks!", "Gold rate checker", null, GOLD).value()).isZero();
    assertThat(WorkflowMatcher.score("", "Gold rate checker", null, GOLD).value()).isZero();
  }

  @Test
  void pluralsAndTensesMeet() {
    assertThat(WorkflowMatcher.stem("invoices")).isEqualTo(WorkflowMatcher.stem("invoice"));
    assertThat(WorkflowMatcher.stem("sending")).isEqualTo("send");
    assertThat(WorkflowMatcher.stem("emailed")).isEqualTo("email");
    assertThat(WorkflowMatcher.stem("replies")).isEqualTo("reply");
  }

  @Test
  void nearSpellingsAndHowPeoplePointAtAWorkflowStillMatch() {
    var score = WorkflowMatcher.score("Can you run a workflow that created to book ramayana movie",
        "Ramayanam Ticket Booking", null, null);

    assertThat(score.value()).isGreaterThanOrEqualTo(WorkflowMatcher.PLAUSIBLE);
    assertThat(score.shared()).contains("book", "ramayana");
  }
}

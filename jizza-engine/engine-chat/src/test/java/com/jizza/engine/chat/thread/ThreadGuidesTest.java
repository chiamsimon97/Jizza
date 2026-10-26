package com.jizza.engine.chat.thread;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ThreadGuidesTest {

  private static final String GUIDE = ThreadGuides.initial("Index fund plan",
      "Building a monthly index fund plan for retirement.", "Kai", Instant.parse("2026-10-25T08:00:00Z"));

  @Test
  void aNewGuideNamesItsLeadPurposeAndEverySection() {
    assertThat(GUIDE).startsWith("# Index fund plan\n")
        .contains("**Lead:** Kai · **Co-owner:** Jizza · **Opened:** 25 Oct 2026")
        .contains("## Purpose\nBuilding a monthly index fund plan for retirement.")
        .contains("## What we know", "## Decisions", "## Open questions", "## How to work here");
  }

  @Test
  void aNoteGoesAtTheEndOfItsSectionBeforeTheNextHeading() {
    String once = ThreadGuides.append(GUIDE, ThreadGuides.KNOWN, "Invests 500 a month");
    String twice = ThreadGuides.append(once, ThreadGuides.KNOWN, "Has an emergency fund");

    assertThat(twice).contains("## What we know\n- Invests 500 a month\n- Has an emergency fund\n\n## Decisions");
  }

  @Test
  void theSameNoteIsNotAddedTwice() {
    String once = ThreadGuides.append(GUIDE, ThreadGuides.DECISIONS, "Prefers low-cost ETFs");
    String again = ThreadGuides.append(once, ThreadGuides.DECISIONS, "  Prefers   low-cost ETFs ");

    assertThat(again).isEqualTo(once);
  }

  @Test
  void aSectionTheUserRemovedComesBackAtTheEnd() {
    String edited = "# Sleep\n\n## Purpose\nSleep better.\n";

    assertThat(ThreadGuides.append(edited, ThreadGuides.OPEN, "Is caffeine after 2pm a problem?"))
        .isEqualTo("# Sleep\n\n## Purpose\nSleep better.\n\n## Open questions\n- Is caffeine after 2pm a problem?\n");
  }

  @Test
  void thePreambleCarriesTheGuideAndHowToHandBack() {
    String preamble = ThreadGuides.preamble("Index fund plan", "Monthly investing", "Kai", false, false, GUIDE);

    assertThat(preamble).contains("which you lead and Jizza co-owns")
        .contains("<thread_guide>\n# Index fund plan")
        .contains("hand_back_to_jizza")
        .contains("note_in_thread_guide");
  }

  @Test
  void jizzaCoordinatesKnowingWhereEachOfThisChatsThreadsStands() {
    String guide = ThreadGuides.append(ThreadGuides.append(GUIDE, ThreadGuides.DECISIONS, "Nifty 50 index fund"),
        ThreadGuides.OPEN, "Lump sum or monthly?");
    String preamble = ThreadGuides.mainPreamble(java.util.List.of(new ThreadGuides.Line("t-1",
        "Index fund plan", "Kai", "idle", "Monthly investing", ThreadGuides.digest(guide, 240))));

    assertThat(preamble).contains("One thread per purpose")
        .contains("\"Index fund plan\" (t-1), led by Kai, idle. For: Monthly investing")
        .contains("Latest: Nifty 50 index fund; open: Lump sum or monthly?")
        .contains("never answer a specialist's topic");
    assertThat(ThreadGuides.mainPreamble(java.util.List.of())).contains("no threads yet");
  }

  @Test
  void aStandInSaysWhoItIsStandingInFor() {
    assertThat(ThreadGuides.preamble("Sleep", "Sleep better", "Vita", true, true, null))
        .contains("Its usual lead, Vita, is paused or archived")
        .contains("(empty)");
  }

  @Test
  void theKeeperOnlyAcceptsAWholeGuide() {
    assertThat(ThreadGuideKeeper.accepted("NO_CHANGE", 6000)).isNull();
    assertThat(ThreadGuideKeeper.accepted("Here is the update", 6000)).isNull();
    assertThat(ThreadGuideKeeper.accepted("```markdown\n# Sleep\n- ok\n```", 6000)).isEqualTo("# Sleep\n- ok\n");
    String longGuide = "# T\n" + "- line of notes\n".repeat(100);
    assertThat(ThreadGuideKeeper.accepted(longGuide, 200)).hasSizeLessThanOrEqualTo(201).startsWith("# T");
  }
}

package com.jizza.platform.usecase.help;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jizza.engine.api.error.NotFoundException;
import java.util.List;
import org.junit.jupiter.api.Test;

class HelpGuidesUseCaseTest {

  @Test
  void readsTheHeaderAsTheWebDoes() {
    var g = HelpGuidesUseCase.parse("browser-recording", """
        ---
        title: Browser recording
        summary: Record a task once.
        section: Web automation
        order: 10
        updated: 2026-11-02
        ---

        ## What it is
        Text.
        """);
    assertThat(g.title()).isEqualTo("Browser recording");
    assertThat(g.summary()).isEqualTo("Record a task once.");
    assertThat(g.section()).isEqualTo("Web automation");
    assertThat(g.order()).isEqualTo(10);
    assertThat(g.updated()).isEqualTo("2026-11-02");
    assertThat(g.body()).startsWith("\n## What it is");
  }

  @Test
  void aGuideWithoutAHeaderStillShows() {
    var g = HelpGuidesUseCase.parse("notes", "Just text\r\n");
    assertThat(g.title()).isEqualTo("notes");
    assertThat(g.section()).isEqualTo("General");
    assertThat(g.order()).isEqualTo(100);
    assertThat(g.updated()).isNull();
    assertThat(g.body()).isEqualTo("Just text\n");
  }

  @Test
  void listsBySectionThenOrderAndFindsBySlug() {
    var help = new HelpGuidesUseCase(List.of(
        HelpGuidesUseCase.parse("b", "---\ntitle: B\nsection: Web\norder: 20\n---\n"),
        HelpGuidesUseCase.parse("a", "---\ntitle: A\nsection: Web\norder: 10\n---\n"),
        HelpGuidesUseCase.parse("c", "---\ntitle: C\nsection: Account\n---\n")));
    assertThat(help.list()).extracting(HelpGuidesUseCase.Summary::slug).containsExactly("c", "a", "b");
    assertThat(help.get("a").title()).isEqualTo("A");
    assertThatThrownBy(() -> help.get("zz")).isInstanceOf(NotFoundException.class);
  }
}

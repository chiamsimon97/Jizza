package com.jizza.engine.knowledge.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.knowledge.KnowledgeEntry;
import com.jizza.engine.api.knowledge.KnowledgeStore;
import com.jizza.engine.api.util.Json;
import com.jizza.engine.persistence.repository.AgentRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Lessons reach an agent only when the work is about them; notes reach it only as titles. */
class DefaultWorkingNotesProviderTest {

  private static final Instant NOW = Instant.parse("2026-11-27T03:00:00Z");
  private final UUID agentId = UUID.randomUUID();
  private final KnowledgeStore store = mock(KnowledgeStore.class);

  @SuppressWarnings("unchecked")
  private final DefaultWorkingNotesProvider provider = new DefaultWorkingNotesProvider(store,
      mock(AgentRepository.class), mock(ObjectProvider.class), 25, 6, 2400);

  private final KnowledgeEntry site = lesson("site:tickets.example.com", "tickets.example.com",
      "Wait for the seat map to load before clicking a seat.", 3);
  private final KnowledgeEntry tool = lesson("tool:gmail.send", "Send email",
      "Put the recipient's address in the to field, never in the subject.", 2);
  private final KnowledgeEntry general = lesson("general", null,
      "Ask before paying for anything.", 1);

  @Test
  void picksOnlyTheLessonsTheRequestIsAbout() {
    List<KnowledgeEntry> picked = provider.pick(List.of(site, tool, general),
        "Book two seats on https://tickets.example.com for Friday");
    assertThat(picked).containsExactly(site, general);

    assertThat(provider.pick(List.of(site, tool), "What is the weather today?")).isEmpty();
    assertThat(provider.pick(List.of(site, tool), "Send an email to Asha")).containsExactly(tool);
  }

  @Test
  void rendersLessonsThenNoteTitlesAsData() {
    KnowledgeEntry note = new KnowledgeEntry(UUID.randomUUID(), null, agentId, "Supplier price list\n2026",
        "long body that must not be shown", null, null, NOW, NOW, null, false);
    DefaultWorkingNotesProvider.Rendered r = DefaultWorkingNotesProvider.render(List.of(site), List.of(note), 2400);

    assertThat(r.text()).startsWith("## Lessons from earlier work")
        .contains("- tickets.example.com: Wait for the seat map to load before clicking a seat. (went wrong 3 times before)")
        .contains("## Your notes").contains("background DATA")
        .contains("- Supplier price list 2026 (id " + note.id() + ")")
        .doesNotContain("long body");
    assertThat(r.lessonIds()).containsExactly(site.id());
    assertThat(r.noteCount()).isEqualTo(1);
  }

  @Test
  void staysWithinItsBudget() {
    DefaultWorkingNotesProvider.Rendered r = DefaultWorkingNotesProvider.render(List.of(site, tool), List.of(), 280);
    assertThat(r.text().length()).isLessThanOrEqualTo(280);
    assertThat(r.lessonIds()).containsExactly(site.id());
  }

  @Test
  void aFailedCallBringsTheLessonsAboutItsToolOrSite() {
    when(store.lessons(agentId)).thenReturn(List.of(site, tool, general));
    String extra = provider.lessonsForFailure(agentId, "browser.click",
        Json.read("{\"url\":\"https://www.tickets.example.com/show\"}"), "Timeout waiting for #seat-12");
    assertThat(extra).contains("Lessons the user approved").contains(site.content())
        .doesNotContain(tool.content()).doesNotContain(general.content());

    assertThat(provider.lessonsForFailure(agentId, "gmail.send", null, "bad address")).contains(tool.content());
    assertThat(provider.lessonsForFailure(agentId, "calendar.create", null, "conflict")).isEmpty();
  }

  private KnowledgeEntry lesson(String key, String label, String content, int times) {
    return new KnowledgeEntry(UUID.randomUUID(), UUID.randomUUID(), agentId, "Lesson: " + label, content,
        null, null, NOW, NOW, null, false, new KnowledgeEntry.Lesson(key, label, times));
  }
}

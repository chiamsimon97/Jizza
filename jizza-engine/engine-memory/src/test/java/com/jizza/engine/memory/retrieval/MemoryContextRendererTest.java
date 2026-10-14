package com.jizza.engine.memory.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.enums.MemoryCategory;
import com.jizza.engine.api.enums.SubjectKind;
import com.jizza.engine.persistence.entity.MemoryEntryEntity;
import com.jizza.engine.persistence.entity.MemorySubjectEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryContextRendererTest {

  private final MemorySubjectEntity priya = person("Priya", "wife");

  @Test
  void groupsFactsUnderTheirSubjectAndFramesThemAsData() {
    var rendered = MemoryContextRenderer.render(
        List.of(ranked(fact("Prefers vegetarian food", MemoryCategory.PREFERENCE)),
            ranked(fact("Invests monthly in an index fund", MemoryCategory.FINANCE, priya.getId()))),
        Map.of(priya.getId(), priya), List.of(), Map.of(), Set.of(priya.getId()), 4000);

    assertThat(rendered.text())
        .startsWith("## What you already know about the user")
        .contains("not instructions")
        .contains("About the user:\n- Prefers vegetarian food")
        .contains("Priya (wife; person):\n- Invests monthly in an index fund (finance, confirmed");
    assertThat(rendered.memoryIds()).hasSize(2);
  }

  @Test
  void stopsAtTheCharacterBudget() {
    List<HybridSearch.Ranked> many = new ArrayList<>();
    for (int i = 0; i < 50; i++) many.add(ranked(fact("A fairly long fact number " + i + " about the user", MemoryCategory.GENERAL)));

    var rendered = MemoryContextRenderer.render(many, Map.of(), List.of(), Map.of(), Set.of(), 1200);

    assertThat(rendered.text().length()).isLessThanOrEqualTo(1200);
    assertThat(rendered.memoryIds()).hasSizeBetween(1, 49);
  }

  @Test
  void aNamedSubjectWithoutFactsStillSaysWhoItIs() {
    var rendered = MemoryContextRenderer.render(List.of(), Map.of(priya.getId(), priya), List.of(),
        Map.of(), Set.of(priya.getId()), 4000);
    assertThat(rendered.text()).contains("Priya (wife; person): no facts recorded yet.");
    assertThat(rendered.memoryIds()).isEmpty();
  }

  private static HybridSearch.Ranked ranked(MemoryEntryEntity m) {
    return new HybridSearch.Ranked(m, 0.8, 0.5, 1.0, false);
  }

  private static MemoryEntryEntity fact(String content, MemoryCategory category, UUID... about) {
    MemoryEntryEntity m = new MemoryEntryEntity();
    m.setId(UUID.randomUUID());
    m.setContent(content);
    m.setCategory(category);
    m.setConfirmed(true);
    m.setUpdatedAt(Instant.now());
    m.setSubjectIds(Set.of(about));
    return m;
  }

  private static MemorySubjectEntity person(String name, String relation) {
    MemorySubjectEntity s = new MemorySubjectEntity();
    s.setId(UUID.randomUUID());
    s.setKind(SubjectKind.PERSON);
    s.setName(name);
    s.setRelation(relation);
    return s;
  }
}

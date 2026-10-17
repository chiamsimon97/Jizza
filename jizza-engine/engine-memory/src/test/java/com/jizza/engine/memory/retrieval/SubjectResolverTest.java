package com.jizza.engine.memory.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.api.enums.SubjectKind;
import com.jizza.engine.persistence.entity.MemorySubjectEntity;
import com.jizza.engine.persistence.entity.MemorySubjectRelationEntity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubjectResolverTest {

  private final MemorySubjectEntity priya = subject(SubjectKind.PERSON, "Priya Sharma", "wife", "Pri");
  private final MemorySubjectEntity account = subject(SubjectKind.ACCOUNT, "Zerodha account", null);
  private final MemorySubjectEntity plan = subject(SubjectKind.PLAN, "Retirement plan", null);
  private final MemorySubjectEntity ann = subject(SubjectKind.PERSON, "Ann", "sister");
  private final List<MemorySubjectEntity> all = List.of(priya, account, plan, ann);
  private final List<MemorySubjectRelationEntity> edges = List.of(
      edge(account, priya, "held_by"), edge(plan, account, "funded_from"));

  @Test
  void resolvesASubjectByItsRelationToTheUserIncludingThePossessive() {
    var r = SubjectResolver.resolve("Should my wife's portfolio change?", all, edges, 1);
    assertThat(r.direct()).containsExactly(priya.getId());
  }

  @Test
  void resolvesByFullNameFirstNameAndAlias() {
    assertThat(SubjectResolver.resolve("what does Priya Sharma hold", all, edges, 1).direct())
        .containsExactly(priya.getId());
    assertThat(SubjectResolver.resolve("ask priya", all, edges, 1).direct()).containsExactly(priya.getId());
    assertThat(SubjectResolver.resolve("tell Pri", all, edges, 1).direct()).containsExactly(priya.getId());
  }

  @Test
  void followsRelationsOutToTheConfiguredDepth() {
    var oneHop = SubjectResolver.resolve("my wife", all, edges, 1);
    assertThat(oneHop.related()).containsExactly(account.getId());

    var twoHops = SubjectResolver.resolve("my wife", all, edges, 2);
    assertThat(twoHops.related()).containsExactlyInAnyOrder(account.getId(), plan.getId());
  }

  @Test
  void matchesWholeWordsOnly() {
    assertThat(SubjectResolver.resolve("my annual bonus", all, edges, 1).isEmpty()).isTrue();
    assertThat(SubjectResolver.resolve("lunch with Ann", all, edges, 1).direct()).containsExactly(ann.getId());
  }

  @Test
  void ignoresRelationsToSubjectsTheReaderCannotSee() {
    var r = SubjectResolver.resolve("my wife", List.of(priya, plan), edges, 2);
    assertThat(r.related()).isEmpty();
  }

  private static MemorySubjectEntity subject(SubjectKind kind, String name, String relation, String... aliases) {
    MemorySubjectEntity s = new MemorySubjectEntity();
    s.setId(UUID.randomUUID());
    s.setKind(kind);
    s.setName(name);
    s.setRelation(relation);
    s.setAliases(aliases);
    return s;
  }

  private static MemorySubjectRelationEntity edge(MemorySubjectEntity from, MemorySubjectEntity to, String relation) {
    MemorySubjectRelationEntity e = new MemorySubjectRelationEntity();
    e.setId(UUID.randomUUID());
    e.setFromSubjectId(from.getId());
    e.setToSubjectId(to.getId());
    e.setRelation(relation);
    return e;
  }
}

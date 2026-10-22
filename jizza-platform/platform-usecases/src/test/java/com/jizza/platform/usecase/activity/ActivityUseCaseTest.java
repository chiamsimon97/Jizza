package com.jizza.platform.usecase.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jizza.engine.api.audit.AuditLog;
import com.jizza.engine.api.audit.catalog.ActivityLogType.Severity;
import com.jizza.engine.api.dto.AuditEntryDto;
import com.jizza.engine.api.enums.ActorType;
import com.jizza.engine.api.orchestrator.AgentDirectory;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Actor names, and how the explorer files, filters and counts what the audit log holds. */
class ActivityUseCaseTest {

  private final UUID userId = UUID.randomUUID();
  private AuditLog audit;
  private AgentDirectory agents;
  private ActivityUseCase useCase;

  private AuditEntryDto row(ActorType actorType, UUID actorId) {
    return new AuditEntryDto(UUID.randomUUID(), actorType, actorId, null, "workflow.saved",
        "target", null, "req-1", "hash", Instant.now());
  }

  private AuditEntryDto row(String action, ActorType actorType, Instant at) {
    return new AuditEntryDto(UUID.randomUUID(), actorType, null, null, action, "target", null,
        "req-1", "hash", at);
  }

  private ActivityExplorer.Query query(Set<String> categories, Set<String> groups,
                                       Set<Severity> severities, String search) {
    return new ActivityExplorer.Query(null, null, categories, groups, severities, Set.of(),
        search, 50, 0, ZoneOffset.UTC);
  }

  @BeforeEach
  void setUp() {
    audit = mock(AuditLog.class);
    agents = mock(AgentDirectory.class);
    useCase = new ActivityUseCase(audit, agents,
        new ActivityTypeRegistry(List.of(new CoreActivityCatalog())));
  }

  @Test
  void userActionsAreLabelledYou() {
    when(audit.recent(userId, 100, null)).thenReturn(List.of(row(ActorType.USER, null)));

    assertThat(useCase.recent(userId, 100, null).get(0).actorName()).isEqualTo("You");
  }

  @Test
  void systemActionsAreLabelledJizza() {
    when(audit.recent(userId, 100, null)).thenReturn(List.of(row(ActorType.SYSTEM, null)));

    assertThat(useCase.recent(userId, 100, null).get(0).actorName()).isEqualTo("Jizza");
  }

  @Test
  void agentActionsAreLabelledWithTheAgentsName() {
    UUID agentId = UUID.randomUUID();
    when(audit.recent(userId, 100, null)).thenReturn(List.of(row(ActorType.AGENT, agentId)));
    when(agents.nameOf(agentId)).thenReturn("Scheduler");

    assertThat(useCase.recent(userId, 100, null).get(0).actorName()).isEqualTo("Scheduler");
  }

  @Test
  void anAgentActionWithNoActorIdIsLabelledGenerically() {
    when(audit.recent(userId, 100, null)).thenReturn(List.of(row(ActorType.AGENT, null)));

    assertThat(useCase.recent(userId, 100, null).get(0).actorName()).isEqualTo("An agent");
  }

  @Test
  void exploreFilesEachEntryUnderItsCategoryAndGroup() {
    Instant now = Instant.now();
    when(audit.between(any(), any(), any(), anyInt())).thenReturn(List.of(
        row("auth.login.failure", ActorType.SYSTEM, now),
        row("auth.mfa.enabled", ActorType.USER, now.minusSeconds(60)),
        row("run.failed", ActorType.SYSTEM, now.minusSeconds(120)),
        row("brand_new.thing_happened", ActorType.USER, now.minusSeconds(180))));

    var page = useCase.explore(userId, query(Set.of(), Set.of(), Set.of(), null));

    assertThat(page.total()).isEqualTo(4);
    assertThat(page.items()).extracting(ActivityExplorer.Item::category)
        .containsExactly("security", "security", "workflows", "other");
    assertThat(page.items()).extracting(ActivityExplorer.Item::group)
        .containsExactly("sign_in", "mfa", "attention", "other");
    assertThat(page.items().get(0).label()).isEqualTo("Sign-in failed");
    assertThat(page.items().get(0).severity()).isEqualTo(Severity.WARNING);
    assertThat(page.items().get(3).label()).isEqualTo("Brand new thing happened");
  }

  @Test
  void categoryCountsIgnoreTheCategoryFilterSoOtherSectionsStillShowTheirSize() {
    Instant now = Instant.now();
    when(audit.between(any(), any(), any(), anyInt())).thenReturn(List.of(
        row("auth.login.success", ActorType.USER, now),
        row("run.completed", ActorType.SYSTEM, now),
        row("run.failed", ActorType.SYSTEM, now)));

    var page = useCase.explore(userId, query(Set.of("workflows"), Set.of(), Set.of(), null));

    assertThat(page.total()).isEqualTo(2);
    assertThat(page.categories()).filteredOn(c -> c.key().equals("security"))
        .singleElement().extracting(ActivityExplorer.CategoryCount::count).isEqualTo(1L);
    var workflows = page.categories().stream().filter(c -> c.key().equals("workflows"))
        .findFirst().orElseThrow();
    assertThat(workflows.groups()).filteredOn(g -> g.key().equals("attention"))
        .singleElement().extracting(ActivityExplorer.GroupCount::count).isEqualTo(1L);
  }

  @Test
  void subGroupSeverityAndTextFiltersNarrowTheList() {
    Instant now = Instant.now();
    when(audit.between(any(), any(), any(), anyInt())).thenReturn(List.of(
        row("run.completed", ActorType.SYSTEM, now),
        row("run.failed", ActorType.SYSTEM, now),
        row("run.dead_lettered", ActorType.SYSTEM, now)));

    assertThat(useCase.explore(userId, query(Set.of(), Set.of("workflows/attention"), Set.of(),
        null)).total()).isEqualTo(2);
    assertThat(useCase.explore(userId, query(Set.of(), Set.of(), Set.of(Severity.CRITICAL),
        null)).items()).extracting(ActivityExplorer.Item::action)
        .containsExactly("run.dead_lettered");
    assertThat(useCase.explore(userId, query(Set.of(), Set.of(), Set.of(), "completed"))
        .total()).isEqualTo(1);
  }

  @Test
  void theTimelineCountsEachSliceByCategory() {
    Instant to = Instant.parse("2026-10-22T12:00:00Z");
    when(audit.between(any(), any(), any(), anyInt())).thenReturn(List.of(
        row("run.completed", ActorType.SYSTEM, Instant.parse("2026-10-22T11:30:00Z")),
        row("auth.login.success", ActorType.USER, Instant.parse("2026-10-22T11:10:00Z")),
        row("run.failed", ActorType.SYSTEM, Instant.parse("2026-10-22T09:05:00Z"))));

    var page = useCase.explore(userId, new ActivityExplorer.Query(
        Instant.parse("2026-10-22T06:00:00Z"), to, Set.of(), Set.of(), Set.of(), Set.of(), null,
        50, 0, ZoneOffset.UTC));

    assertThat(page.bucketUnit()).isEqualTo("hour");
    assertThat(page.buckets()).hasSize(6);
    assertThat(page.buckets().get(5).total()).isEqualTo(2);
    assertThat(page.buckets().get(5).byCategory()).containsEntry("security", 1L);
    assertThat(page.buckets().get(3).byCategory()).containsEntry("workflows", 1L);
  }

  @Test
  void verifyReportsAnIntactChainWithItsEntryCount() {
    when(audit.countForUser(userId)).thenReturn(3L);
    when(audit.verifyChain(userId)).thenReturn(Optional.empty());

    var result = useCase.verify(userId);

    assertThat(result.intact()).isTrue();
    assertThat(result.firstBrokenEntryId()).isNull();
    assertThat(result.entries()).isEqualTo(3L);
  }

  @Test
  void verifyReportsTheFirstBrokenEntryWhenTheChainIsTampered() {
    UUID brokenId = UUID.randomUUID();
    when(audit.countForUser(userId)).thenReturn(5L);
    when(audit.verifyChain(userId)).thenReturn(Optional.of(brokenId));

    var result = useCase.verify(userId);

    assertThat(result.intact()).isFalse();
    assertThat(result.firstBrokenEntryId()).isEqualTo(brokenId);
  }
}

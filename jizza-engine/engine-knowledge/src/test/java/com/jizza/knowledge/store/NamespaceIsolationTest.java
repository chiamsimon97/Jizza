package com.jizza.knowledge.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jizza.common.audit.AuditLog;
import com.jizza.common.enums.DataClassification;
import com.jizza.common.error.IsolationViolationException;
import com.jizza.common.knowledge.KnowledgeEntry;
import com.jizza.knowledge.embedding.LocalHashingEmbeddingProvider;
import com.jizza.persistence.entity.AgentEntity;
import com.jizza.persistence.entity.KnowledgeEntryEntity;
import com.jizza.persistence.repository.AgentRepository;
import com.jizza.persistence.repository.KnowledgeEntryRepository;
import com.jizza.persistence.support.KnowledgeVectorSearch;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The isolation invariant, proven directly: agent A can neither read nor write agent B's namespace
 * (R22, and the Charter's "enforced in code, not just prompts").
 */
class NamespaceIsolationTest {

  private final UUID userId = UUID.randomUUID();
  private final AgentEntity health = agent("health", "Vita");
  private final AgentEntity finance = agent("finance", "Kai");

  private KnowledgeEntryRepository entries;
  private AuditLog audit;
  private NamespacedKnowledgeStore store;
  private final List<KnowledgeEntryEntity> rows = new ArrayList<>();

  private AgentEntity agent(String key, String name) {
    AgentEntity a = new AgentEntity();
    a.setId(UUID.randomUUID());
    a.setUserId(userId);
    a.setKey(key);
    a.setName(name);
    a.setSystemPrompt("prompt");
    a.setEnabled(true);
    return a;
  }

  @BeforeEach
  void setUp() {
    entries = mock(KnowledgeEntryRepository.class);
    audit = mock(AuditLog.class);
    AgentRepository agents = mock(AgentRepository.class);
    when(agents.findById(health.getId())).thenReturn(Optional.of(health));
    when(agents.findById(finance.getId())).thenReturn(Optional.of(finance));

    when(entries.save(any())).thenAnswer(inv -> {
      KnowledgeEntryEntity e = inv.getArgument(0);
      if (e.getId() == null) e.setId(UUID.randomUUID());
      rows.removeIf(r -> r.getId().equals(e.getId()));
      rows.add(e);
      return e;
    });
    when(entries.findById(any())).thenAnswer(inv ->
        rows.stream().filter(r -> r.getId().equals(inv.getArgument(0))).findFirst());
    when(entries.searchLexical(any(), any(), any())).thenAnswer(inv -> {
      UUID agentId = inv.getArgument(0);
      String q = inv.getArgument(1);
      return rows.stream()
          .filter(r -> r.getAgentId().equals(agentId))
          .filter(r -> r.getTitle().toLowerCase().contains(q.toLowerCase())
              || (r.getContent() != null && r.getContent().toLowerCase().contains(q.toLowerCase())))
          .toList();
    });
    when(entries.findByAgentIdOrderByUpdatedAtDesc(any(), any())).thenAnswer(inv ->
        rows.stream().filter(r -> r.getAgentId().equals(inv.<UUID>getArgument(0))).toList());

    KnowledgeVectorSearch vectorSearch = mock(KnowledgeVectorSearch.class);
    when(vectorSearch.nearest(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenThrow(new IllegalStateException("no pgvector in this unit test"));

    store = new NamespacedKnowledgeStore(entries, agents, new LocalHashingEmbeddingProvider(),
        vectorSearch, audit);
  }

  private KnowledgeEntry write(AgentEntity agent, String title, String content) {
    return store.upsert(agent.getId(), KnowledgeEntry.create(userId, agent.getId(), title, content,
        null, DataClassification.HEALTH));
  }

  @Test
  void agentACannotReadAnEntryOwnedByAgentB() {
    KnowledgeEntry financeEntry = write(finance, "Card statement", "closing balance 12,000");

    assertThatThrownBy(() -> store.get(health.getId(), financeEntry.id()))
        .isInstanceOf(IsolationViolationException.class)
        .hasMessageContaining(health.getId().toString())
        .hasMessageContaining(financeEntry.agentId().toString());
  }

  @Test
  void agentACannotWriteIntoAgentBsNamespace() {
    KnowledgeEntry forged = KnowledgeEntry.create(userId, finance.getId(), "planted", "text",
        null, DataClassification.NONE);

    assertThatThrownBy(() -> store.upsert(health.getId(), forged))
        .isInstanceOf(IsolationViolationException.class);
    assertThat(rows).isEmpty();
  }

  @Test
  void agentACannotOverwriteAgentBsExistingEntry() {
    KnowledgeEntry financeEntry = write(finance, "Card statement", "closing balance 12,000");
    KnowledgeEntry edit = new KnowledgeEntry(financeEntry.id(), userId, null, "hijacked",
        "changed", null, DataClassification.NONE, null, null, null);

    assertThatThrownBy(() -> store.upsert(health.getId(), edit))
        .isInstanceOf(IsolationViolationException.class);
    assertThat(rows.get(0).getTitle()).isEqualTo("Card statement");
  }

  @Test
  void agentACannotDeleteAgentBsEntry() {
    KnowledgeEntry financeEntry = write(finance, "Card statement", "balance");
    assertThatThrownBy(() -> store.delete(health.getId(), financeEntry.id()))
        .isInstanceOf(IsolationViolationException.class);
    assertThat(rows).hasSize(1);
  }

  @Test
  void searchNeverCrossesNamespaces() {
    write(finance, "Salary", "monthly credit from employer");
    write(health, "Salary negotiation stress", "sleep affected");

    assertThat(store.search(health.getId(), "salary", 10))
        .extracting(KnowledgeEntry::title)
        .containsExactly("Salary negotiation stress");
    assertThat(store.search(finance.getId(), "salary", 10))
        .extracting(KnowledgeEntry::title)
        .containsExactly("Salary");
  }

  @Test
  void listingIsScopedToTheCallingAgent() {
    write(finance, "Card", "x");
    write(health, "Steps", "y");
    assertThat(store.list(health.getId(), 50)).extracting(KnowledgeEntry::title)
        .containsExactly("Steps");
  }

  @Test
  void anIsolationViolationIsItselfAudited() {
    KnowledgeEntry financeEntry = write(finance, "Card statement", "balance");
    assertThatThrownBy(() -> store.get(health.getId(), financeEntry.id()))
        .isInstanceOf(IsolationViolationException.class);
    verify(audit).record(eq(userId), any(), eq(health.getId()),
        eq("knowledge.isolation_violation"), eq(finance.getId().toString()), any());
  }

  @Test
  void agentsCanFreelyUseTheirOwnNamespace() {
    KnowledgeEntry saved = write(health, "Resting heart rate", "58 bpm this week");
    assertThat(store.get(health.getId(), saved.id())).isPresent();

    KnowledgeEntry updated = store.upsert(health.getId(),
        new KnowledgeEntry(saved.id(), userId, health.getId(), "Resting heart rate", "56 bpm",
            null, DataClassification.HEALTH, null, null, null));
    assertThat(updated.content()).isEqualTo("56 bpm");
    assertThat(rows).hasSize(1);
  }

  @Test
  void knowledgeContentIsNeverWrittenToTheAuditTrail() {
    write(health, "Diagnosis", "sensitive clinical detail");
    verify(audit).record(eq(userId), any(), eq(health.getId()), eq("knowledge.upsert"),
        eq("Diagnosis"), org.mockito.ArgumentMatchers.argThat(detail ->
            !detail.toString().contains("sensitive clinical detail")
                && detail.path("classification").asText().equals("HEALTH")));
  }
}

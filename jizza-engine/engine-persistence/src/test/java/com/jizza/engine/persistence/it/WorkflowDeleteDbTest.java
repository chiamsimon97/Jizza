package com.jizza.engine.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.persistence.repository.WorkflowRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A deleted workflow keeps its row (its runs point at it) and no query loads it any more. */
@org.junit.jupiter.api.condition.EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available", disabledReason = "no PostgreSQL available")
class WorkflowDeleteDbTest extends PostgresIntegrationTest {

  @PersistenceContext
  private EntityManager em;

  @Autowired
  private WorkflowRepository workflows;

  @Test
  void aDeletedWorkflowIsHiddenWhileItsRunsStay() {
    Object userId = em.createNativeQuery(
        "insert into users (email, password_hash) values ('delete@jizza.local', 'x') returning id")
        .getSingleResult();
    UUID wfId = (UUID) em.createNativeQuery("""
        insert into workflows (user_id, name, graph, origin, status)
        values (:u, 'Old booking', '{}'::jsonb, 'recorded', 'draft') returning id
        """).setParameter("u", userId).getSingleResult();
    Object runId = em.createNativeQuery("""
        insert into workflow_runs (user_id, workflow_id, version, status)
        values (:u, :w, 1, 'done') returning id
        """).setParameter("u", userId).setParameter("w", wfId).getSingleResult();

    var workflow = workflows.findById(wfId).orElseThrow();
    workflow.setDeletedAt(java.time.Instant.now());
    workflows.saveAndFlush(workflow);
    em.clear();

    assertThat(workflows.findById(wfId)).isEmpty();
    assertThat(workflows.findByUserIdOrderByCreatedAtDesc((UUID) userId)).isEmpty();
    Object runs = em.createNativeQuery("select count(*) from workflow_runs where id = :r")
        .setParameter("r", runId).getSingleResult();
    assertThat(((Number) runs).intValue()).isEqualTo(1);
  }
}

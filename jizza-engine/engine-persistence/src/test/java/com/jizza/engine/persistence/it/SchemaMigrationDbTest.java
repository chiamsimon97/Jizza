package com.jizza.engine.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.jizza.engine.persistence.support.SchemaInfo;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Accept: {@code flyway migrate} builds the schema clean and every Contract table is present. */
@org.junit.jupiter.api.condition.EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available", disabledReason = "no PostgreSQL available")
class SchemaMigrationDbTest extends PostgresIntegrationTest {

  @PersistenceContext
  private EntityManager em;

  @Test
  @SuppressWarnings("unchecked")
  void everyContractAndPlatformTableExists() {
    List<String> tables = em.createNativeQuery(
        "select table_name from information_schema.tables where table_schema = 'public'").getResultList();
    assertThat(tables).containsAll(SchemaInfo.CONTRACT_TABLES);
    assertThat(tables).containsAll(SchemaInfo.PLATFORM_TABLES);
    assertThat(tables).containsAll(SchemaInfo.MEMORY_TABLES);
  }

  @Test
  @SuppressWarnings("unchecked")
  void pgvectorExtensionIsInstalledAndTheEmbeddingColumnIsAVector() {
    List<String> ext = em.createNativeQuery("select extname from pg_extension").getResultList();
    assertThat(ext).contains("vector", "citext", "pgcrypto");
    Object udt = em.createNativeQuery("""
        select udt_name from information_schema.columns
         where table_name = 'knowledge_entries' and column_name = 'embedding'
        """).getSingleResult();
    assertThat(udt.toString()).isEqualTo("vector");
  }

  @Test
  void checkConstraintsRejectValuesOutsideTheContractVocabulary() {
    Object userId = em.createNativeQuery(
        "insert into users (email, password_hash) values ('check@jizza.local', 'x') returning id")
        .getSingleResult();
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
        em.createNativeQuery("update users set status = 'not-a-status' where id = :u")
            .setParameter("u", userId).executeUpdate())
        .hasMessageContaining("users_status_check");
  }

  @Test
  void sideEffectCheckpointsAreUniquePerRunAndNode() {
    Object userId = em.createNativeQuery(
        "insert into users (email, password_hash) values ('steps@jizza.local', 'x') returning id")
        .getSingleResult();
    Object wfId = em.createNativeQuery("""
        insert into workflows (user_id, name, graph, origin, status)
        values (:u, 'wf', '{}'::jsonb, 'user_built', 'approved') returning id
        """).setParameter("u", userId).getSingleResult();
    Object runId = em.createNativeQuery("""
        insert into workflow_runs (user_id, workflow_id, version, status)
        values (:u, :w, 1, 'running') returning id
        """).setParameter("u", userId).setParameter("w", wfId).getSingleResult();

    em.createNativeQuery("insert into workflow_run_steps (run_id, node_id, status) values (:r, 'n1', 'done')")
        .setParameter("r", runId).executeUpdate();
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
        em.createNativeQuery("insert into workflow_run_steps (run_id, node_id, status) values (:r, 'n1', 'done')")
            .setParameter("r", runId).executeUpdate())
        .hasMessageContaining("workflow_run_steps_run_node_uk");
  }

  @Test
  void webAutomationMigrationAddsBrowserAndRecordedVocabularies() {
    Object userId = em.createNativeQuery(
        "insert into users (email, password_hash) values ('web@jizza.local', 'x') returning id")
        .getSingleResult();
    Object workspaceId = em.createNativeQuery(
        "insert into workspaces (name, type, created_by_user_id) values ('Web', 'personal', :u) returning id")
        .setParameter("u", userId).getSingleResult();
    em.createNativeQuery("""
        insert into devices (workspace_id, owner_user_id, name, platform)
        values (:w, :u, 'Chrome', 'browser')
        """).setParameter("w", workspaceId).setParameter("u", userId).executeUpdate();
    em.createNativeQuery("""
        insert into workflows (user_id, name, graph, origin)
        values (:u, 'Recorded task', '{}'::jsonb, 'recorded')
        """).setParameter("u", userId).executeUpdate();

    Object stepType = em.createNativeQuery("""
        select data_type from information_schema.columns
        where table_name = 'web_recording_steps' and column_name = 'step'
        """).getSingleResult();
    assertThat(stepType.toString()).isEqualTo("bytea");
  }
}

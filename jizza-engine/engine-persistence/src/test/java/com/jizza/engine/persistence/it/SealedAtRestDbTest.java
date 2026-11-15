package com.jizza.engine.persistence.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jizza.engine.api.crypto.EncryptionService;
import com.jizza.engine.api.enums.RunStepStatus;
import com.jizza.engine.persistence.converter.SealedJson;
import com.jizza.engine.persistence.entity.WorkflowRunStepEntity;
import com.jizza.engine.persistence.repository.WorkflowRunStepRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the sealing converter against the real schema: what lands in the {@code jsonb} columns
 * is ciphertext, entities read back the original JSON, and rows written before sealing existed
 * (plaintext) still load.
 */
@EnabledIf(value = "com.jizza.engine.persistence.support.TestDatabase#available",
    disabledReason = "no PostgreSQL: set JIZZA_TEST_DB_URL or start Docker")
class SealedAtRestDbTest extends PostgresIntegrationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Reversible stand-in; production wires EnvelopeEncryptionService at startup. */
  private static final EncryptionService XOR = new EncryptionService() {
    @Override public byte[] encrypt(byte[] plain) { return flip(plain); }
    @Override public byte[] decrypt(byte[] ciphertext) { return flip(ciphertext); }
    private byte[] flip(byte[] in) {
      byte[] out = in.clone();
      for (int i = 0; i < out.length; i++) out[i] ^= 0x5a;
      return out;
    }
  };

  @Autowired private WorkflowRunStepRepository steps;
  @Autowired private JdbcTemplate jdbc;

  private UUID runId;

  @BeforeAll
  static void sealWithTestKey() {
    SealedJson.initialize(XOR, true);
  }

  @AfterAll
  static void resetSealing() {
    SealedJson.initialize(null, true);
  }

  @BeforeEach
  void makeRun() {
    UUID userId = jdbc.queryForObject(
        "insert into users (email, password_hash) values (?, 'x') returning id",
        UUID.class, "sealed-" + UUID.randomUUID() + "@jizza.local");
    UUID workflowId = jdbc.queryForObject("""
        insert into workflows (user_id, name, graph, origin, status)
        values (?, 'sealed test', '{}'::jsonb, 'user_built', 'approved') returning id""",
        UUID.class, userId);
    runId = jdbc.queryForObject("""
        insert into workflow_runs (user_id, workflow_id, version, status)
        values (?, ?, 1, 'running') returning id""", UUID.class, userId, workflowId);
  }

  @Test
  void stepInputAndOutputAreCiphertextInTheColumn() throws Exception {
    WorkflowRunStepEntity step = new WorkflowRunStepEntity();
    step.setRunId(runId);
    step.setNodeId("n1");
    step.setStatus(RunStepStatus.DONE);
    step.setInput(MAPPER.readTree("{\"fields\":[{\"label\":\"Name\",\"value\":\"A Person\"}]}"));
    step.setOutput(MAPPER.readTree("{\"text\":\"9876543210\"}"));
    UUID id = steps.saveAndFlush(step).getId();

    String rawInput = jdbc.queryForObject(
        "select input::text from workflow_run_steps where id = ?", String.class, id);
    String rawOutput = jdbc.queryForObject(
        "select output::text from workflow_run_steps where id = ?", String.class, id);
    assertThat(rawInput).contains(SealedJson.FIELD).doesNotContain("A Person");
    assertThat(rawOutput).contains(SealedJson.FIELD).doesNotContain("9876543210");

    WorkflowRunStepEntity read = steps.findById(id).orElseThrow();
    assertThat(read.getInput().at("/fields/0/value").asText()).isEqualTo("A Person");
    assertThat(read.getOutput().path("text").asText()).isEqualTo("9876543210");
  }

  @Test
  void plaintextRowsFromBeforeSealingStillRead() {
    UUID id = jdbc.queryForObject("""
        insert into workflow_run_steps (run_id, node_id, status, output, attempts)
        values (?, 'n0', 'done', '{"text":"legacy"}'::jsonb, 0) returning id""",
        UUID.class, runId);
    WorkflowRunStepEntity read = steps.findById(id).orElseThrow();
    assertThat(read.getOutput().path("text").asText()).isEqualTo("legacy");
  }
}

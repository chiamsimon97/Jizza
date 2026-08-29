package com.jizza.engine.persistence.support;

import java.util.List;

/**
 * The table inventory the Contract declares, used by the migration test to assert that
 * {@code V1__init.sql} really does build the whole schema.
 */
public final class SchemaInfo {

  private SchemaInfo() {}

  public static final List<String> CONTRACT_TABLES = List.of(
      "users", "sessions", "agents", "knowledge_entries", "conversations", "messages",
      "delegation_traces", "connections", "generated_tools", "workflows", "workflow_versions",
      "skills", "workflow_runs", "workflow_run_steps", "scheduled_tasks", "notifications",
      "autonomy_policies", "kill_switch", "audit_log", "idempotency_keys",
      "webhook_subscriptions", "inbound_events", "files", "agent_capabilities");

  public static final List<String> PLATFORM_TABLES = List.of(
      "job_queue", "shedlock", "outbox", "llm_usage", "oauth_states", "password_reset_tokens");
}

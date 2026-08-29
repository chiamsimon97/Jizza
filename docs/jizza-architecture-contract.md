# Jizza — Architecture Contract (Phase 1)

*The binding glue. Schema, shared types, and interfaces every module implements. No module invents its
own schema, DTOs, error format, or events — it imports these. Stack: Java 21 + Spring Boot 3.x, Spring
Data JPA/Hibernate, PostgreSQL + pgvector, Flyway. Package root `com.jizza`.*

---

## 1. Database schema (PostgreSQL / Flyway)

Conventions: UUID PKs (`gen_random_uuid()`, pgcrypto), snake_case, `created_at`/`updated_at timestamptz`,
every user-owned table has `user_id uuid not null` + FK to `users`. Enumerated columns are `varchar` +
`CHECK` (JPA `@Enumerated(STRING)`). Encrypted columns are `bytea` (AES-GCM ciphertext) and are marked
🔒.

```sql
-- ── Identity ────────────────────────────────────────────────
create table users (
  id uuid primary key default gen_random_uuid(),
  email citext unique not null,
  password_hash text not null,               -- Argon2id
  mfa_secret bytea,                           -- 🔒 TOTP seed
  mfa_enabled boolean not null default false,
  recovery_hash text,
  status varchar not null default 'active' check (status in ('active','locked','disabled')),
  failed_logins int not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table sessions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  device text, ip inet,
  issued_at timestamptz not null default now(),
  idle_expires_at timestamptz not null,
  absolute_expires_at timestamptz not null,
  revoked boolean not null default false
);

-- ── Agents & knowledge ──────────────────────────────────────
create table agents (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  key varchar not null,                       -- 'jizza','health','finance','professional',...
  name varchar not null,                       -- user-editable persona name
  domain varchar,
  system_prompt text not null,
  is_orchestrator boolean not null default false,
  enabled boolean not null default true,
  unique (user_id, key)
);

create table knowledge_entries (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  agent_id uuid not null references agents(id) on delete cascade,   -- namespace
  title varchar not null,
  content text,
  data jsonb,
  classification varchar check (classification in ('none','pii','health','financial')),
  embedding vector(1536),                      -- pgvector
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on knowledge_entries (agent_id);

create table conversations (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now()
);
create table messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references conversations(id) on delete cascade,
  role varchar not null check (role in ('user','assistant')),
  content text not null,
  created_at timestamptz not null default now()
);
create table delegation_traces (               -- internal Jizza↔specialist audit for debugging
  id uuid primary key default gen_random_uuid(),
  message_id uuid references messages(id) on delete cascade,
  from_agent uuid references agents(id), to_agent uuid references agents(id),
  task text, result text, created_at timestamptz not null default now()
);

-- ── Connectors ──────────────────────────────────────────────
create table connections (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  service_name varchar not null,
  auth_scheme varchar not null check (auth_scheme in ('oauth2','api_key','basic','custom','no_auth')),
  client_id text,
  client_secret bytea,                         -- 🔒
  authorization_url text, access_token_url text, refresh_token_url text,
  scopes text, scope_separator varchar default 'space',
  pkce boolean default false, unified_callback boolean default false,
  token_placement varchar default 'header' check (token_placement in ('header','query','body')),
  grant_type varchar default 'authorization_code',
  access_token bytea,                          -- 🔒
  refresh_token bytea,                         -- 🔒
  token_expires_at timestamptz,
  auto_refresh boolean not null default true,
  test_api jsonb, revoke_api jsonb, profile_api jsonb, webhook_verify_api jsonb, extra_fields jsonb,
  status varchar not null default 'new' check (status in ('new','connected','needs_reauth','error')),
  created_at timestamptz not null default now()
);

create table generated_tools (
  id uuid primary key default gen_random_uuid(),
  connection_id uuid not null references connections(id) on delete cascade,
  name varchar not null, description text,
  input_schema jsonb not null,                 -- JSON Schema
  http_method varchar not null, path_template text not null,
  param_mapping jsonb, auth_mapping jsonb, side_effecting boolean not null default false,
  enabled boolean not null default true
);

-- ── Workflows / skills / runs ───────────────────────────────
create table workflows (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  name varchar not null, description text,
  graph jsonb not null,                         -- nodes + edges
  input_schema jsonb, trigger_config jsonb,
  origin varchar not null check (origin in ('user_built','agent_authored')),
  status varchar not null default 'draft' check (status in ('draft','pending_review','approved','rejected')),
  owning_agent_id uuid references agents(id),
  version int not null default 1,
  source_task_id uuid,
  created_at timestamptz not null default now()
);
create table workflow_versions (               -- immutable snapshots
  id uuid primary key default gen_random_uuid(),
  workflow_id uuid not null references workflows(id) on delete cascade,
  version int not null, graph jsonb not null, status varchar not null,
  created_at timestamptz not null default now(),
  unique (workflow_id, version)
);
create table skills (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  workflow_id uuid not null references workflows(id) on delete cascade,
  version int not null,
  name varchar not null, description text, parameter_schema jsonb,
  invocable boolean not null default false      -- true only when its version is approved
);
create table workflow_runs (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  workflow_id uuid not null references workflows(id), version int not null,
  trigger_source varchar,
  status varchar not null check (status in ('running','paused_waiting_instruction','done','failed','cancelled')),
  current_node varchar, context jsonb,          -- serialized saga state
  created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table workflow_run_steps (              -- per-node checkpoints (resume-from-last)
  id uuid primary key default gen_random_uuid(),
  run_id uuid not null references workflow_runs(id) on delete cascade,
  node_id varchar not null, status varchar not null check (status in ('done','failed','skipped')),
  output jsonb, attempts int not null default 0, finished_at timestamptz
);

-- ── Scheduler ───────────────────────────────────────────────
create table scheduled_tasks (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  agent_id uuid not null references agents(id),
  title varchar not null, payload jsonb,
  schedule_type varchar not null check (schedule_type in ('one_time','recurring')),
  cron_or_rrule text, run_at timestamptz, next_run_at timestamptz, timezone varchar default 'Asia/Kolkata',
  bound_workflow_id uuid references workflows(id), bound_version int,
  status varchar not null default 'pending'
     check (status in ('pending','running','waiting_approval','done','cancelled','failed')),
  last_run_at timestamptz, last_result text
);
create index on scheduled_tasks (next_run_at) where status = 'pending';

-- ── Notifications ───────────────────────────────────────────
create table notifications (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  source_agent_id uuid references agents(id),
  kind varchar not null check (kind in ('info','permission_request','workflow_review','instruction_required')),
  body text, context jsonb,
  task_id uuid references scheduled_tasks(id), workflow_id uuid references workflows(id),
  run_id uuid references workflow_runs(id),
  status varchar not null default 'unread'
     check (status in ('unread','read','approved','denied','submitted','expired')),
  submitted_instruction text,
  expires_at timestamptz, escalation_state varchar default 'none',
  created_at timestamptz not null default now(), answered_at timestamptz
);

-- ── Autonomy, audit, infra ──────────────────────────────────
create table autonomy_policies (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  agent_id uuid references agents(id),           -- null = global
  tool_ref varchar,                              -- generated_tool name / skill name / '*'
  rule varchar not null check (rule in ('allow','ask','deny')),
  limits jsonb, precedence int not null default 0
);
create table kill_switch (
  user_id uuid primary key references users(id) on delete cascade,
  autonomy_paused boolean not null default false, updated_at timestamptz not null default now()
);
create table audit_log (                        -- append-only; hash-chained
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  actor_type varchar not null check (actor_type in ('user','agent','system')), actor_id uuid,
  action varchar not null, target varchar, detail jsonb,
  prev_hash text, hash text, request_id text, created_at timestamptz not null default now()
);
create table idempotency_keys (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  key varchar not null, tool varchar, fingerprint text, result jsonb,
  created_at timestamptz not null default now(), unique (user_id, key)
);
create table webhook_subscriptions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  connection_id uuid references connections(id), endpoint_token varchar unique not null,
  workflow_id uuid references workflows(id), verify_config jsonb, enabled boolean default true
);
create table inbound_events (
  id uuid primary key default gen_random_uuid(),
  subscription_id uuid references webhook_subscriptions(id), payload jsonb, verified boolean,
  received_at timestamptz not null default now()
);
create table files (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  storage_ref text not null, filename varchar, content_type varchar, size_bytes bigint,
  scan_status varchar default 'pending', created_at timestamptz not null default now()
);
create table agent_capabilities (
  id uuid primary key default gen_random_uuid(),
  agent_id uuid not null references agents(id) on delete cascade,
  capability_type varchar not null check (capability_type in ('knowledge','generated_tool','skill')),
  capability_ref uuid not null,                  -- generated_tools.id or skills.id
  unique (agent_id, capability_type, capability_ref)
);
```

---

## 2. Shared types (`jizza-common`)

- **Error model:** RFC 9457 `ProblemDetail` (Spring's built-in) for all API errors.
- **Enums** mirror the CHECK values above (`AuthScheme`, `NotificationKind`, `TaskStatus`,
  `WorkflowStatus`, `RunStatus`, `AutonomyRule`, `DataClassification`, `CapabilityType`, …).
- **DTOs** (records): `AgentDto`, `ConnectionDto`, `GeneratedToolDto`, `WorkflowDto`, `SkillDto`,
  `RunDto`, `TaskDto`, `NotificationDto`, `AutonomyRuleDto`, `AuditEntryDto`, plus request/response
  records per endpoint. Map with **MapStruct**.
- **Tool contract:** `ToolSpec(name, description, JsonSchema inputSchema)`,
  `ToolCall(name, JsonNode args)`, `ToolResult(JsonNode output, boolean ok, String error)`.
- **Events (notification bus):** `InfoRaised`, `PermissionRequested`, `WorkflowReviewRequested`,
  `InstructionRequired`, `PermissionAnswered`, `ReviewAnswered`, `InstructionSubmitted`.

---

## 3. Interface contracts (Java sketches)

```java
// Agent runtime
interface LlmClient { LlmResponse complete(String model, List<Msg> messages, List<ToolSpec> tools); }
interface Agent { AgentResult run(AgentRequest req); }              // Base Agent (loop, budget, iteration cap)
interface CapabilityRegistry { List<ToolSpec> toolsFor(UUID agentId); ToolResult invoke(UUID agentId, ToolCall c); }

// Knowledge (namespace-scoped, isolation enforced here)
interface KnowledgeStore {
  List<KnowledgeEntry> search(UUID agentId, String query, int k);
  KnowledgeEntry upsert(UUID agentId, KnowledgeEntry e);           // rejects cross-namespace ids
}

// Orchestrator
interface Orchestrator { AssistantReply handle(UUID userId, String message); }   // Jizza only entry point

// Connectors
interface OAuthService { URI startAuth(UUID connectionId, UUID sessionId); Connection completeCallback(String state, String code); }
interface ToolGenerator { List<GeneratedTool> fromApiDoc(UUID connectionId, byte[] doc, DocFormat fmt); }
interface ToolExecutor { ToolResult execute(GeneratedTool t, JsonNode args, IdempotencyKey key); } // SSRF-guarded, auth-injected

// Scheduler + durable workflow engine (custom Postgres saga)
interface SchedulerService { ScheduledTask schedule(TaskSpec s); void cancel(UUID taskId); }
interface WorkflowEngine {
  WorkflowRun start(UUID workflowId, int version, JsonNode input, String triggerSource);
  WorkflowRun resume(UUID runId, JsonNode instruction);            // continue a paused run
}                                                                   // checkpoints each node; resumes from last

// Learned procedures
interface ProcedureService { Workflow propose(UUID agentId, UUID sourceTaskId, JsonNode graph);
  void approve(UUID workflowId); void reject(UUID workflowId, String feedback); }  // approve → bind to task

// Notifications
interface NotificationBus { Notification raise(NotificationKind k, UUID sourceAgent, Ctx ctx);
  void answer(UUID notificationId, Answer a); }                     // routes to Jizza; expiry+escalation

// Autonomy & safety (checked before every tool call / autonomous run)
interface AutonomyGate { Decision evaluate(UUID agentId, String toolRef, JsonNode args); } // allow|ask|deny
interface KillSwitch { boolean paused(UUID userId); void setPaused(UUID userId, boolean v); }

// Cross-cutting
interface KeyProvider { SecretKey masterKey(); }                    // local keystore now, KMS later
interface EncryptionService { byte[] encrypt(byte[] plain); byte[] decrypt(byte[] ct); }   // AES-GCM envelope
interface StorageProvider { String put(byte[] data, String name); byte[] get(String ref); }
interface AuditLog { void record(ActorType t, UUID actor, String action, String target, JsonNode detail); }
```

**Invariants every implementer honors:** namespace isolation in `KnowledgeStore`; `AutonomyGate` +
`KillSwitch` consulted in `CapabilityRegistry.invoke` and the scheduler/engine before any autonomous
action; secrets decrypted only server-side and never placed in `LlmClient` messages or logs; every
consequential call goes through `AuditLog`; `ToolExecutor` blocks private/metadata IPs and enforces
timeouts + idempotency; `WorkflowEngine` checkpoints and resumes.

---

## 4. HTTP + WS surface (`jizza-web`)
```
POST /api/auth/{register,login,logout,mfa/verify,mfa/setup,recover}      GET /api/auth/sessions
GET  /api/agents   PATCH /api/agents/{id}          (rename, enable/disable)
POST /api/chat                                       WS /ws (stream replies + notifications)
GET/POST /api/connections   POST /api/connections/{id}/test   GET /api/oauth/callback
POST /api/connections/{id}/apidoc  →  generated tools   POST /api/agents/{id}/capabilities
GET/POST /api/workflows   POST /api/workflows/{id}/save-skill   GET /api/workflows/{id}/versions
GET/POST /api/tasks   DELETE /api/tasks/{id}
GET /api/notifications   POST /api/notifications/{id}/answer     (approve|deny|submit-instruction)
GET/PUT /api/autonomy/policies   POST /api/autonomy/kill-switch
GET /api/activity   (audit view)     POST /api/webhooks/{token}   (inbound)
```
All guarded by session auth; step-up re-auth required for connections, autonomy/security changes, and
high-impact approvals. Errors as RFC 9457.

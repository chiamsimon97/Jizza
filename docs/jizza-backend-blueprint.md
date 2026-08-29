# Jizza — Backend Blueprint (Phase 2)

*One build spec per module, in dependency order. Every module: Java 21 + Spring Boot 3.x, package
`com.jizza.<module>`, conforms to the **Architecture Contract** and the **Charter**. A module exposes
only its `jizza-common` interfaces; it never reaches into another module's internals. Each spec lists
what it **owns**, **implements/exposes**, **key components**, **endpoints**, **depends on**, and its
**acceptance checks**. Build in this order.*

---

## 0 — `jizza-common`  (shared kernel — build first)
- **Owns:** shared enums, DTO records, `ToolSpec/ToolCall/ToolResult`, bus event records, RFC 9457
  error helpers, all interface definitions from the Contract §3.
- **Key:** no business logic; pure contracts. MapStruct mapper interfaces declared here, generated per
  module.
- **Depends on:** nothing. **Accept:** compiles; every Contract interface + enum present.

## 1 — `jizza-persistence`
- **Owns:** all JPA entities + Spring Data repositories for every Contract §1 table; **Flyway**
  migrations (`V1__init.sql` = the full schema); pgvector type registration.
- **Key:** `@Enumerated(STRING)`; `bytea` fields as `byte[]`; base entity with `id/created_at/
  updated_at`; `user_id` on every user-owned entity; vector column via a custom Hibernate type or
  native queries for similarity search.
- **Depends on:** common. **Accept:** `flyway migrate` builds the schema clean; repositories load; a
  pgvector similarity query returns ordered results.

## 2 — `jizza-security`
- **Owns:** `users`, `sessions`, `audit_log`, `kill_switch` access; `KeyProvider`,
  `EncryptionService`, `AuditLog` implementations.
- **Implements/exposes:** Spring Security config; Argon2id password encoder; **TOTP** setup/verify;
  server-side session (httpOnly+Secure+SameSite, idle+absolute expiry, revoke, "log out everywhere");
  **step-up re-auth** filter for sensitive routes; rate-limit + lockout; breached-password check;
  **envelope encryption** (AES-GCM data key wrapped by master key from `KeyProvider`); **append-only
  hash-chained audit log**.
- **Endpoints:** `/api/auth/*`, `/api/auth/sessions`.
- **Depends on:** common, persistence. **Accept:** login+MFA works; sessions expire/revoke; sensitive
  route forces step-up; secrets round-trip encrypted; audit entries chain-verify; lockout triggers.

## 3 — `jizza-agents`  (Base Agent runtime)
- **Owns:** `LlmClient`, `Agent` (Base Agent), `CapabilityRegistry`, `agent_capabilities` access.
- **Implements/exposes:** Anthropic API client behind `LlmClient` (configurable model strings, model
  tiering); the **tool-use loop** with **iteration cap + schema-validated tool calls + per-run
  token/cost budget**; `CapabilityRegistry.invoke` that **consults `AutonomyGate` + `KillSwitch` and
  writes `AuditLog` before executing any tool**, and never puts secrets in LLM context.
- **Depends on:** common, persistence, security. (Uses `AutonomyGate`/`KillSwitch` interfaces; impls
  arrive in module 12 — wire by interface.) **Accept:** an agent completes a tool-use turn; loop cap +
  budget enforced; a denied tool is blocked pre-execution; no secret appears in prompt/log.

## 4 — `jizza-knowledge`
- **Owns:** `knowledge_entries`; `KnowledgeStore` implementation.
- **Implements/exposes:** namespace-scoped search (pgvector) + upsert; **isolation enforced in code**
  (an agent id can only touch its own namespace); classification tagging; knowledge tools
  (`knowledge_search/get/upsert`) registered per specialist.
- **Depends on:** common, persistence, agents. **Accept:** test proves namespace A cannot read/write B;
  semantic search returns relevant entries.

## 5 — `jizza-orchestrator`  (Jizza) + specialists seed
- **Owns:** `agents`, `conversations`, `messages`, `delegation_traces`; agent seeding.
- **Implements/exposes:** `Orchestrator.handle` as the **only** user entry point; `delegate_to_
  specialist` tool; intent routing (serial/parallel), aggregation into one voice, **attribution by
  agent name**; Jizza's own knowledge namespace; seeds Jizza + Health/Finance/Professional (renameable
  names). Specialists run on the Base Agent with domain prompts + own namespaces.
- **Endpoints:** `POST /api/chat`; `GET/PATCH /api/agents` (rename, enable/disable).
- **Depends on:** common, persistence, security, agents, knowledge. **Accept:** a domain question routes
  to the right specialist and returns one aggregated reply; cross-domain fans out and merges; no
  specialist output reaches the user except via Jizza.

## 6 — `jizza-connectors`  (OAuth2 connections)
- **Owns:** `connections`; `OAuthService` implementation.
- **Implements/exposes:** connection CRUD + config (auth-scheme selector, OAuth2 fields);
  **authorization-code handshake** with a public backend callback, **single-use expiring `state` bound
  to session + PKCE**, encrypted token storage, **refresh-token rotation**, transparent auto-refresh,
  **revoke on disconnect**; Test/Revoke/Profile/Webhook-verify hooks; "Test connection".
- **Endpoints:** `/api/connections`, `/api/connections/{id}/test`, `/api/oauth/callback`.
- **Depends on:** common, persistence, security. **Accept:** OAuth2 handshake completes via callback
  (state+PKCE), tokens stored encrypted, auto-refresh + rotation work, revoke clears tokens, test
  reports status.

## 7 — `jizza-connectors-tools`  (API-doc → tools + executor)
- **Owns:** `generated_tools`, `idempotency_keys`; `ToolGenerator`, `ToolExecutor`.
- **Implements/exposes:** API-doc ingestion — **OpenAPI/Swagger deterministic parse + LLM-assisted
  free-form fallback** → one `GeneratedTool` per endpoint (name, description, JSON-Schema input);
  **`ToolExecutor`**: builds the request from method/path/mapping, **injects auth server-side**,
  **SSRF/egress guard** (host allow-list, block private/metadata IPs), timeout + response-size caps,
  **idempotency keys** on side-effecting calls; registers tools into the Capability Registry,
  assignable to agents.
- **Endpoints:** `POST /api/connections/{id}/apidoc`, `POST /api/agents/{id}/capabilities`.
- **Depends on:** common, persistence, connectors, agents. **Accept:** an OpenAPI doc yields correct
  tools; an assigned tool makes a real, idempotent, auth-injected call; SSRF guard blocks internal IPs.

## 8 — `jizza-scheduler`
- **Owns:** `scheduled_tasks`; `SchedulerService`.
- **Implements/exposes:** durable worker on the queue (module 13) waking on `next_run_at`;
  **ShedLock** distributed lock (no double-fire); cron/RRULE with **IST/DST** handling; catch-up policy;
  restart reconciliation. **If a task has `bound_workflow_id`, run that approved workflow; else run the
  instruction through the owning agent.**
- **Endpoints:** `/api/tasks`, `DELETE /api/tasks/{id}`.
- **Depends on:** common, persistence, agents, workflow (module 9, by interface), platform (13).
  **Accept:** one-time + recurring fire with no browser; no double-fire under two workers; missed runs
  reconcile; DST-safe.

## 9 — `jizza-workflow`  (builder model + durable saga engine + interventions)
- **Owns:** `workflows`, `workflow_versions`, `skills`, `workflow_runs`, `workflow_run_steps`;
  `WorkflowEngine`.
- **Implements/exposes:** the node model (Trigger, Connector-tool, Agent-step, Transform, Branch,
  **Human-intervention**, event/webhook trigger); **save-as-skill** (invocable only when approved); the
  **custom Postgres saga engine** — checkpoint each node to `workflow_run_steps`, **resume-from-last-
  completed-node** after restart, **idempotency/exactly-once** on side-effecting nodes, **retries+
  backoff, hard timeouts, dead-letter**; **Transform node runs in the JEXL3 sandbox** (no fs/network);
  **intervention node** pauses the run, persists state, emits `instruction_required`, and **resumes on
  the submitted instruction** feeding downstream.
- **Endpoints:** `/api/workflows`, `/api/workflows/{id}/save-skill`, `/api/workflows/{id}/versions`.
- **Depends on:** common, persistence, agents, connectors-tools, notifications (12, by interface).
  **Accept:** a workflow runs to completion; a killed process resumes from the last node; a side-effect
  node is exactly-once; a failure retries then dead-letters or raises intervention; intervention
  pauses→resumes on instruction; JEXL sandbox has no fs/network.

## 10 — `jizza-procedures`  (learned procedures + versioning + binding)
- **Owns:** procedure lifecycle over `workflows`/`workflow_versions`; `ProcedureService`;
  `propose_workflow` tool.
- **Implements/exposes:** after a job marked repetitive, agent **captures steps → proposes a workflow**
  (`agent_authored/pending_review`) and submits a `workflow_review`; on **approve** → mark approved,
  expose as skill, **bind to the recurring task** (`bound_workflow_id`+version); on **reject** → return
  with feedback; **binding enforcement** (bound job runs the approved workflow exactly; deviation →
  intervention; any change → new version + fresh review); version **history/diff/rollback/disable**.
- **Depends on:** common, persistence, agents, workflow, scheduler, notifications. **Accept:** a
  repetitive job produces a review; approval binds it; the bound job then executes it exactly (no
  improvising); editing re-enters review; rollback works.

## 11 — `jizza-notifications`  (bus + review/intervention + escalation)
- **Owns:** `notifications`; `NotificationBus`.
- **Implements/exposes:** `raise`/`answer` for all four kinds; **all delivery through Jizza, attributed
  by agent name**; `permission_request` halts its task; `workflow_review` → approve/reject;
  `instruction_required` → collects **free-form instruction** and calls `WorkflowEngine.resume`;
  **expiry + reminders + escalation** (auto-cancel policy + out-of-app **email/SMTP** nudge).
- **Endpoints:** `/api/notifications`, `/api/notifications/{id}/answer`.
- **Depends on:** common, persistence, orchestrator, workflow, platform (email). **Accept:** each kind
  round-trips; a review binds on approve; an intervention resumes on submit; a blocking item expires +
  escalates; nothing hangs unseen.

## 12 — `jizza-autonomy`  (kill switch + policy)
- **Owns:** `autonomy_policies`, `kill_switch`; `AutonomyGate`, `KillSwitch`.
- **Implements/exposes:** **global kill switch** (pauses scheduler + halts running workflows at the next
  checkpoint) + per-agent enable/disable; **policy evaluation** (`allow`/`ask`/`deny` with limits,
  precedence) consulted by the Capability Registry, scheduler, and engine **before every autonomous
  action**; ships the Charter's conservative defaults.
- **Endpoints:** `/api/autonomy/policies`, `/api/autonomy/kill-switch`.
- **Depends on:** common, persistence, security. **Accept:** kill switch halts all autonomy at next safe
  checkpoint and resumes; `ask` routes to permission; `deny` blocks; per-agent disable works.

## 13 — `jizza-platform`  (infra & ops)
- **Owns:** `webhook_subscriptions`, `inbound_events`, `files`; queue, realtime, observability,
  storage, cache, cost.
- **Implements/exposes:** **Postgres-backed durable job queue** (drives scheduler + workflow workers,
  concurrency limits, backpressure, dead-letter); **WebSocket(STOMP)/SSE** realtime for streaming
  replies + pushing notifications; **verified inbound webhook** receiver → event/webhook trigger;
  **observability** (Micrometer metrics, JSON logs, optional OTel tracing, Actuator health/readiness);
  **Caffeine** cache; `StorageProvider` (local fs); **LLM cost accounting + budgets**; **data
  lifecycle** (backup/PITR docs, history pruning, **user export + hard-delete**); **email/SMTP** sender.
- **Endpoints:** `POST /api/webhooks/{token}`, health/metrics, export/delete.
- **Depends on:** common, persistence, security. **Accept:** queue runs workers with limits; realtime
  streams; a verified webhook fires a workflow; health/metrics live; export + hard-delete work.

## 14 — `jizza-web`  (API surface + activity)
- **Owns:** REST controllers + WS endpoints for all modules; the **activity/audit** read view.
- **Implements/exposes:** the full HTTP+WS surface from Contract §4; session-auth guard + step-up on
  sensitive routes; RFC 9457 errors; validation; **`GET /api/activity`** rendering the audit log.
- **Depends on:** all feature modules. **Accept:** every endpoint reachable + guarded; step-up enforced;
  activity view reads the audit log.

## 15 — `jizza-app`  (assembly)
- **Owns:** Spring Boot main; wires all modules; `application.yml` + profiles (`local`,`worker`);
  Docker Compose (Postgres+pgvector, mail catcher); single dev run bringing up **API + worker**.
- **Depends on:** everything. **Accept:** `mvn spring-boot:run` boots the whole app; Flyway migrates;
  the full acceptance suite (below, in the assembly prompt) can run end-to-end.

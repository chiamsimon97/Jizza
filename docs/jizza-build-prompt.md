# Build "Jizza" — A Secure, Backend-Driven Personal Assistant Platform (Named Orchestrator + Specialist Agents, Connectors, Workflow Builder, Learned Procedures)

## Role

You are a **senior software architect and full-stack engineer specialising in secure LLM multi-agent
platforms**. You design and build production systems that orchestrate multiple Claude-powered agents
— each with its own name and isolated memory — behind a single user-facing agent, with hardened
authentication, encrypted secrets, a durable resumable workflow engine, an OAuth2 connector layer
that turns API docs into callable tools, learned procedures the agents propose for review and are
then bound to follow, and a full autonomy-control and audit layer. You treat safety, correctness, and
observability as first-class requirements, not afterthoughts, and you state assumptions where the
spec is silent.

---

## Context

### System Overview
Build a **secure, single-user personal-assistant platform** called **Jizza** (think "Jarvis"). Jizza
is the **only agent the user ever talks to**; behind it sits a team of **named specialist agents**
(Health, Finance, Professional, and more the user can add), each with its own name, private
knowledge, and tools. Jizza routes requests, delegates, and answers in one voice. The system can
connect to external services (OAuth2), turn their API docs into tools, run visual workflows saved as
skills, learn a repeated job into a workflow it submits for review and then follows exactly, and pause
a workflow to ask the user for an instruction. Because agents act autonomously on the user's behalf
over **health, financial, and professional** data and **live third-party tokens**, the platform must
be built to a high security and reliability bar: hardened login, encrypted secrets, per-agent
autonomy controls, a global kill switch, an immutable audit trail, and durable, resumable execution.

### Core Concepts
| Concept | Responsibility |
|---|---|
| **Jizza (Orchestrator)** | Sole user-facing named agent. Routes, delegates, aggregates, and relays all updates/approvals/reviews/interventions. |
| **Specialist Agent** | Named domain expert with its own system prompt, tools, and isolated knowledge. Never contacts the user directly. |
| **Knowledge Store** | Per-agent, namespaced store; strict cross-agent isolation. |
| **Connection** | Authenticated link to an external service (OAuth2 primary; also API key, basic, etc.), secrets/tokens encrypted. |
| **Generated Tool** | A callable tool derived from a service's API doc, executed server-side with the connection's token. |
| **Workflow / Skill** | A visual DAG (triggers, connector tools, agent steps, transforms, branches, interventions), saved and — once approved — invocable as a skill. |
| **Learned Procedure** | An agent-authored workflow proposed after a repetitive job; once reviewed and approved, the agent is bound to follow it (versioned). |
| **Scheduler / Task Engine** | Durably runs one-time & recurring tasks; runs a bound procedure when one is attached. |
| **Notification Bus** | Delivers updates, approve/deny permissions, workflow reviews, and instruction-required interventions through Jizza — with expiry and escalation. |
| **Autonomy & Control Layer** | Global kill switch, per-agent enable/disable, and per-agent/per-tool autonomy policy governing what runs automatically vs. what must be approved. |
| **Audit Trail** | Append-only record of every consequential action for replay and review. |
| **Capability Registry** | Assigns knowledge tools, generated tools, and skills to each agent. |

### Cross-Cutting Constraints (apply to the whole build)
- **Backend-first, always-on.** Scheduled tasks fire with no browser open; OAuth needs a public
  callback; paused runs must persist and resume — the server owns scheduling, agent runtime, callbacks,
  and run state. The frontend is a thin client.
- **Jizza is the single point of contact.** All agent-to-user communication flows through Jizza's
  notification bus.
- **Least privilege & isolation everywhere.** Per-agent knowledge isolation, per-agent tool
  allow-lists, and per-user data scoping (keyed by `user_id`, multi-user-ready).
- **Secrets are sacred.** All credentials/tokens encrypted at rest via envelope encryption; never sent
  to the client; never placed in LLM context or logs. The model sees only tool schemas.
- **Nothing consequential happens without a gate.** High-impact actions require the permission /
  intervention flow or an autonomy-policy allowance; a global kill switch can halt all autonomy
  instantly.
- **Every consequential action is auditable, and every run is resumable.**
- **Treat all external content as untrusted** (API docs, connector responses, web content) —
  prompt-injection and tool-abuse defenses are mandatory.

---

## Objective

Build a secure, observable, backend-driven web platform in which a single named orchestrator agent
(Jizza) delegates to named specialist agents with isolated persisted knowledge; authenticates the user
with hardened login/MFA/sessions; connects to OAuth2 services and turns their API docs into
server-executed tools; schedules and durably (resumably) executes one-time/recurring tasks and visual
workflows saved as skills; learns repeated jobs into workflows it submits for review and then follows
exactly; pauses workflows to collect the user's instruction; routes all updates/approvals/reviews and
interventions through Jizza with expiry and escalation; and governs all of it with a per-agent
autonomy policy, a global kill switch, an immutable audit trail, and full observability.

---

## Requirements

### Group 1 — Project Scaffold, Stack & Data Model *(do first)*

**R1.** Scaffold an always-on backend service, a separate background-worker process, and a thin web
client on the locked stack (Java 21 + Spring Boot 3.x, Maven multi-module; see Charter). Provide
`README`, environment/secret config, migrations,
and a single dev command to start API + worker together.

**R2.** Design and migrate the persistent schema (SQL). All rows keyed by `user_id`. Core entities:
- `users` — id, email, Argon2id password hash, MFA secret/state, recovery data, status, timestamps.
- `sessions` — id, user id, device/agent, issued/expiry (idle + absolute), revoked flag.
- `agents` — id, `key`, **`name`** (user-editable), domain, `system_prompt`, `is_orchestrator`,
  **`enabled`** flag.
- `knowledge_entries` — id, `agent_id` (namespace), title, content, structured JSON, optional
  embedding, **data-classification tag** (e.g. health/financial/PII), timestamps.
- `conversations` / `messages` — user↔Jizza chat + internal delegation trace.
- `connections` — id, service, `auth_scheme`, client id, **encrypted** client secret, auth/token/
  refresh URLs, scopes+separator, PKCE, callback config, token placement, grant type, **encrypted**
  access & refresh tokens, expiry, auto-refresh, Test/Revoke/Profile/Webhook config, status.
- `generated_tools` — id, `connection_id`, name, description, input schema, method, path template,
  param mapping, auth mapping, enabled.
- `workflows` — id, name, description, graph JSON, input schema, trigger config, `origin`
  (`user_built`|`agent_authored`), `status` (`draft`|`pending_review`|`approved`|`rejected`),
  `owning_agent_id`, **`version`**, `source_task_id`.
- `workflow_versions` — immutable snapshots of each approved/edited version for history & rollback.
- `skills` — id, workflow id + version, name, description, parameter schema; invocable only when its
  version is `approved`.
- `workflow_runs` — id, workflow/version id, trigger source, status (`running`|
  `paused_waiting_instruction`|`done`|`failed`|`cancelled`), current node, **per-node result
  checkpoints** (serialized state), started/updated timestamps.
- `scheduled_tasks` — id, owning `agent_id`, title, payload, `schedule_type`, cron/RRULE or run-at,
  next-run-at, `bound_workflow_id` (+version), status, last-run info, **timezone**.
- `notifications` — id, source `agent_id`, `kind` (`info`|`permission_request`|`workflow_review`|
  `instruction_required`), context, related task/workflow/run ids, status, submitted instruction,
  **expires_at**, **escalation_state**, timestamps.
- `autonomy_policies` — id, scope (agent and/or tool/skill), rule (`allow`|`ask`|`deny`),
  optional limits (e.g. value caps), precedence.
- `audit_log` — **append-only**: actor (agent/user), action, target, before/after summary, request id,
  timestamp; tamper-evident (hash-chained recommended).
- `idempotency_keys` — id, key, connection/tool, request fingerprint, result, created-at (for
  exactly-once side effects).
- `webhook_subscriptions` / `inbound_events` — verified inbound endpoints and received events.
- `files` — uploaded API docs / documents (object-storage ref, type, size, scan status).
- `agent_capabilities` — maps each agent to its knowledge tools, generated tools, and skills.

### Group 2 — Identity, Authentication & Sessions

**R3.** Implement **real login**: email + password with **Argon2id** hashing (or passkeys/WebAuthn),
optional Google sign-in. No plaintext or weak hashing anywhere.

**R4.** Implement **MFA** (TOTP and/or WebAuthn) and **step-up re-authentication** required for
high-risk actions — connecting a service, approving a money-moving or otherwise high-impact procedure,
and changing autonomy/security settings.

**R5.** Implement **session management**: httpOnly + Secure + SameSite cookies (or rotating tokens),
idle + absolute expiry, server-side revocation, "log out everywhere," and an active-session list.

**R6.** Implement **account recovery & abuse protection**: secure password reset, brute-force lockout,
rate-limited auth endpoints, and a breached-password check. Enforce the per-user auth boundary so
future multi-user support is additive.

### Group 3 — Security & Data Protection *(cross-cutting; applies to all later groups)*

**R7.** **Key management & envelope encryption.** Manage a master key in a KMS/secret manager with
rotation; field-encrypt all connection secrets/tokens with data keys. The env-var key is dev-only.

**R8.** **SSRF / egress hardening for the tool executor.** Outbound HTTP from generated tools is
restricted to the connection's declared host; block private/internal IP ranges and cloud
metadata endpoints; enforce timeouts and response-size caps.

**R9.** **Sandbox the Transform/code workflow node.** Do not execute arbitrary code. Use a safe,
sandboxed expression engine with no filesystem/network access (e.g. CEL or a JEXL-style engine), or an
isolated worker with hard CPU/memory/time limits.

**R10.** **Prompt-injection & tool-abuse guardrails.** Treat API docs, connector responses, and any
fetched content as untrusted data, not instructions. Enforce per-agent tool allow-lists, block
auto-execution of high-impact tools without a gate (Group 12/13), and keep cross-agent isolation.

**R11.** **Secrets never enter LLM context or logs.** The model receives tool *schemas* only; the
executor injects auth server-side; redact tokens/PII from prompts, traces, and logs.

**R12.** **App & transport hardening.** TLS + HSTS, CORS allow-list, CSRF protection, CSP, strict
input/schema validation at every boundary, and dependency/secret scanning in CI.

**R13.** **Data classification & residency.** Tag health/financial/PII fields (R2), minimize storage,
redact in logs, and honour a configured data region/residency.

**R14.** **Immutable audit trail.** Append-only log of every agent action, tool call, permission grant,
review/approval decision, workflow run, connection change, autonomy-policy change, and login — the
backbone of the review-then-trust model and of action replay.

### Group 4 — Agent Runtime (Base Agent)

**R15.** Implement a reusable **Base Agent** wrapping the Anthropic Messages API: system prompt + a tool
set (from the Capability Registry, filtered by the autonomy policy), a tool-use loop, structured
result. Backend holds the API key.

**R16.** **LLM-loop safety.** Cap tool-call iterations per turn, validate every tool call against its
schema before executing, and enforce a per-run token/cost budget to stop runaway loops.

**R17.** Give specialists knowledge tools scoped to their own namespace only (`knowledge_search`,
`knowledge_get`, `knowledge_upsert`), enforced in code. Give agents `schedule_task`, and give
specialists `notify_user`, `request_permission`, and `propose_workflow` (Group 11). All user-facing
output flows through these tools.

### Group 5 — Jizza Orchestrator

**R18.** Implement Jizza as the orchestrator and the **only** entry point for user messages, with a
`delegate_to_specialist(agent_key, task)` tool.

**R19.** Intent routing: choose the specialist(s), delegate (serial/parallel), aggregate into one reply
in Jizza's voice, attribute contributors **by name**. Cross-domain requests fan out and merge.

**R20.** Jizza owns cross-cutting context (profile, preferences, conversation) in its own namespace.
No specialist output reaches the user except relayed by Jizza.

### Group 6 — Specialist Agents & Isolated Knowledge

**R21.** Implement Health, Finance, and Professional specialists as Base Agent instances — each with a
name, a domain system prompt, and its own knowledge namespace.

**R22.** Enforce isolation: a specialist cannot read another's namespace; cross-domain needs return to
Jizza. Include a test proving namespace A cannot access namespace B.

### Group 7 — Connector Layer: OAuth2 Service Connections

**R23.** Build the **Connection configuration** module + UI (per the reference): auth-scheme selector
(**OAuth v2 primary**; also API Key, Basic, Custom, No Auth) and, for OAuth2, Client ID/Secret,
authorization/token/refresh URLs, scopes+separator, PKCE, Unified Callback, token placement, grant
type.

**R24.** Implement the full **OAuth2 authorization-code handshake with a public backend callback**,
**hardened**: single-use, expiring `state` bound to the session, PKCE, least-privilege scopes,
encrypted token storage, **refresh-token rotation**, transparent auto-refresh, and **revoke on
disconnect**.

**R25.** Support optional per-connection **Test / Revoke / Profile / Webhook-verify** hooks and extra
OAuth fields, plus a "Test connection" action reporting status.

### Group 8 — Connector Layer: API-Doc → Tool Generation

**R26.** Let the user attach a service's **API documentation** and **auto-generate callable tools**
(one per endpoint) with name, description, and input schema. Deterministic parsing for
OpenAPI/Swagger; LLM-assisted extraction fallback for free-form docs. Persist as `generated_tools`.

**R27.** Implement the **tool executor** (subject to R8/R11): build the request from stored
method/path/mapping, inject auth server-side, call the service with timeout/size caps and idempotency
keys (R31) for side-effecting calls, and return the response. Handle errors and rate limits.

**R28.** Register generated tools into the **Capability Registry**, assignable to agents and governed by
the autonomy policy.

### Group 9 — Scheduler / Task Engine

**R29.** Implement a **durable background worker** on a real queue (Group 14) that wakes on
`next_run_at`. If a task has a `bound_workflow_id`, run that approved workflow version; else run the
task's instruction through the owning agent. Record outcomes; recurring tasks compute the next run.

**R30.** **Scheduler correctness:** distributed lock/leasing so a task never double-fires; explicit
timezone/DST handling for cron/RRULE (default IST, configurable); a defined catch-up-vs-skip policy for
missed runs; survive restarts and reconcile on boot.

### Group 10 — Workflow Builder, Skills, Interventions & Durable Execution

**R31.** Build the **n8n-style visual builder**: DAG of **Trigger** (manual|schedule|agent-invoked|
**event/webhook**, Group 14), **Connector-tool**, **Agent step**, **Transform/code** (sandboxed, R9),
**Conditional/branch**, and **Human-intervention** nodes (R33). Persist to `workflows`.

**R32.** **Save as Skill** (name, description, parameter schema) → a Capability-Registry tool, invocable
only when its workflow version is `approved`.

**R33.** **Durable, resumable execution engine.** Run the graph as a persisted state machine/saga:
checkpoint each node's result to `workflow_runs`, and **resume from the last completed node** after a
crash or restart. **Idempotency / exactly-once** on side-effecting nodes via idempotency keys.
**Retries with backoff, hard timeouts, and dead-letter/max-attempts**; a permanent failure raises an
intervention rather than dying silently. **Human-intervention node:** pause the run, persist state,
emit an `instruction_required` notification with the paused `run_id` and context; on the user's
submitted instruction, resume from that node, feeding the instruction downstream. Support a lighter
approve/deny variant too.

### Group 11 — Learned Procedures, Review, Versioning & Binding

**R34.** After completing a job the user marked repetitive, the owning agent uses `propose_workflow` to
capture the executed steps as a workflow (`agent_authored`, `pending_review`, `owning_agent_id`,
`source_task_id`) and submit it for review (a `workflow_review` notification). The agent does not act on
it until approved.

**R35.** On **approval**, mark the version `approved`, expose it as a skill, and **bind** it to the
recurring job (`bound_workflow_id` + version). On **rejection**, keep it out of use and return it with
optional feedback; the agent may revise and re-submit as a new version.

**R36.** Enforce **binding**: a bound job runs its approved workflow **exactly**, never free-forming;
deviations raise an intervention; **any change requires a fresh review as a new version**. Provide
**version history, diff, rollback, and disable** for procedures (each change re-enters review).

### Group 12 — Notification, Permission, Review, Intervention & Escalation

**R37.** `notify_user` → `info`; `request_permission` → `permission_request` **and halt the related
task**. All items attributed **by agent name**, delivered only through Jizza.

**R38.** Handle **`workflow_review`** (present proposed steps → Approve binds, Reject returns with
feedback) and **`instruction_required`** (present paused context → user submits a **free-form
instruction** that resumes the run).

**R39.** **Expiry & escalation** for blocking items: `expires_at`, reminders, an escalation/auto-cancel
policy, and out-of-app nudges (Group 14) so a paused run never hangs forever unseen.

### Group 13 — Autonomy, Control & Safety

**R40.** **Global kill switch + per-agent enable/disable.** One control instantly pauses all autonomous
activity (scheduler + running workflows halt safely at the next checkpoint); per-agent toggles disable
individual agents. Enforce these before any autonomous action executes.

**R41.** **Per-agent / per-tool autonomy policy.** A configurable rules layer (`allow`|`ask`|`deny`,
with limits like value caps) deciding what each agent/tool may do automatically vs. route to the
permission/intervention gate. The runtime consults it before every tool call and every autonomous run.
Generalize the existing permission model into this policy.

### Group 14 — Platform, Infrastructure & Operations

**R42.** **Durable job/queue infrastructure** (e.g. Redis/BullMQ or a Postgres-backed queue) driving the
scheduler and workflow workers as a separate process, with concurrency limits and backpressure.

**R43.** **Real-time transport** (WebSocket/SSE) to stream Jizza's replies and push notifications and
interventions the moment they occur.

**R44.** **Inbound webhooks / event triggers:** a verified inbound receiver (using the connector's
webhook-verify config) feeding an event/webhook workflow trigger.

**R45.** **Observability:** structured logs + distributed tracing across Jizza→specialist→tool→
connector, metrics (task success rate, run latency, LLM spend), alerting, and health/readiness
endpoints.

**R46.** **Caching layer** (e.g. Redis) for sessions, rate-limit counters, and embeddings; cautious,
opt-in caching of connector responses.

**R47.** **File/attachment handling:** object storage for uploaded API docs/documents, with size limits,
type validation, and scanning.

**R48.** **Data lifecycle:** backups + point-in-time recovery, migrations with rollback,
conversation-history pruning, and full user **export + hard-delete** ("forget everything").

**R49.** **LLM cost controls:** model tiering (cheap model for routing, stronger model for hard tasks),
token accounting, caching, and budgets/alerts.

### Group 15 — Reliability, Concurrency & Testing

**R50.** **Concurrency & consistency:** optimistic locking on workflows/tasks/knowledge, transactional
status transitions, and an **outbox pattern** so notifications and side effects emit reliably.

**R51.** **Automated tests for the guarantees:** namespace isolation, procedure binding ("the agent
can't deviate"), the OAuth handshake, run→pause→resume, kill-switch halt, and autonomy-policy
enforcement; plus contract tests for generated tools.

### Group 16 — Frontend (Thin Client)

**R52.** **Auth & onboarding UI:** sign-up / sign-in / MFA / recovery / step-up re-auth, and a
**first-run onboarding** flow (create account, name agents, seed initial knowledge, connect first
services, set autonomy defaults).

**R53.** **Chat with Jizza** (streaming via R43; delegation shown subtly and attributed **by agent
name**; single-voice illusion preserved).

**R54.** **Notifications area** surfacing all four item kinds through Jizza — updates, approvals,
**workflow reviews** (view proposed steps → Approve/Reject+feedback), and **instruction-required
interventions** (read context → type instruction → submit to continue) — with **expiry/escalation**
indicators.

**R55.** **Tasks** (upcoming one-time/recurring with owning agent name, next-run, status, cancel;
indicate when a task **follows an approved procedure**), **Connectors** (create/test connections, attach
API doc, view/assign generated tools), and **Workflow Builder** (canvas incl. intervention node;
saved-skills list showing origin + status; **procedure version history / diff / rollback**; per-agent
capability assignment; **agent rename**).

**R56.** **Control & transparency surfaces:** an **Autonomy & Control settings** area (global kill
switch, per-agent enable/disable, per-agent/per-tool autonomy rules) and a readable **Activity / audit
view** of what agents did.

### Group 17 — Auth Boundary & Config *(baseline)*

**R57.** All endpoints guarded by authentication; every row keyed by `user_id`; secrets/tokens encrypted
at rest; the Anthropic key, client secrets, and tokens live **server-side only**.

---

## Acceptance Criteria

- [ ] Login requires strong-hashed credentials + MFA; sessions expire, can be revoked, and high-risk
      actions force step-up re-auth; auth endpoints are rate-limited and lock out on abuse.
- [ ] All connection secrets/tokens are envelope-encrypted at rest, never sent to the client, and never
      appear in LLM context or logs (model sees tool schemas only).
- [ ] The tool executor cannot reach private/internal IPs or metadata endpoints and enforces
      timeout/size caps; the Transform node runs only in a sandbox with no fs/network.
- [ ] Every consequential action (agent action, tool call, approval, review decision, run, connection/
      autonomy change, login) is recorded in an append-only audit log and is replayable.
- [ ] The user only ever messages Jizza; every agent output reaches the user through Jizza, attributed
      **by name**; each agent is renameable.
- [ ] Specialist knowledge namespaces are isolated (test-proven); cross-domain requests fan out and
      merge via Jizza.
- [ ] OAuth2 connections complete via a hardened backend callback (state+PKCE), auto-refresh with token
      rotation, and revoke on disconnect; "Test connection" reports status.
- [ ] An attached API doc generates callable tools (one per endpoint); an assigned tool makes a real,
      idempotent API call using the connection's token.
- [ ] Workflows are built visually, saved as skills, and invocable only once approved; runs are
      **durable and resume from the last completed node** after a restart; side-effecting nodes are
      exactly-once; failures retry, dead-letter, or raise an intervention.
- [ ] An intervention node pauses a run, appears as "instruction needed," and continues on the user's
      free-form instruction; the paused run survives a restart.
- [ ] A repetitive job produces an agent-proposed workflow for review; approving binds it and the
      recurring job then executes it **exactly** (no improvising; deviation → intervention); procedures
      are versioned with history/diff/rollback and any change re-enters review.
- [ ] The **global kill switch** instantly halts all autonomy at the next safe checkpoint; per-agent
      toggles work; the **autonomy policy** is consulted before every tool call and autonomous run.
- [ ] Blocking notifications have expiry, reminders, and escalation so paused work never hangs unseen.
- [ ] Scheduler never double-fires (distributed lock), handles IST/DST, and reconciles missed runs.
- [ ] Real-time streaming/notifications, observability (logs/traces/metrics/health), backups + PITR, and
      user export + hard-delete all work.

---

## Locked Decisions (Phase 0 — see Project Charter for full detail & rationale)

All prior open questions are resolved; build to these.

- **Stack:** Java 21 (LTS) + Spring Boot 3.x, Maven multi-module, Spring Data JPA/Hibernate +
  PostgreSQL + Flyway + pgvector, Spring Security, WebSocket(STOMP)/SSE, React + Vite + React Flow
  client. Modular monolith, single deployable app running API + worker.
- **Durable execution:** custom **Postgres-backed saga / state machine** (no Temporal) — checkpointed
  `workflow_runs`, resume-from-last-node, retries/backoff, dead-letter, idempotency keys.
- **Runtime:** **local-first** — Docker Compose for Postgres(+pgvector) + local mail catcher;
  cloud-swappable behind `KeyProvider` / `StorageProvider` / cache interfaces. OAuth callback base URL is
  a config value; local dev uses a tunnel (ngrok/cloudflared) or localhost-permitting providers.
- **Auth:** email+password (**Argon2id**) + **TOTP** MFA (passkeys/WebAuthn fast-follow; Google later);
  server-side sessions (httpOnly+Secure+SameSite), idle+absolute expiry, revoke/"log out everywhere."
- **Transform-node sandbox:** **Apache Commons JEXL3**, no fs/network, no arbitrary code.
- **Learned procedures:** user explicitly marks a routine (agent may suggest); bound procedures run
  **deterministically**, deviation → intervention.
- **Autonomy defaults:** read/inform automatic; send/pay/create/delete/external side-effects → `ask`.
  Finance read-only unattended (money movement always ask); Health/Professional read+draft unattended
  (external sends ask). Kill switch present, system active by default.
- **Data residency:** local-first on-machine; when hosted → **IN region** + IN backups.
- **Escalation channel:** **email (SMTP)** in v1 (Telegram/push later).
- **API-doc ingestion:** OpenAPI/Swagger deterministic + LLM-assisted free-form fallback.
- **Launch auth schemes:** **OAuth2 + API Key** (Basic/JWT later).
- **Launch specialists:** Health, Finance, Professional + user-renameable placeholder names.
- **Error model / mapping / secrets:** RFC 9457 problem+json; MapStruct; envelope encryption (AES-GCM
  data keys wrapped by a `KeyProvider` master key).

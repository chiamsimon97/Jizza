# Jizza — Integration Manifest

Living record of module → interfaces exposed/consumed → status, updated at the end of every module
step. Contract gaps are recorded here rather than silently diverging (see the last section).

Legend: **status** is `complete` (built + module tests green), `partial`, or `pending`.

## Test conventions

| Suffix | What it is | Runs where |
|---|---|---|
| `*Test` | Unit / slice tests, no external services | Everywhere |
| `*DbTest`, `*E2eTest` | Backed by a real PostgreSQL 16 + pgvector | Where one is reachable: `JIZZA_TEST_DB_URL`, or a Docker daemon for `pgvector/pgvector:pg16`. Self-skip otherwise via `@EnabledIf(TestDatabase#available)`, repeated on every concrete class because JUnit does not inherit the condition |

There is no faithful in-memory stand-in for `citext` / `inet` / `jsonb` / `vector`, so DB-backed
tests use the real image rather than a divergent H2 schema.

## Modules

Two aggregates and the deployable that assembles them (see `docs/REFACTOR-HANDOFF.md` for the
history of the split, if it is still present). **`jizza-engine`** (`com.jizza.engine.*`) is generic
mechanism: no HTTP surface, no knowledge of any particular user journey, policy stripped out where
a use-case exists to hold it. **`jizza-platform`** (`com.jizza.platform.*`) is the entry point:
every client call arrives there, and that is where the product's decisions live — validation,
defaults, ownership checks, audit calls, and everything a controller does beyond HTTP mapping.
`platform-usecases` reaches the engine only through `engine-api`, never through a concrete engine
module.

### `jizza-engine`

| # | Module | Exposes | Consumes | Status |
|---|---|---|---|---|
| 1 | `engine-api` | Every published port interface (`WorkflowRegistry`, `SchedulerService`, `ProcedureService`, `NotificationBus`, `WebhookRegistry`, `ToolCatalog`, `ToolGenerator`, `AuthService`, `DataLifecycleService`, `AuditLog`, …), every DTO/enum, the tool contract, RFC 9457 error model, `WorkflowGraph`, `Redaction`, `ToolSchemaValidator`, `RunBudget` | — | complete |
| 2 | `engine-persistence` | 30 JPA entities + Spring Data repositories for every table, Flyway `V1__init.sql` + `V2__platform.sql`, pgvector search (`KnowledgeVectorSearch`), `PersistenceConfig` | `engine-api` | complete |
| 3 | `engine-security` | `KeyProvider`, `EncryptionService` (AES-GCM envelope), `HashChainAuditLog` (implements `AuditLog`), `DefaultAuthService` (implements `AuthService`), `SessionService`, `TotpService`, `StepUpInterceptor`, `CurrentUser`/`SessionCookies` (session infra used directly by `platform-api`), `SecurityConfig` | `engine-api`, `engine-persistence` | complete |
| 4 | `engine-agents` | `LlmClient` (Anthropic, tiered models), `Agent` (Base Agent), `CapabilityRegistry`, `CostAccountant`, permissive autonomy fallbacks | `engine-api`, `engine-persistence`, `engine-security` | complete |
| 5 | `engine-knowledge` | `KnowledgeStore` (namespace-enforced), `EmbeddingProvider`, knowledge tool provider | `engine-api`, `engine-persistence`, `engine-agents` | complete |
| 5b | `engine-memory` | `MemoryStore`, `MemoryContextProvider`, `MemoryLearner` (hybrid user memory), memory tool provider, retention/re-embedding | `engine-api`, `engine-persistence`, `engine-knowledge` | complete |
| 6 | `engine-orchestrator` | `Orchestrator` (Jizza), `AgentDirectory`, `AgentSeeder`, delegation tools (`delegate_to_specialist`, `delegate_to_specialists`) | `engine-api`, `engine-persistence`, `engine-security`, `engine-agents`, `engine-knowledge` | complete |
| 7 | `engine-connectors` | `OAuthService` (state+PKCE, rotation, revoke, test), `ConnectionService` (implements `ConnectionStore`), `EgressGuard`, `GuardedHttpClient`, `ConnectorProperties` (infra config, used directly by `platform-api`) | `engine-api`, `engine-persistence`, `engine-security` | complete |
| 8 | `engine-connectors-tools` | `ApiDocIngestService` (implements `ToolGenerator` + `ToolCatalog`: OpenAPI/LLM generation, size/type limits, `find`/`list`/`setEnabled`), `ToolExecutor` (auth injection, SSRF guard, idempotency), `GeneratedToolProvider`, `IdempotencyService` | `engine-api`, `engine-persistence`, `engine-connectors`, `engine-agents` | complete |
| 9 | `engine-scheduler` | `DefaultSchedulerService` (implements `SchedulerService`, policy-free: no audit on cancel/mark-repetitive), `TaskDispatcher` (ShedLock + committed row claim), `RecurrenceCalculator` (cron + RRULE, DST-safe, catch-up policies), `schedule_task` tool | `engine-api`, `engine-persistence`, `engine-security`, `engine-agents`, `engine-workflow` | complete |
| 10 | `engine-workflow` | `SagaWorkflowEngine` (implements `WorkflowEngine`: per-node checkpoints, resume-from-last-node, retry/backoff, dead-letter, intervention), `DefaultWorkflowRegistry` (implements `WorkflowRegistry`, policy-free: no origin/ownership defaulting), `WorkflowValidator`, JEXL sandbox, node executors, `RunRecovery`, skill tools | `engine-api`, `engine-persistence`, `engine-security`, `engine-agents`, `engine-connectors-tools`, `engine-autonomy` (by interface) | complete |
| 11 | `engine-procedures` | `DefaultProcedureService` (implements `ProcedureService`: `propose`/`approve`/`reject`/`revise` stay here because they are called by `ProposeWorkflowToolProvider`/`DefaultNotificationBus` from inside the engine; only `rollback`/`disable`'s audit moved out), `BindingEnforcer` + `DeviationRecorder`, `propose_workflow` tool. `ProcedureLearner` moved to `platform-usecases` (`ProcedureLearningUseCase`) | `engine-api`, `engine-persistence`, `engine-agents`, `engine-workflow`, `engine-scheduler`, `engine-notifications` | complete |
| 12 | `engine-notifications` | `DefaultNotificationBus` (single voice, attributed by agent name; kept business-shaped because `answer()` reaches synchronously into `WorkflowEngine`/`ProcedureService` with no DTO-only seam to lift a use-case through), `EscalationSweeper` (remind → escalate → expire), `notify_user` / `request_permission` tools | `engine-api`, `engine-persistence`, `engine-security`, `engine-infra` (realtime, mail) | complete |
| 13 | `engine-autonomy` | `PolicyAutonomyGate` (implements `AutonomyGate`: per-agent/per-tool rules, category tokens, globs, value caps, conservative default in code deliberately, not in a deletable row), `DefaultKillSwitch`, `AutonomyPolicyService` (implements `AutonomyPolicyStore`) | `engine-api`, `engine-persistence`, `engine-security` | complete |
| 14 | `engine-infra` | `JobQueue` (`for update skip locked`), `RealtimeGateway` (STOMP), `StorageProvider`, `MailSender`, `WebhookService` (implements `WebhookRegistry`: token + HMAC dispatch, policy-free — ownership on `unsubscribe` moved to `WebhookUseCase`), `DefaultDataLifecycleService` (implements `DataLifecycleService`: export / hard delete / pruning), `PlatformProperties` (infra config, used directly by `platform-api`), `RequestIdFilter`, Caffeine cache, outbox | `engine-api`, `engine-persistence`, `engine-security`, `engine-workflow` | complete |

### `jizza-platform`

| # | Module | Exposes | Consumes | Status |
|---|---|---|---|---|
| 15 | `platform-usecases` | The business logic: `WorkflowUseCase`, `ProcedureReviewUseCase`, `ProcedureLearningUseCase`, `TaskUseCase`, `NotificationUseCase`, `WebhookUseCase`, `GeneratedToolUseCase`, `ActivityUseCase`, `ConnectionUseCase`, `SkillUseCase`, `AgentTeamUseCase`, `AgentAdminUseCase`, `AutonomyDefaultsUseCase`, `AutonomyUseCase` — validation, defaults, ownership checks and audit calls that used to live in the engine services above | `engine-api` only (enforced by a `maven-enforcer` `bannedDependencies` rule) | complete |
| 16 | `platform-api` | Contract §4 HTTP surface (13 controllers), STOMP `/ws`, RFC 9457 `GlobalExceptionHandler`. Reaches `engine-security`/`engine-infra`/`engine-connectors` directly only for cross-cutting infrastructure (`CurrentUser`, `SessionCookies`, `StepUpInterceptor`, `PlatformProperties`, `ConnectorProperties`) — everything else goes through `platform-usecases` or an `engine-api` port | `platform-usecases`, `engine-api`, `engine-security`, `engine-infra`, `engine-connectors`; every other `engine-*` module at runtime scope only, for Spring to wire | complete |
| 17 | `jizza-app` | Spring Boot main, `local` + `worker` profiles, `docker-compose.yml`, README, the end-to-end acceptance suite (93 tests across 6 classes) | `platform-api` | complete |

## Contract gaps and deliberate resolutions

1. **Contract §3 domain type names vs §2 DTO names.** §3 sketches use `Connection`, `GeneratedTool`,
   `Workflow`, `WorkflowRun`, `ScheduledTask`, `Notification`; §2 lists `ConnectionDto`,
   `GeneratedToolDto`, `WorkflowDto`, `RunDto`, `TaskDto`, `NotificationDto`. Rather than carry two
   parallel model families, the interfaces use the §2 `*Dto` records. `KnowledgeEntry` keeps its §3
   name because §2 does not list a DTO for it.
2. **`V2__platform.sql` additions.** The Contract's table list does not include the durable job queue
   (R42), the ShedLock table (R30), the transactional outbox (R50), LLM usage accounting (R49), OAuth
   `state`/PKCE storage (R24) or password-reset tokens (R6), all of which the Blueprint requires.
   They live in `V2` with column-level comments, leaving `V1` as the Contract schema verbatim.
3. **Hardening columns on Contract tables** (also `V2`): `sessions.step_up_at` (R4),
   `connections.base_url` for the egress allow-list (R8), `scheduled_tasks.repetitive` /
   `catch_up_policy` (R30/R34), `workflows.graph_fingerprint` for binding proof (R36),
   `workflow_run_steps` unique `(run_id, node_id)` for exactly-once (R31), `@Version` optimistic-lock
   columns on workflows/runs/tasks/knowledge (R50).
4. **`AgentDirectory`, `WorkflowRegistry`, `BindingEnforcer`, `CostAccountant`, `EgressGuard`,
   `JobQueue`, `RealtimeGateway`, `MailSender`, `ToolProvider`** are additional `engine-api`
   interfaces not sketched in §3. They exist so modules can read each other's data without reaching
   into internals, which the Charter forbids. `ToolProvider` in particular is what lets knowledge,
   connector tools, skills, delegation, scheduling, notifications and procedure proposal all
   contribute tools while `CapabilityRegistry.invoke` stays the single gated execution path.
5. **Controllers live in `platform-api`, not alongside the engine mechanism they call.** This
   started as the Blueprint's "web owns REST controllers + WS endpoints for all modules" and was
   later formalized into the full engine/platform split recorded in `docs/REFACTOR-HANDOFF.md`:
   every controller reaches the engine only through an `engine-api` port or a `platform-usecases`
   use-case that holds the validation, defaults, ownership checks and audit calls a controller used
   to have inline. Engine modules stay free of HTTP and business-decision dependencies.
6. **The egress guard lives in `engine-connectors`, alongside the OAuth handshake rather than in a
   separate module.** The OAuth token exchange needs the same SSRF protection as a generated tool
   call, and `engine-connectors-tools` depends on `engine-connectors`, so both share one
   implementation rather than two.
7. **MFA between password and TOTP uses a short-lived in-memory token**, not a database row: the
   Contract has no table for a half-completed login, and no session cookie is issued until the second
   factor succeeds.
8. **Transaction propagation is a design decision, recorded here because it is invisible in the
   interfaces.** `AuditLog.record` runs `REQUIRES_NEW`: an audit entry must survive the failure it
   describes, and the per-user advisory lock it takes must not be held for a caller's whole
   transaction (holding it there deadlocked cross-domain fan-out). `NotificationBus.raise` runs
   `REQUIRED`: a notification points at the run, task or workflow it is about, so it cannot commit
   before that row exists. `AuthService.register` therefore commits the user through a
   `TransactionTemplate` before auditing, and `Orchestrator.handle` is deliberately *not*
   transactional so a turn never holds a connection across model calls and parallel delegation.
9. **`audit_log.seq`.** The Contract's `audit_log` has no ordering column beyond `created_at`, which
   is not unique enough to chain on. A `bigserial seq` gives the chain a total order per user;
   `prev_hash`/`hash` are computed over the previous entry by `seq`.
10. **`BindingEnforcer.assertBound` runs `REQUIRES_NEW` and records through `DeviationRecorder`.**
   Refusing a run means throwing, and a throw inside the caller's transaction marks it rollback-only
   — which took the scheduler's own record of the failed run down with it, and rolled back the
   intervention that was supposed to ask the user what to do. The check now owns its transaction and
   the deviation is committed by a separate one before the throw. The fingerprint is compared against
   the graph the engine would actually load (the live row for the current version, the snapshot
   otherwise), not the snapshot against itself.
11. **CSRF uses eager token loading plus a `CsrfCookieFilter`.** Spring Security 6 defers CSRF token
   loading, which leaves a single-page client with no token to echo and lets a request that clears
   the cookie finish without issuing a replacement. `setCsrfRequestAttributeName(null)` plus the
   filter is Spring's documented SPA arrangement.
12. **`LoginAttempts` is a separate `REQUIRES_NEW` service.** Recording a failed login inside
   `AuthService.login` was rolled back by the exception that followed it, so lockout never triggered.
13. **The dispatch lock hold is a literal `PT5S`, not configuration.** ShedLock holds
   `jizza-task-dispatch` briefly after each tick so two workers with skewed clocks cannot both claim
   it. The acceptance suite drives ticks back to back, so it clears the hold explicitly
   (`E2eSupport.releaseDispatchLock`) rather than the codebase carrying a knob that exists only for
   tests.
14. **`/actuator/prometheus` is not exposed.** `health`, `info` and `metrics` are. Adding
   `micrometer-registry-prometheus` did not activate Boot's scrape endpoint in this configuration, so
   the exposure list no longer advertises an endpoint that returns 404; wiring a registry is a
   follow-up, not a silent divergence.
15. **A path with no handler returns 404, not 500.** `GlobalExceptionHandler` handles
   `NoResourceFoundException` / `NoHandlerFoundException` explicitly; without it a mistyped URL fell
   through to the catch-all and was logged at ERROR as an internal failure.
16. **Agent memory files (`V3__agent_memory_files.sql`).** The Contract's `files` table has no
    owner below the user, so `files.agent_id` and `files.knowledge_entry_id` were added. A memory
    file lives in exactly one agent's namespace, is envelope-encrypted before it reaches storage,
    and any readable text is indexed as a knowledge entry in that same namespace
    (`MemoryFileStore`, `AgentMemoryUseCase`).
17. **Connector (Service) module (`V4__connector_services.sql`).** A `connections` row is now the
    definition of a Service: metadata (`slug`, `description`, `category`), a draft/live status, its
    auth scheme and the user's credential for it. `generated_tools` are the service's raw APIs
    (`label`, `kind` added). New tables hold resources, actions, triggers, variables and the run
    history; `agent_capabilities` accepts `service_action`. The table names were kept so the OAuth,
    executor and egress paths needed no rewrite; the ports are `ServiceDefinitions` and
    `ServiceRunner`, and agents reach actions only through `ServiceActionToolProvider`, which hides
    every action of a draft service.

18. **Hybrid memory (`V5__hybrid_memory.sql`).** Per-agent knowledge has no notion of who a fact is
    about and is only seen when an agent searches for it, so a user-level memory was added beside it
    rather than in place of it: subjects (people, accounts, plans, optionally linked to a
    `connections` row), typed relations between them, and facts with category, agent-domain
    visibility, provenance, confidence, `fact_key` conflict slots, history and pgvector embeddings.
    `BaseAgent` places the relevant, in-scope facts in every agent's system prompt through the
    `MemoryContextProvider` port, the orchestrator hands finished turns to `MemoryLearner`, and the
    user manages it at `/api/memory`. Domain scoping is bound into the SQL like R22. Tool-call
    audit entries for tools that write personal detail (`memory_remember`, `memory_describe`,
    `knowledge_upsert`) now redact their arguments (`ToolProvider.redactArguments`), so content
    never reaches the append-only log (R13). Design and configuration: `docs/MEMORY.md`.

## Acceptance suite

`jizza-app/src/test/java/com/jizza/app/e2e` — 98 tests, the whole application booted against a real
PostgreSQL and driven through its own HTTP API. Only two things are substituted: the model is
scripted, and the background pollers are switched off so tests drive them deterministically.

| Class | Tests | Covers |
|---|---|---|
| `AuthAndSecurityE2eTest` | 17 | register → login → TOTP, session idle/absolute expiry and revocation, step-up, lockout, secrets round-tripping encrypted with no token or PII in logs or model payloads |
| `AgentsAndChatE2eTest` | 11 | single point of contact, routing, fan-out merged into one voice, per-agent knowledge isolation, no direct route to a specialist |
| `ConnectorsE2eTest` | 14 | OAuth2 with state + PKCE, auto-refresh and rotation, revoke, test connection, OpenAPI → tools, auth-injected idempotent call, SSRF block |
| `SchedulerAndWorkflowE2eTest` | 14 | one-time and recurring tasks firing with no browser, no double-fire, missed-run reconciliation, DST safety, checkpointed runs resuming from the last completed node, exactly-once side effects, retry → dead-letter or intervention |
| `ProceduresAndAutonomyE2eTest` | 19 | repetitive job → proposal → review → approve binds → exact execution, deviation → intervention, edit re-review, rollback, kill switch halting and resuming, `deny` blocked, `ask` routed to permission, per-agent disable |
| `MemoryE2eTest` | 5 | a question about the user's wife reaching Kai with her plan, risk tolerance and Connector already in context (and nothing from health), Jizza seeing only the shared layer, preview/search/correct/forget, user isolation, learning from chat and withdrawing consent, export |
| `PlatformE2eTest` | 18 | blocking items chased then expired, realtime push to a subscribed STOMP client, health and metrics, verified webhook firing a workflow, export without credentials, hard delete, audit chain verification and the activity view |

Run: `mvn test` (DB-backed tests skip) or, with a PostgreSQL,
`JIZZA_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/jizza mvn test`.

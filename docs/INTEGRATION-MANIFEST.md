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

| # | Module | Exposes | Consumes | Status |
|---|---|---|---|---|
| 1 | `jizza-common` | every Contract §3 interface, Contract §2 enums/DTOs/tool contract/bus events, RFC 9457 error model, `WorkflowGraph`, `Redaction`, `ToolSchemaValidator`, `RunBudget` | — | complete |
| 2 | `jizza-persistence` | 30 JPA entities + 29 Spring Data repositories for every Contract §1 table, Flyway `V1__init.sql` (Contract schema) + `V2__platform.sql`, pgvector search (`KnowledgeVectorSearch`), `PersistenceConfig` | `jizza-common` | complete |
| 3 | `jizza-security` | `KeyProvider` (keystore/file/env with rotation), `EncryptionService` (AES-GCM envelope), `AuditLog` (hash-chained), `AuthService`, `SessionService`, `TotpService`, `StepUpInterceptor`, `SecurityConfig` | common, persistence | complete |
| 4 | `jizza-agents` | `LlmClient` (Anthropic, tiered models), `Agent` (Base Agent), `CapabilityRegistry`, `CostAccountant`, permissive autonomy fallbacks | common, persistence, security | complete |
| 5 | `jizza-knowledge` | `KnowledgeStore` (namespace-enforced), `EmbeddingProvider`, knowledge tool provider | common, persistence, agents | complete |
| 6 | `jizza-orchestrator` | `Orchestrator` (Jizza), `AgentDirectory`, `AgentAdminService`, `AgentSeeder`, delegation tools (`delegate_to_specialist`, `delegate_to_specialists`) | common, persistence, security, agents, knowledge | complete |
| 7 | `jizza-connectors` | `OAuthService` (state+PKCE, rotation, revoke, test), `ConnectionService`, `EgressGuard`, `GuardedHttpClient` | common, persistence, security | complete |
| 8 | `jizza-connectors-tools` | `ToolGenerator` (OpenAPI + LLM fallback), `ToolExecutor` (auth injection, SSRF guard, idempotency), `GeneratedToolProvider`, `IdempotencyService` | common, persistence, connectors, agents | complete |
| 9 | `jizza-scheduler` | `SchedulerService`, `TaskDispatcher` (ShedLock + committed row claim), `RecurrenceCalculator` (cron + RRULE, DST-safe, catch-up policies), `schedule_task` tool | common, persistence, security, agents, workflow | complete |
| 10 | `jizza-workflow` | `WorkflowEngine` (Postgres saga: per-node checkpoints, resume-from-last-node, retry/backoff, dead-letter, intervention), `WorkflowRegistry`, `WorkflowValidator`, JEXL sandbox, node executors, `RunRecovery`, skill tools | common, persistence, security, agents, connectors-tools, autonomy (by interface) | complete |
| 11 | `jizza-procedures` | `ProcedureService` (propose → review → approve/bind → revise → rollback → disable), `BindingEnforcer` + `DeviationRecorder`, `ProcedureLearner`, `propose_workflow` tool | common, persistence, agents, workflow, scheduler, notifications | complete |
| 12 | `jizza-notifications` | `NotificationBus` (single voice, attributed by agent name), `EscalationSweeper` (remind → escalate → expire), `notify_user` / `request_permission` tools | common, persistence, security, platform (realtime, mail) | complete |
| 13 | `jizza-autonomy` | `AutonomyGate` (per-agent/per-tool rules, category tokens, globs, value caps, conservative default in code), `KillSwitch`, `AutonomyPolicyService`, `AutonomyDefaults` seeding | common, persistence, security | complete |
| 14 | `jizza-platform` | `JobQueue` (`for update skip locked`), `RealtimeGateway` (STOMP), `StorageProvider`, `MailSender`, `WebhookService` (token + HMAC), `DataLifecycleService` (export / hard delete / pruning), `RequestIdFilter`, Caffeine cache, outbox | common, persistence, security, workflow | complete |
| 15 | `jizza-web` | Contract §4 HTTP surface (13 controllers), STOMP `/ws`, RFC 9457 `GlobalExceptionHandler`, `ActivityService` over the audit chain | all feature modules | complete |
| 16 | `jizza-app` | Spring Boot main, `local` + `worker` profiles, `docker-compose.yml`, README, the end-to-end acceptance suite (93 tests across 6 classes) | everything | complete |

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
   `JobQueue`, `RealtimeGateway`, `MailSender`, `ToolProvider`** are additional `jizza-common`
   interfaces not sketched in §3. They exist so modules can read each other's data without reaching
   into internals, which the Charter forbids. `ToolProvider` in particular is what lets knowledge,
   connector tools, skills, delegation, scheduling, notifications and procedure proposal all
   contribute tools while `CapabilityRegistry.invoke` stays the single gated execution path.
5. **Controllers live in `jizza-web`, not in their feature modules.** Blueprint module 14 says web
   "owns REST controllers + WS endpoints for all modules", so the endpoint lists in modules 2, 5-13
   are implemented there over each module's service. Feature modules stay free of web dependencies.
6. **The egress guard lives in `jizza-connectors` (module 7), one module earlier than the Blueprint
   places it.** The OAuth token exchange needs the same SSRF protection as a generated tool call, and
   module 8 depends on module 7, so both share one implementation rather than two.
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

## Acceptance suite

`jizza-app/src/test/java/com/jizza/app/e2e` — 93 tests, the whole application booted against a real
PostgreSQL and driven through its own HTTP API. Only two things are substituted: the model is
scripted, and the background pollers are switched off so tests drive them deterministically.

| Class | Tests | Covers |
|---|---|---|
| `AuthAndSecurityE2eTest` | 17 | register → login → TOTP, session idle/absolute expiry and revocation, step-up, lockout, secrets round-tripping encrypted with no token or PII in logs or model payloads |
| `AgentsAndChatE2eTest` | 11 | single point of contact, routing, fan-out merged into one voice, per-agent knowledge isolation, no direct route to a specialist |
| `ConnectorsE2eTest` | 14 | OAuth2 with state + PKCE, auto-refresh and rotation, revoke, test connection, OpenAPI → tools, auth-injected idempotent call, SSRF block |
| `SchedulerAndWorkflowE2eTest` | 14 | one-time and recurring tasks firing with no browser, no double-fire, missed-run reconciliation, DST safety, checkpointed runs resuming from the last completed node, exactly-once side effects, retry → dead-letter or intervention |
| `ProceduresAndAutonomyE2eTest` | 19 | repetitive job → proposal → review → approve binds → exact execution, deviation → intervention, edit re-review, rollback, kill switch halting and resuming, `deny` blocked, `ask` routed to permission, per-agent disable |
| `PlatformE2eTest` | 18 | blocking items chased then expired, realtime push to a subscribed STOMP client, health and metrics, verified webhook firing a workflow, export without credentials, hard delete, audit chain verification and the activity view |

Run: `mvn test` (DB-backed tests skip) or, with a PostgreSQL,
`JIZZA_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/jizza mvn test`.

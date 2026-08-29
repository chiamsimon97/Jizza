# Jizza — Integration Manifest

Living record of module → interfaces exposed/consumed → status, updated at the end of every module
step. Contract gaps are recorded here rather than silently diverging (see the last section).

Legend: **status** is `complete` (built + module tests green), `partial`, or `pending`.

## Test conventions

| Suffix | What it is | Runs where |
|---|---|---|
| `*Test` | Unit / slice tests, no external services | Everywhere |
| `*DbTest`, `*E2eTest` | Testcontainers-backed (`pgvector/pgvector:pg16`) | **Only where a Docker daemon is reachable**; self-skip otherwise via `@EnabledIf(Docker#available)` |

There is no faithful in-memory stand-in for `citext` / `inet` / `jsonb` / `vector`, so DB-backed
tests use the real image rather than a divergent H2 schema.

## Modules

| # | Module | Exposes | Consumes | Status |
|---|---|---|---|---|
| 1 | `jizza-common` | every Contract §3 interface, Contract §2 enums/DTOs/tool contract/bus events, RFC 9457 error model, `WorkflowGraph`, `Redaction`, `ToolSchemaValidator`, `RunBudget` | — | complete |
| 2 | `jizza-persistence` | 30 JPA entities + 29 Spring Data repositories for every Contract §1 table, Flyway `V1__init.sql` (Contract schema) + `V2__platform.sql`, pgvector search (`KnowledgeVectorSearch`), `PersistenceConfig` | `jizza-common` | complete |
| 3 | `jizza-security` | `KeyProvider`, `EncryptionService`, `AuditLog`; Spring Security config, Argon2id, TOTP, sessions, step-up, lockout | common, persistence | pending |
| 4 | `jizza-agents` | `LlmClient`, `Agent`, `CapabilityRegistry`, `CostAccountant` | common, persistence, security | pending |
| 5 | `jizza-knowledge` | `KnowledgeStore` | common, persistence, agents | pending |
| 6 | `jizza-orchestrator` | `Orchestrator`, `AgentDirectory` | common, persistence, security, agents, knowledge | pending |
| 7 | `jizza-connectors` | `OAuthService` | common, persistence, security | pending |
| 8 | `jizza-connectors-tools` | `ToolGenerator`, `ToolExecutor`, `EgressGuard` | common, persistence, connectors, agents | pending |
| 9 | `jizza-scheduler` | `SchedulerService` | common, persistence, security, agents | pending |
| 10 | `jizza-workflow` | `WorkflowEngine`, `WorkflowRegistry` | common, persistence, security, agents, connectors-tools | pending |
| 11 | `jizza-procedures` | `ProcedureService`, `BindingEnforcer` | common, persistence, agents, workflow, scheduler | pending |
| 12 | `jizza-notifications` | `NotificationBus` | common, persistence, security | pending |
| 13 | `jizza-autonomy` | `AutonomyGate`, `KillSwitch` | common, persistence, security | pending |
| 14 | `jizza-platform` | `JobQueue`, `RealtimeGateway`, `StorageProvider`, `MailSender`, observability, export/delete | common, persistence, security | pending |
| 15 | `jizza-web` | Contract §4 HTTP + WS surface | all feature modules | pending |
| 16 | `jizza-app` | Spring Boot main, profiles, compose, end-to-end suite | everything | pending |

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
   `JobQueue`, `RealtimeGateway`, `MailSender`** are additional `jizza-common` interfaces not sketched
   in §3. They exist so modules can read each other's data without reaching into internals, which
   the Charter forbids.

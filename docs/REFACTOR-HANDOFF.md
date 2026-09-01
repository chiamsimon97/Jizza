# Engine / Platform split — handoff

Status at commit `06d9ed8` on `claude/jizza-backend-assembly-bk9fwz`.
**Full build green: 494 tests** (`JIZZA_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/jizza mvn install`).

## The goal

Two aggregates. `jizza-engine` is the brain: generic mechanism, no HTTP surface, no knowledge of any
particular user journey. `jizza-platform` is the entry point: every client call arrives there and
**all business logic lives there**, reaching the engine only through `engine-api`.

```
jizza-parent
├── jizza-engine/            com.jizza.engine.*
│   ├── engine-api           ← the published contract; the only module platform may depend on
│   ├── engine-persistence     engine-scheduler      engine-notifications
│   ├── engine-security        engine-workflow       engine-autonomy
│   ├── engine-agents          engine-procedures     engine-infra
│   ├── engine-knowledge       engine-connectors     engine-connectors-tools
│   └── engine-orchestrator
├── jizza-platform/          com.jizza.platform.*
│   ├── platform-usecases    ← business logic
│   └── platform-api         ← controllers + WS (was jizza-web)
└── jizza-app                the single deployable
```

## The rule for deciding where code goes

**Lifecycle of anything the user configures → Platform. Runtime execution of it → Engine.**

| Domain | Platform (business) | Engine (generic) |
|---|---|---|
| Connections | create/update/test/revoke a service | OAuth handshake, token refresh, egress guard |
| Tools | attaching an API doc, granting to agents | OpenAPI→schema generation, execution, idempotency |
| Skills | creating a skill from a workflow | invoking it as a tool at runtime |
| Workflows | save/approve/revise/rollback/disable | saga execution, checkpoints, recovery |
| Tasks | create/cancel/mark-repetitive/bind | dispatcher, runner, recurrence maths |
| Agents | rename/enable, seeding the team | agent runtime, delegation, capability gating |
| Autonomy | policy CRUD, conservative defaults | gate evaluation, kill-switch reads |
| Notifications | answering, reading, listing | raising, delivering, escalating, expiring |
| Auth | register/login/step-up journeys | crypto, sessions, TOTP, audit chain |
| Webhooks | subscribe/unsubscribe | signature verification, dispatch |
| Data | export shape, delete journey | storage, queue, outbox |

## Done

1. **Reactor split** (`ba19cdd`) — 16 flat modules → two aggregates + deployable. `jizza-common` →
   `engine-api`; old `jizza-platform` (queue/realtime/storage/mail/webhooks/observability) →
   `engine-infra`.
2. **Package rename** (`bb570a3`) — all 381 files to `com.jizza.engine.*` / `com.jizza.platform.*`.
3. **Connections + skills** (`d2a0e11`) — `ConnectionUseCase`, `SkillUseCase`.
4. **Agents + autonomy** (`4a2062c`) — `AgentTeamUseCase`, `AgentAdminUseCase`,
   `AutonomyDefaultsUseCase`, `AutonomyUseCase`.
5. **Workflow builder save** (`91da172`) — `WorkflowUseCase`.
6. **Procedure rollback/disable** (`554a396`, narrowed from the original A2 scope — see the gotcha
   below) — `ProcedureReviewUseCase`.
7. **Task cancel/mark-repetitive** (`ccaabd7`, narrowed the same way — `schedule` and
   `bindProcedure`/`unbindProcedure` have the same engine-internal callers as A2) — `TaskUseCase`.
8. **Notification ownership** (`06d9ed8`) — `NotificationUseCase`. `DefaultNotificationBus` is
   unchanged: every method on it manipulates `NotificationEntity` directly or reaches synchronously
   into `WorkflowEngine`/`ProcedureService`, so there was no DTO-only seam to extract a use-case
   through. Only the controller's inline ownership check moved, for consistency with A1-A3.

### The established pattern — follow this exactly for each remaining domain

1. Add a **policy-free port** to `engine-api` (e.g. `ConnectionStore`, `SkillStore`, `AgentStore`,
   `AutonomyPolicyStore`) plus a spec record if the shape is wide (`ConnectionSettings`,
   `AgentSpec`, `SkillSpec`).
2. Make the existing engine service **implement** the port and **strip** from it: validation,
   defaults, ownership checks, `AuditLog` calls. It should store what it is handed, nothing more.
3. Create `platform-usecases/.../<domain>/<X>UseCase.java` holding exactly what you stripped.
   It may import **only** `com.jizza.engine.api.*`.
4. Point the controller in `platform-api` at the use-case. Controllers become HTTP mapping only.
5. **Move the tests** with the code: policy assertions to a `*UseCaseTest` in `platform-usecases`
   (stub the port with an anonymous class — do not mock repositories); storage assertions stay in
   the engine module.
6. Run `JIZZA_TEST_DB_URL=... mvn install` and commit only when green.

## Remaining work

### Task A — nine domains still to extract

In rough order of value. Each is one commit.

| # | Domain | Engine class to reduce | New port | New use-case |
|---|---|---|---|---|
| A1 | Workflows | `DefaultWorkflowRegistry` | extend `WorkflowRegistry` | `WorkflowUseCase` (save, ownership, `USER_BUILT` origin) |
| A2 | Procedures | **done, narrowed** -- see below | -- | `ProcedureReviewUseCase` (rollback/disable audit only) |
| A3 | Tasks | **done, narrowed** -- same coupling as A2 | -- | `TaskUseCase` (cancel/mark-repetitive audit only) |
| A4 | Notifications | **done, thin** -- see below | -- | `NotificationUseCase` (ownership only; no real policy to extract) |
| A5 | Auth | `AuthService` | `IdentityStore` / keep `SessionService` | `AuthUseCase` (register/login/step-up journeys, lockout policy) |
| A6 | Webhooks | `WebhookService` | `WebhookRegistry` | `WebhookUseCase` (subscribe/unsubscribe only; verification + dispatch stay in engine) |
| A7 | Data lifecycle | `DataLifecycleService` | `UserDataStore` | `DataExportUseCase` / `ForgetMeUseCase` (export shape, `DELETE EVERYTHING` confirmation) |
| A8 | API-doc ingestion | `ApiDocIngestService` | `ToolCatalog` | `AttachApiDocUseCase` (size/type limits, which agent gets the tool) |
| A9 | Procedure learning | `ProcedureLearner` | — (uses `Agent` already) | `ProcedureLearningUseCase` (the "write down what you did" prompt is product copy) |

### Task B — enforce the boundary at build time

Add to `jizza-platform/pom.xml` (inherited by both children) a `maven-enforcer-plugin`
`bannedDependencies` rule permitting `com.jizza:engine-api` and banning `com.jizza:engine-*`
otherwise. **This will fail the build until Task A is finished** — `platform-api` currently declares
a dependency on every engine module. Two options:

- Do Task A first, then add the rule and delete the now-unused dependencies from
  `platform-api/pom.xml`; or
- Add the rule now with `<fail>false</fail>` so it prints warnings, and let the shrinking warning
  list drive A1–A9. Flip to `<fail>true</fail>` at the end.

The second is probably the better way to work — the warnings enumerate exactly what is left.

### Task C — documentation

`docs/INTEGRATION-MANIFEST.md` still describes the **old** 16-module layout and is now wrong. It
needs the new structure, and the gap list needs entries for the decisions recorded below.
`README.md` also still refers to the flat module list.

## Decisions already made — keep these, don't re-litigate

- **`engine-api` is the only thing platform may see.** Platform use-cases work in DTOs and never
  touch `engine-persistence` entities or repositories.
- **Two safety invariants stayed in the engine deliberately**, because they are enforced at
  *execution* time where a caller cannot be trusted to have checked:
  - skill invocability follows the approval state of the workflow behind it (`DefaultSkillStore`);
  - the autonomy gate's conservative default lives in code, so deleting every policy row makes the
    system safer rather than permissive (`PolicyAutonomyGate`).
  Contrast with "Jizza cannot be disabled", which *is* in the platform — it is a rule about what the
  user may configure, checked at configuration time.
- **Gate matching tokens** (`*`, `*:read`, `*:side-effecting`) live on the `AutonomyGate` interface,
  not on the implementation, because the platform must name them to write a rule.
- **Kill-switch row seeding was dropped.** An absent row already means "not paused", so seeding a
  row that says `paused=false` only ever duplicated the default.

## Gotchas that already cost time

- **A service called from inside the engine as well as from a platform controller cannot have its
  policy/audit moved to a use-case without breaking the internal caller.** `ProcedureService.approve`
  and `.reject` are called both from `ProcedureController` (HTTP) and from
  `DefaultNotificationBus#answerReview` (a review notification being answered, entirely
  engine-internal — engine can never call back into platform). `.propose` is called only from
  `ProposeWorkflowToolProvider`, mid an agent's own tool-calling turn. All three, plus `.revise`
  (which shares a notifying/auditing helper with `.propose`), had to stay in the engine for this
  reason; only `.rollback`/`.disable`, which truly have no caller but the controller, moved. Check
  **every** caller of a class before assuming its policy is extractable — `grep -rn` for every method
  you plan to touch, not just the controller you started from.
- **The extractable seam is defaults-on-input and audit-on-output, not "policy" in general.** A1-A3
  worked because the caller could build a complete DTO (with defaults filled in) before calling the
  engine, and read a complete DTO back to audit from, without the use-case ever touching a
  persistence entity. A4 doesn't have that seam: `DefaultNotificationBus`'s methods manipulate
  `NotificationEntity` throughout and `answer()` reaches into `WorkflowEngine`/`ProcedureService`
  synchronously, so there's no DTO-only boundary to lift a use-case through. Before assuming a
  domain will extract like A1-A3, check whether its engine methods take/return complete DTOs or
  reach into entities/other engine services mid-method — if the latter, expect an A4-shaped result
  (thin or nothing), not an A1-shaped one. This will bite A5 (`AuthService`) and probably A7
  (`DataLifecycleService`) too, given how entity-heavy both look already.
- **`find(UUID)` / `list(UUID)` erase identically across ports.** One class cannot implement two
  ports with those signatures — this is why `DefaultSkillStore` was split out of
  `DefaultWorkflowRegistry`. Expect the same when adding `TaskStore`/`ProcedureStore`.
- **JUnit does not inherit `@EnabledIf` from a superclass.** Every concrete `*E2eTest` carries its
  own database guard; a new one without it will fail to start a context instead of skipping.
- **`SchemaConsistencyTest` reads the entity directory off disk** — it has a hardcoded source path
  that must move if `engine-persistence` packages ever move again.
- **The acceptance suite shares one database across test classes.** `SchedulerAndWorkflowE2eTest`
  and `ProceduresAndAutonomyE2eTest` park other tests' due tasks in `@BeforeEach` and clear the
  ShedLock hold before each dispatch; keep that if you touch them.
- **Never leave a stray `mvn spring-boot:run` alive** while running the suite — a background app
  polling the same database silently runs the tests' scheduled tasks and produces baffling failures.

## Running things

```bash
# database (this sandbox; docker-compose.yml is the documented path)
su postgres -c "/usr/lib/postgresql/16/bin/pg_ctl -D /var/lib/postgresql/jizza start"

JIZZA_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/jizza mvn install   # 481 tests
mvn test                                                               # DB-backed tests skip
mvn -pl jizza-app spring-boot:run
```

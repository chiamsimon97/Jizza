# Engine / Platform split — handoff

Status at commit `ca6e039` on `claude/jizza-backend-assembly-bk9fwz`.
**Full clean build green: 512 tests** (`JIZZA_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/jizza mvn install`).
**Task A and Task B are both closed.** Only Task C (docs) remains.

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
9. **Procedure learning** (`0d31e63`) — `ProcedureLearningUseCase`. The entire `ProcedureLearner`
   class (the "write down what you did" prompt and the `@EventListener`/`@Async` handler) moved
   verbatim into `platform-usecases`; it depended only on `Agent` and `ProcedureService` already, so
   this was a pure move, not a split. `ProcedureService` gained one new port method,
   `hasProposalFor(UUID sourceTaskId)`, so the use-case never touches `WorkflowRepository` directly.
10. **Webhook subscription management** (`81860c7`) — `WebhookUseCase`, `WebhookRegistry` port,
    `WebhookSubscriptionDto`. `WebhookService.unsubscribe(UUID)` took a bare subscription id with
    **no ownership check at all** — any authenticated user could disable another user's webhook
    endpoint by guessing/enumerating a UUID. Fixed by adding `find()` to the port and routing
    `unsubscribe` through the use-case, which checks ownership before calling through.
    `receive()`/signature verification/dispatch stayed in the engine untouched.
11. **Generated-tool ownership** (`c25d6fe`) — `GeneratedToolUseCase`, `ToolCatalog` port.
    `ApiDocIngestService.setEnabled(UUID toolId, boolean)` had the same missing-ownership-check bug
    as A6's `unsubscribe` — fixed the same way, reusing `ConnectionUseCase#ownedBy` rather than
    duplicating the check. `fromApiDoc()` (ingestion, size/type limits) stayed in the engine; it
    reaches into `ApiDocParser` and persistence mid-method, so there was no seam to lift it through.
12. **Auth — investigated, left unchanged.** `AuthService` (register/login/verifyMfa/setupMfa/
    stepUp/resetPassword/changePassword) manipulates `UserEntity`/`PasswordResetTokenEntity` directly
    and mixes crypto (hashing, TOTP, encryption) and throttle/lockout state inseparably in one
    atomic operation per call. Its only caller is `AuthController` (no engine-internal-caller issue
    like A2/A3), but there is no DTO-only boundary to lift a use-case through — see the DTO-seam
    gotcha below. No engine-api interface exists for it either, which Task B will need to address.
13. **Data lifecycle — investigated, left unchanged.** `DataLifecycleService.export()` reaches into
    13+ repositories directly, building a raw `ObjectNode` by hand entity-by-entity — the same
    no-seam shape as A5/A9's `DefaultNotificationBus`. `DataController`'s `DELETE EVERYTHING`
    confirmation-phrase check is already correctly located in the platform controller, not the
    engine, so there was nothing to move there either.
14. **Activity view** (`51abde9`) — `ActivityUseCase`. Not one of the original nine domains; found
    only because turning on Task B's enforcer rule in warn-only mode flagged `ActivityService`
    (`platform-api`) querying `AuditLogRepository`/`AuditLogEntity` directly. `AuditLog` gained
    `recent()`/`countForUser()`; the use-case resolves the actor name from the raw rows.
15. **Task B** (`aac6a4d`, `ca6e039`) — the boundary rule is live at `<fail>true</fail>`. See below.

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

### Task A — done

All nine domains resolved. Five extracted real logic into `platform-usecases` (A1, A2/A3 narrowed,
A6, A8, A9); two (A4) were thin ownership-only moves; two (A5, A7) were investigated and left
unchanged because no DTO-only seam exists. A6 and A8 additionally closed genuine authorization bugs
(missing ownership checks on `unsubscribe`/`setEnabled`) found while doing the extraction — see
items 10-11 above.

| # | Domain | Engine class | Outcome |
|---|---|---|---|
| A1 | Workflows | `DefaultWorkflowRegistry` | done -- `WorkflowUseCase` |
| A2 | Procedures | `DefaultProcedureService` | done, narrowed -- `ProcedureReviewUseCase` (rollback/disable audit only) |
| A3 | Tasks | `DefaultSchedulerService` | done, narrowed -- `TaskUseCase` (cancel/mark-repetitive audit only) |
| A4 | Notifications | `DefaultNotificationBus` | done, thin -- `NotificationUseCase` (ownership only) |
| A5 | Auth | `AuthService` | investigated, no seam -- left unchanged, see gotchas |
| A6 | Webhooks | `WebhookService` | done -- `WebhookUseCase` + `WebhookRegistry` port; fixed missing ownership check on `unsubscribe` |
| A7 | Data lifecycle | `DataLifecycleService` | investigated, no seam -- left unchanged, see gotchas |
| A8 | API-doc ingestion | `ApiDocIngestService` | done -- `GeneratedToolUseCase` + `ToolCatalog` port; fixed missing ownership check on `setEnabled` |
| A9 | Procedure learning | `ProcedureLearner` | done -- moved verbatim to `ProcedureLearningUseCase` |

### Task B — done

`jizza-platform/pom.xml` now carries a `maven-enforcer-plugin` `bannedDependencies` rule with
`<fail>true</fail>`. It bans compile-scope `com.jizza:engine-*` dependencies, with
`com.jizza:engine-api`/`engine-security`/`engine-infra`/`engine-connectors` whitelisted, and
`searchTransitive=false` so it only judges what `platform-api` itself declares, not what those
whitelisted modules pull in for their own compilation.

Getting there in warn-only mode first (as this section originally suggested) paid off immediately:
the warning surfaced `ActivityService` (in `platform-api`) querying `AuditLogRepository`/
`AuditLogEntity` directly — a real boundary violation nobody had flagged. Fixed by adding
`recent()`/`countForUser()` to the `AuditLog` port and moving the actor-name-resolution logic (a
presentation decision: "You"/"Jizza"/an agent's name) into a new `ActivityUseCase`.

The two known leaks this section used to list are also closed:

- `AuthController` now depends on a new `com.jizza.engine.api.security.AuthService` interface.
  The concrete class was renamed `DefaultAuthService`; its nested `LoginResult`/`MfaSetup` records
  moved to `engine-api/dto` since an interface can't carry another class's nested types. No
  behavior changed — this only closes the compile-time leak, it does not reopen the A5 question of
  whether there's a policy seam (there isn't).
- `DataController` now depends on `com.jizza.engine.api.lifecycle.DataLifecycleService`; the
  concrete class was renamed `DefaultDataLifecycleService`, same shape as above.

`CurrentUser`, `SessionCookies`, `StepUpInterceptor` (`engine-security`) and `PlatformProperties`,
`ConnectorProperties` (`engine-infra`/`engine-connectors`) turned out to be the only other classes
`platform-api` reaches into directly. Per the recommendation this section used to make, they're
treated as legitimate cross-cutting infrastructure: the three modules they live in are whitelisted
outright rather than forced through a port, since `bannedDependencies` can only exempt at the
module level, not the package level, and adding a port for "who is the current user" would add
indirection without adding a policy seam.

Every other `engine-*` dependency `platform-api/pom.xml` declared (persistence, agents, knowledge,
orchestrator, connectors-tools, scheduler, workflow, procedures, notifications, autonomy) is
`<scope>runtime</scope>` now: `platform-api`'s own code never imported anything from them directly
(confirmed with `grep -rhoE "^import com\.jizza\.engine\." .` across its `src/main/java`), but
Spring still needs the concrete beans on the classpath to wire the app, since `jizza-app` depends
on nothing but `platform-api`. Runtime scope is on Maven's test classpath too, so this didn't touch
`jizza-app`'s e2e tests.

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
  (thin or nothing), not an A1-shaped one. This bit A5 (`AuthService`) and A7
  (`DataLifecycleService`) exactly as predicted — both confirmed no-seam and left unchanged.
- **A third, distinct class of real extraction: missing ownership checks and raw-entity leakage.**
  Some engine methods take a bare resource id with **no ownership check at all** — a genuine
  authorization bug, since any authenticated user can act on any other user's row by
  guessing/enumerating the id — and/or return raw persistence entities straight to the platform
  layer instead of DTOs. Neither A6's `WebhookService.unsubscribe(UUID)` nor A8's
  `ApiDocIngestService.setEnabled(UUID, boolean)` had *any* policy to strip (no audit call, no
  defaulting), so by the defaults/audit test above they'd look like nothing to extract — but adding
  a `find()`-style read method plus an `engine-api` port, then routing the ownership check through a
  platform use-case (reusing a sibling use-case's `ownedBy` where one exists, e.g.
  `GeneratedToolUseCase` reusing `ConnectionUseCase#ownedBy`), closed a real security hole. Don't
  stop at "is there a DTO seam" — also check "does this id-taking method verify the caller owns the
  id" and "does this method return an entity instead of a DTO".
- **`grep -rhoE "^import ..."` misses fully-qualified references.** The first pass at auditing
  `platform-api`'s actual `engine-*` imports (for Task B) only matched `^import` lines and missed
  `WebhookController`'s field `private final com.jizza.engine.connectors.config.ConnectorProperties`
  -- a fully-qualified type with no import statement at all. Drop the `^import` anchor (just
  `grep -rhoE "com\.jizza\.engine\.(...)\.[A-Za-z0-9_.]+"`) to catch both forms before trusting the
  result as complete.
- **`bannedDependencies` bans artifacts, not packages, and `searchTransitive` defaults to true.**
  You cannot tell Maven "ban most of `engine-security` but allow `CurrentUser`" -- the rule only
  sees module coordinates, so a class-level exception means whitelisting the whole module. And
  without `searchTransitive=false`, the rule walks the *resolved* dependency tree: whitelisting
  `engine-security` still fails on `engine-persistence` showing up underneath it (as a transitive
  compile dependency `engine-security` needs for itself), even though `platform-api` never declares
  that edge directly. Set `searchTransitive=false` so the rule judges only what the current
  module's own `pom.xml` declares.
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

JIZZA_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/jizza mvn install   # 512 tests
mvn test                                                               # DB-backed tests skip
mvn -pl jizza-app spring-boot:run
```

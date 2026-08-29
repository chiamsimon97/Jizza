# Jizza — Project Charter & Conventions (Phase 0)

*The constant reference for the whole build. Every later session loads this + the Architecture Contract
(Phase 1) + the specific module spec. The **build prompt** remains the requirements source of truth;
this Charter records **decisions and conventions** on top of it. If a decision here and the build
prompt ever conflict, fix the build prompt first, then update this Charter.*

---

## 1. How to use this document
- **Phase 0 (this doc):** lock decisions + conventions.
- **Phase 1:** Architecture Contract — schema/DDL, shared types, and the internal interfaces every
  module implements.
- **Phase 2:** one module per session → each produces a *Module Package* (sub-spec + Claude Code module
  prompt + stable artifacts + integration note), conforming to this Charter and the Contract.
- **Phase 3:** design track via Claude Design (feeds the frontend module sessions).
- **Phase 4:** the final assembly prompt (build runbook) for Claude Code.
- **Phase 5–6:** build/integrate in Claude Code, then harden and verify.

Keep a living **Integration Manifest** (module → interfaces → status) and update it at the end of every
session.

---

## 2. Locked decisions

### Pivotal (your calls)
| # | Decision | Choice | Note |
|---|---|---|---|
| 1 | Backend stack | **Java 21 (LTS) + Spring Boot 3.x** | Your home toolchain; single deployable app. |
| 2 | Durable workflow engine | **Build — custom Postgres-backed saga / state machine** | No Temporal dependency; checkpoints + resume live in Postgres. |
| 3 | Runtime target | **Local-first** | One machine, Docker Compose for infra; cloud-swappable via interfaces. |

### Defaulted (my recommendations — override any)
| Area | Default | Rationale |
|---|---|---|
| Login | Email + password (**Argon2id**) + **TOTP** MFA; passkeys/WebAuthn as fast-follow; Google sign-in later | Strong + simple for single-user local-first. |
| Sessions | Server-side session, httpOnly+Secure+SameSite cookie; idle + absolute expiry; revoke/"log out everywhere" | Simplest correct model for a browser client. |
| Transform-node sandbox | **Apache Commons JEXL3** (no arbitrary code) | You know JEXL deeply; safe, embeddable, no fs/network. |
| "Repetitive" detection | User explicitly marks a routine; agent may **suggest** | Predictable; keeps the human in charge of what gets learned. |
| Binding strictness | Bound procedures run **deterministically**; any deviation raises an **intervention** | Matches "strictly follow" without brittle improvisation. |
| Autonomy defaults | Read/inform = automatic; anything that **sends / pays / creates / deletes / calls an external side-effect** = **ask**. Finance: read-only unattended, money movement always ask. Health/Professional: read + draft unattended, external sends ask. Kill switch present, system active by default. | Conservative, safe out of the box. |
| Data residency | Local-first = data on your machine. When hosted → **IN region** + IN backups | You're in India. |
| Escalation channel | **Email (SMTP)** for unseen blocking items in v1; Telegram/push later | Zero extra infra locally. |
| API-doc ingestion | **OpenAPI/Swagger** deterministic parse + **LLM-assisted** free-form fallback | Covers both cases. |
| Launch auth schemes | **OAuth2 + API Key** in v1 (Basic/JWT later) | Most services; keeps scope tight. |
| Launch specialists | **Health, Finance, Professional** + placeholder names (e.g. Vita / Kai / Cato) you can rename | Matches the brief. |
| LLM usage | Anthropic API; **model tiering** (cheap model for routing, stronger for hard tasks) via **configurable model strings** | Cost control; model names change over time. |

---

## 3. Technology stack (concrete)
- **Language/runtime:** Java 21 (LTS). **Build:** Maven multi-module. **IDE:** IntelliJ IDEA.
- **Framework:** Spring Boot 3.x — Spring MVC for REST; **Spring Security** for auth/sessions/MFA;
  **WebSocket (STOMP)** or SSE for real-time streaming of Jizza's replies and notifications.
- **Persistence:** PostgreSQL + **Spring Data JPA / Hibernate**; **Flyway** migrations; **pgvector**
  for knowledge embeddings (native/registered type). Snake_case tables, `com.jizza` entities.
- **Durable execution:** custom saga engine — a `workflow_runs` state machine with per-node
  checkpoints, resume-from-last-completed-node, retries/backoff, dead-letter, and idempotency keys, all
  in Postgres.
- **Background/scheduler:** Postgres-backed job queue + Spring scheduling with **ShedLock** for the
  distributed lock (no double-fire). Cron/RRULE with explicit IST/DST handling. (Redis optional later;
  not required local-first.)
- **Caching:** **Caffeine** (in-process) locally; Redis a later swap behind a cache interface.
- **LLM client:** Anthropic API via Spring `RestClient`/`WebClient` (or the Anthropic Java SDK), behind
  an `LlmClient` interface. Model strings configurable.
- **Sandbox:** Apache Commons **JEXL3** for the Transform node, no fs/network exposure.
- **Secrets/encryption:** **envelope encryption** — AES-GCM data keys wrapped by a master key from a
  `KeyProvider` interface (local: OS keystore / encrypted file; cloud KMS later). Field-encrypt all
  connection secrets/tokens.
- **Object storage:** local filesystem behind a `StorageProvider` interface (S3/GCS later).
- **Error model:** **RFC 9457 problem+json** across the API.
- **Mapping:** **MapStruct** for entity↔DTO.
- **Observability:** Micrometer metrics + structured JSON logging (Logback); optional OpenTelemetry
  tracing across Jizza→specialist→tool→connector; Spring Actuator health/readiness.
- **Frontend:** **React + Vite + React Flow** (workflow canvas), talking to Spring over REST +
  WebSocket/SSE. Claude Design's output plugs in here.

---

## 4. Repository layout (modular monolith)
One deployable Spring Boot app, internally split into Maven modules that map to the build-prompt groups.
Local-first keeps it a single process (API + worker) with a `worker` profile flag.

```
jizza/
  pom.xml                      (parent)
  jizza-common/                shared DTOs, interfaces/contracts, event types, RFC-9457 error model
  jizza-persistence/           JPA entities, repositories, Flyway migrations, pgvector
  jizza-security/              Spring Security, auth, sessions, MFA, KeyProvider/encryption, audit log
  jizza-agents/                Base Agent runtime, LlmClient, loop-safety, Capability Registry
  jizza-orchestrator/          Jizza + intent routing + aggregation
  jizza-knowledge/             specialist knowledge stores + isolation enforcement
  jizza-connectors/            OAuth2 connections, API-doc→tool generation, tool executor, SSRF guard
  jizza-scheduler/             task engine, ShedLock, cron/RRULE, catch-up policy
  jizza-workflow/              builder model, durable saga engine, interventions, JEXL sandbox
  jizza-procedures/            learned procedures, versioning, binding
  jizza-notifications/         notification bus, review/intervention, expiry + escalation
  jizza-autonomy/              global kill switch + per-agent/per-tool autonomy policy
  jizza-platform/              job queue, realtime (WS/SSE), inbound webhooks, observability, storage, cost
  jizza-web/                   REST controllers, WebSocket endpoints, the HTTP API surface
  jizza-app/                   Spring Boot main; wires modules; runs API + worker
  frontend/                    React + Vite + React Flow client
  docker-compose.yml           Postgres (+ pgvector), mail catcher, etc.
```

Package root: `com.jizza.<module>`. A module exposes Spring beans/interfaces from `jizza-common`; it
never reaches into another module's internals — only its published interface.

---

## 5. Module Package convention (Phase 2 output)
Each module session produces:
1. **Module sub-spec** — the slice of the build prompt this module owns, with its acceptance checks.
2. **Claude Code module prompt** — a precise, buildable instruction referencing this Charter + the
   Architecture Contract (the thing Claude Code actually executes).
3. **Stable artifacts** — anything deterministic and reusable now: DDL/migrations, shared interfaces,
   agent system prompts, JEXL policies, unit-test skeletons.
4. **Integration note** — what it consumes, what it exposes, and its entry in the Integration Manifest.

Every module conforms to the Contract; none invents its own schema, DTOs, error format, or event types.

---

## 6. Conventions & cross-cutting standards
- **API:** `/api/**`, REST + JSON, RFC 9457 errors, Bean Validation at every boundary.
- **DB:** snake_case tables/columns; Flyway `V<n>__desc.sql`; one owning module per table group.
- **Config:** `application.yml` + Spring profiles (`local`, `worker`, later `cloud`); no secrets in code;
  a single dev run brings up API + worker.
- **Security invariants (enforced in code, not just prompts):** per-agent knowledge isolation; per-agent
  tool allow-lists; autonomy-policy check before every tool call and autonomous run; kill-switch check
  before any autonomous action; **secrets/tokens never enter LLM context or logs** (model sees tool
  schemas only); all consequential actions written to the append-only **audit log**.
- **Reliability invariants:** every workflow run is a checkpointed saga (resume-from-last-node);
  side-effecting connector calls are idempotent (idempotency keys); retries→backoff→dead-letter;
  transactional status transitions + outbox for notifications/side effects.
- **LLM:** capped tool-call iterations per turn, schema-validated tool calls, per-run token/cost budget.

---

## 7. Running it locally (local-first)
- `docker compose up` → PostgreSQL (with pgvector) + a local mail catcher for escalation emails.
- `mvn spring-boot:run` (app, running API + worker) · `npm run dev` in `frontend/`.
- **OAuth callback gotcha:** external services must reach the redirect URI, so even local-first needs a
  **public tunnel** (ngrok / cloudflared) *or* providers that permit `http://localhost` callbacks in dev.
  Make the callback **base URL a config value** so it swaps cleanly when you host later.
- Keep the master encryption key out of the repo (OS keystore / encrypted local file).

---

## 8. Change control
- **Build prompt** = requirements source of truth. **This Charter** = decisions + conventions.
  **Architecture Contract** (Phase 1) = the binding interfaces.
- Any interface/requirement change: edit the build prompt first, then propagate to the Charter,
  Contract, and affected Module Packages. Never let a module silently diverge.

---

## 9. Next step — Phase 1 (Architecture Contract)
Produce: the full **schema/DDL** (all build-prompt R2 tables), the **shared types/DTOs**, and the
**internal interface contracts** — Base Agent, tool-calling, Capability Registry, notification-bus
events, the workflow-run saga state machine, the autonomy-policy evaluation interface, and the HTTP API
surface. Everything downstream imports these.

# Jizza — Project Overview

> High-level orientation for a developer new to this repository. Based on direct inspection of the
> codebase (Maven module structure, `pom.xml` dependencies, controllers, config files) as it stands
> on branch `claude/jizza-backend-assembly-bk9fwz`.

## 1. Application Overview

**What it does:** Jizza is a personal-assistant backend. A single primary agent ("Jizza") is the
one point of contact for the user; behind it sits a team of named specialist agents (e.g. Vita for
health, Kai for finance, Cato for professional matters per the docs), each with its own isolated
knowledge base and tools. The system can:

- Connect to external services via OAuth2 and turn their API docs into callable tools.
- Run durable, resumable, multi-step workflows (a Postgres-backed saga engine).
- Learn a repeated task into a "procedure" that it proposes for user review, then executes exactly
  as approved.
- Gate all autonomous/agent actions behind a configurable autonomy policy and a kill switch.
- Keep an append-only, hash-chained audit log of every consequential action.
- Deliver notifications/approvals over WebSocket (STOMP) and email, and accept inbound webhooks.

**Main purpose/use case:** a secure, local-first, single-user AI assistant platform that can act on
a person's behalf across connected services, with strong auditability and user control over what it
is allowed to do autonomously.

**Target users:** Per `docs/jizza-project-charter.md` and the README, this is explicitly a
**single-user, local-first** system — one person runs their own instance rather than a
multi-tenant SaaS product. No broader user-role model (e.g. admin/end-user separation) was found in
the codebase.

## 2. Technology Stack

| Layer | Finding |
|---|---|
| **Frontend** | **Not present in this repository.** The README states the frontend is "a separate project" ("React + Vite + React Flow client"), and no frontend source, `package.json`, or JS/TS UI code was found anywhere in this repo. This repository is backend-only. |
| **Backend** | **Java 21** + **Spring Boot 3.5.16** (`spring-boot-starter-parent`), built as a Maven multi-module **modular monolith**. |
| **Database** | **PostgreSQL** (with the **pgvector** extension for embeddings), accessed via **Spring Data JPA / Hibernate**, with schema managed by **Flyway** migrations. |
| **Important libraries** | `spring-boot-starter-web` (REST), `spring-boot-starter-websocket` (STOMP realtime), `spring-boot-starter-security` (auth/session), `spring-boot-starter-validation`, `spring-boot-starter-mail`, `spring-boot-starter-cache` (Caffeine), Bouncy Castle (`bcprov-jdk18on`, crypto), Apache Commons JEXL3 (sandboxed workflow "Transform" node), ShedLock (`shedlock-spring`, distributed scheduling lock), Swagger/OpenAPI parser (`swagger-parser`, for turning connected APIs into tools), MapStruct (DTO mapping), Micrometer (metrics). LLM calls to Anthropic's Claude API are made via plain HTTP (Spring's web client stack), not an official Anthropic SDK. |
| **Build/package tools** | **Maven** (multi-module reactor, parent `pom.xml` at repo root), **Docker Compose** (`docker-compose.yml`) for local Postgres + MailHog (SMTP test inbox), `spring-boot-maven-plugin` to build the runnable app. |

## 3. Project Structure

There is no traditional "Frontend/Backend" folder split — the whole repository *is* the backend,
organized into two Maven aggregates plus the deployable app:

```text
jizza-engine/              Generic mechanism — no HTTP surface, no business decisions
 ├── engine-api             Shared contract: port interfaces, DTOs, enums, error model
 ├── engine-persistence     JPA entities, repositories, Flyway migrations, pgvector search
 ├── engine-security        Auth mechanism, sessions, MFA/TOTP, encryption, audit log
 ├── engine-agents          Base agent loop, Anthropic LLM client, capability registry
 ├── engine-knowledge       Per-agent knowledge store (namespace-isolated)
 ├── engine-orchestrator    "Jizza" orchestrator agent, delegation to specialists
 ├── engine-connectors      OAuth2 handshake, connection config, SSRF/egress guard
 ├── engine-connectors-tools API-doc → tool generation, tool execution, idempotency
 ├── engine-scheduler       One-time/recurring task dispatch (ShedLock-guarded)
 ├── engine-workflow        Postgres saga workflow engine, JEXL sandbox
 ├── engine-procedures      Procedure binding/deviation-detection mechanism
 ├── engine-notifications   Notification bus, escalation sweeper
 ├── engine-autonomy        Autonomy gate evaluation, kill switch
 └── engine-infra           Job queue, realtime (STOMP), webhooks, storage, mail, outbox

jizza-platform/            Business logic and the HTTP entry point
 ├── platform-usecases      Validation, defaults, ownership checks, audit calls (business rules)
 └── platform-api           REST controllers, WebSocket endpoint, global error handling

jizza-app/                 The runnable Spring Boot application (assembly + config + E2E tests)

docs/                       Design/spec documents (charter, architecture contract, blueprint,
                             integration manifest, refactor notes)
```

The guiding rule (see `docs/REFACTOR-HANDOFF.md`): **runtime mechanism lives in `jizza-engine`;
the lifecycle/business decisions for anything the user configures live in `jizza-platform`.**
`platform-api` may only depend on `engine-api` plus a couple of named cross-cutting infrastructure
classes (session handling, config) — this is enforced by a Maven build rule, not just convention.

## 4. Modules

| Module | Description |
|---|---|
| Agents & Orchestration | The "Jizza" primary agent, specialist sub-agents, delegation between them |
| LLM Integration | Talks to Anthropic's Claude API; tiered models, cost/token accounting |
| Knowledge | Per-agent, isolated knowledge storage with vector search |
| Connectors | OAuth2 connections to external services, egress/SSRF protection |
| Tools | Turns a connected service's API docs into callable tools; executes them |
| Scheduler | Durable one-time and recurring (cron/RRULE) task execution |
| Workflows | Multi-step, resumable, checkpointed automation ("sagas") |
| Procedures | Learns a repeated task, submits it for review, then runs it exactly as approved |
| Notifications | Raises, delivers, escalates and expires approvals/alerts |
| Autonomy | Per-agent/per-tool permission rules and a global kill switch |
| Auth & Security | Registration, login, MFA (TOTP), sessions, step-up re-authentication, encryption |
| Webhooks | Inbound webhook subscriptions and signature-verified delivery, which can fire a workflow |
| Data Lifecycle | Export a user's data, or permanently delete everything ("forget me") |
| Activity | Read-only view over the audit log, with hash-chain integrity verification |

## 5. Features Implemented

| Module | Features |
|---|---|
| Auth & Security | Register, login, TOTP MFA setup/verify/confirm, step-up re-auth, logout / logout-everywhere, session listing/revocation, password reset (email-based), password change, lockout after repeated failures |
| Agents & Orchestration | Chat with the primary agent, agent listing, rename/enable-disable an agent, delegation to specialists |
| Connectors | Create/list connections, OAuth authorize/callback, test connection, revoke connection |
| Tools | Attach an API doc to a connection to auto-generate tools, list a connection's tools, enable/disable a generated tool |
| Workflows | Create/save a workflow, list workflows and versions, run a workflow, resume/cancel a run, save a workflow as a reusable "skill" |
| Scheduler / Tasks | Create a one-time or recurring task, list/cancel tasks, mark a task repetitive, unbind a task from a procedure |
| Procedures | Propose a procedure from a repetitive task, review history/diff, approve/reject/revise, roll back to a prior version, disable |
| Notifications | List notifications (all / unread), get one, mark read, answer (approve/deny/instruction/feedback) |
| Autonomy | View/update autonomy policies, trigger the kill switch |
| Webhooks | Subscribe/unsubscribe an inbound webhook endpoint, receive and verify (HMAC or token) an inbound delivery, fire a workflow from it |
| Data Lifecycle | Export all of a user's data as JSON, permanently delete everything (with typed confirmation) |
| Activity | List recent audit-log entries (filterable by action), verify the audit hash chain is unbroken |

## 6. Frontend ↔ Backend

- **No frontend code exists in this repository**, so there is nothing to trace on the client side
  here — this section describes the backend's side of that contract only.
- The backend exposes a **REST API** (JSON over HTTP) plus one **WebSocket (STOMP)** endpoint at
  `/ws` for realtime push (chat streaming, live notifications).
- **API surface:** all REST endpoints are implemented as Spring `@RestController` classes under
  `jizza-platform/platform-api/src/main/java/com/jizza/platform/api/rest/` (13 controllers — Auth,
  Agent, Autonomy, Chat, Connection, Data, Notification, OAuthCallback, Procedure, Task, Webhook,
  Workflow, Activity).
- **How modules connect internally:** a controller in `platform-api` calls into a `*UseCase` class
  in `platform-usecases` (business logic), which in turn calls an `engine-api` port interface
  implemented by a class in one of the `jizza-engine/*` modules (the actual mechanism). Controllers
  do not talk to engine internals directly except for a small set of named cross-cutting
  infrastructure (current-user/session lookup, step-up interceptor, config properties).
- Authentication for API calls is a server-side session cookie; error responses follow the RFC 9457
  `application/problem+json` format (per the README — not independently re-verified here, since
  detailed error-handling behavior is outside this overview's scope).

## 7. Important Files

| File | Why it matters |
|---|---|
| `pom.xml` (repo root) | The Maven parent/reactor: lists the two aggregates (`jizza-engine`, `jizza-platform`) and `jizza-app`, and centralizes shared dependency versions. |
| `jizza-app/src/main/java/com/jizza/app/JizzaApplication.java` | The Spring Boot application entry point — where the whole app actually starts. |
| `jizza-app/src/main/resources/application.yml` | Central runtime configuration: datasource, server, management/actuator, logging, and Jizza-specific settings. |
| `docker-compose.yml` | Local infrastructure: PostgreSQL (with pgvector) and MailHog (test SMTP/inbox) for running the app locally. |
| `jizza-engine/engine-api/` (module) | The shared contract every other module and `platform-api` depends on — read this first to understand what capabilities exist. |
| `jizza-platform/platform-api/src/main/java/com/jizza/platform/api/rest/` | Where every HTTP endpoint is defined — the fastest way to see "what can the client actually call". |
| `jizza-platform/platform-usecases/` (module) | Where the actual business rules (ownership, defaults, audit) live, one `*UseCase` class per feature area. |
| `README.md` | Setup/run instructions, environment variables, and a summary of the HTTP surface. |
| `docs/INTEGRATION-MANIFEST.md` | Living record of every module, what it exposes/consumes, and where the implementation deliberately diverges from the original design spec. |
| `docs/jizza-architecture-contract.md` / `docs/jizza-backend-blueprint.md` / `docs/jizza-project-charter.md` | The original design documents (schema, module contracts, decisions/conventions) this codebase was built from. |

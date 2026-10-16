# Jizza

A secure, local-first, single-user personal-assistant platform. **Jizza** is the only agent you
talk to; behind it sits a team of named specialists — Vita (health), Kai (finance), Cato
(professional) — each with its own private knowledge and tools. It connects to external services
over OAuth2, turns their API docs into callable tools, runs durable resumable workflows, learns a
repeated job into a procedure it submits for your review and then follows exactly, and puts all of
it behind an autonomy policy, a kill switch and an append-only audit trail.

This repository is the **backend**: a Java 21 / Spring Boot 3 modular monolith. The React + Vite +
React Flow client is a separate project and consumes the HTTP + WebSocket surface described below.

---

## Running it

### 1. Infrastructure

```bash
docker compose up -d
```

That brings up PostgreSQL with pgvector on `5432` and MailHog on `1025` (SMTP) and
[`8025`](http://localhost:8025) (web UI), where escalation emails land instead of a real inbox.

### 2. Configuration

Two things need setting before first run:

```bash
export ANTHROPIC_API_KEY=sk-ant-...     # without it the app boots but agents cannot reply
export JIZZA_CALLBACK_BASE_URL=https://your-tunnel.example   # see "OAuth callbacks" below
```

Optional: `JIZZA_EMBEDDING_PROVIDER=openai-compatible` with a local Ollama gives memory and
knowledge real semantic search without data leaving the machine (see [docs/MEMORY.md](docs/MEMORY.md)).

Everything else has a working local default. The full list is in
[`jizza-app/src/main/resources/application.yml`](jizza-app/src/main/resources/application.yml);
each value is an environment variable, so nothing sensitive belongs in the file.

**The master encryption key never lives in this repository.** On first run Jizza generates one at
`~/.jizza/master.key` with `0600` permissions and logs a warning. Back it up: without it, every
stored credential becomes unreadable. For anything beyond a laptop, point
`JIZZA_KEYSTORE_PATH` / `JIZZA_KEYSTORE_PASSWORD` at a PKCS12 keystore instead. The key file
supports rotation — it holds `<keyId>=<base64>` lines plus `active=<keyId>`, and ciphertext records
which key wrapped it, so adding a new key does not invalidate old rows.

### 3. Run

```bash
mvn -pl jizza-app -am spring-boot:run
```

One process serves the API **and** runs the worker: scheduled tasks fire, workflows resume and
notifications escalate with no browser open. Flyway migrates the schema on startup.

To add a worker-only process later, run the same artefact with `--spring.profiles.active=worker`;
it drops the HTTP surface and keeps everything else. Two workers are safe: the scheduler takes a
ShedLock lock and claims each task row, and the job queue leases with `for update skip locked`.

### 4. Point the frontend at it

The client talks to `http://localhost:8080` over REST and to `ws://localhost:8080/ws` over
STOMP. Authentication is the `JIZZA_SESSION` cookie, so the client must send credentials:

```js
fetch('http://localhost:8080/api/chat', { credentials: 'include', /* … */ })
```

Add the client's origin to `JIZZA_CORS_ORIGINS` (default `http://localhost:5173`, Vite's default).
CSRF protection is on: read the `XSRF-TOKEN` cookie and send it back as `X-XSRF-TOKEN` on every
mutating request.

Realtime destinations are namespaced per user:

* `/topic/{userId}/chat` — streamed assistant replies
* `/topic/{userId}/notifications` — approvals, reviews and interventions the moment they occur

---

## OAuth callbacks in local development

This is the one part of local-first that genuinely needs the internet. An OAuth provider redirects
**the user's browser** to your callback URL, so that URL has to be reachable from outside your
machine. Two options:

1. **A tunnel.** `ngrok http 8080` or `cloudflared tunnel --url http://localhost:8080`, then set
   `JIZZA_CALLBACK_BASE_URL` to the public URL it prints and register the same URL with the
   provider.
2. **A provider that permits `http://localhost`.** Google and GitHub both do for development
   clients; leave `JIZZA_CALLBACK_BASE_URL` at its default.

The callback base URL is configuration precisely so this swaps cleanly when you host it later.

---

## What is in here

Two Maven aggregates plus the deployable that assembles them:

* **`jizza-engine`** (`com.jizza.engine.*`) — generic mechanism. No HTTP surface, no knowledge of
  any particular user journey.
* **`jizza-platform`** (`com.jizza.platform.*`) — the entry point. Every client call arrives here,
  and this is where the product's decisions live (validation, defaults, ownership, audit), reaching
  the engine only through `engine-api`.

| Module | What it owns |
|---|---|
| `jizza-engine/engine-api` | Every shared contract: port interfaces, enums, DTOs, the tool contract, RFC 9457 errors, the workflow graph model |
| `jizza-engine/engine-persistence` | JPA entities, repositories, Flyway migrations, pgvector search |
| `jizza-engine/engine-security` | Argon2id + TOTP, sessions, step-up, lockout, envelope encryption, hash-chained audit log |
| `jizza-engine/engine-agents` | The Base Agent loop, the Anthropic client, the Capability Registry choke point |
| `jizza-engine/engine-knowledge` | Per-agent knowledge with isolation enforced in code; the shared `EmbeddingProvider` (local or any OpenAI-compatible endpoint, e.g. Ollama) |
| `jizza-engine/engine-memory` | Hybrid user memory: facts grouped around the people, accounts and plans in the user's life, scoped by agent domain, retrieved by structure + pgvector + keywords and handed to every agent turn ([docs/MEMORY.md](docs/MEMORY.md)) |
| `jizza-engine/engine-orchestrator` | Jizza, delegation, agent seeding and renaming |
| `jizza-engine/engine-connectors` | OAuth2 handshake, connection config, the SSRF/egress guard |
| `jizza-engine/engine-connectors-tools` | API doc → tools, and the auth-injecting idempotent executor |
| `jizza-engine/engine-scheduler` | Durable one-time and recurring tasks, DST-safe, no double-fire |
| `jizza-engine/engine-workflow` | The Postgres saga engine, the JEXL sandbox, interventions |
| `jizza-engine/engine-procedures` | Learned-procedure mechanism: binding enforcement, deviation recording |
| `jizza-engine/engine-notifications` | The bus, expiry and escalation |
| `jizza-engine/engine-autonomy` | Kill switch and autonomy gate evaluation |
| `jizza-engine/engine-infra` | Job queue, realtime, webhooks, storage, observability, export/delete mechanism |
| `jizza-platform/platform-usecases` | The business logic: ownership checks, defaults, audit calls, and the "learn a procedure" / activity-view decisions, all behind `engine-api` |
| `jizza-platform/platform-api` | The HTTP + WebSocket surface, controllers, the activity view's ownership check |
| `jizza-app` | Assembly, configuration, profiles |

`docs/INTEGRATION-MANIFEST.md` records what each module exposes and consumes, and every place the
implementation had to resolve a gap in the Architecture Contract.

## The HTTP surface

```
POST   /api/auth/register  login  logout  logout-everywhere  step-up  password
POST   /api/auth/mfa/setup  mfa/verify  mfa/confirm  mfa/recovery-codes  mfa/disable
POST   /api/auth/recover  recover/complete
GET    /api/auth/me                  (two-step status, recovery codes left)
GET    /api/auth/sessions            DELETE /api/auth/sessions/{id}
GET    /api/auth/invitations/{token} (public: the email and organization a sign-up link is for)

GET    /api/me/context               POST   /api/me/active-workspace  (switch account; new cookie)
GET    /api/me/invitations           POST   /api/me/invitations/{id}/accept  decline
POST   /api/workspaces               DELETE /api/workspaces/current/membership  (create / leave)
GET    /api/workspaces/current/members      PATCH/DELETE …/members/{memberId}
GET    /api/workspaces/current/invitations  POST …/invitations   POST …/invitations/{id}/revoke
GET    /api/permissions              (labels from security/permissions.json)

GET    /api/agents                   PATCH  /api/agents/{id}          (rename, enable/disable)
GET    /api/agents/{id}/tools        POST   /api/agents/{id}/capabilities
POST   /api/chat                     WS     /ws

GET    /api/services                 POST   /api/services           (Connector services)
GET    /api/services/{id}            PATCH  DELETE  POST publish  unpublish
POST   /api/services/{id}/apis|resources|actions|triggers|variables    PUT/DELETE …/{childId}
PUT    /api/services/{id}/actions/{actionId}/agents   POST …/actions/{actionId}/test
POST   /api/services/{id}/triggers/{triggerId}/test   GET  /api/services/{id}/history
GET    /api/agents/{id}/memory       POST   /api/agents/{id}/memory/files
GET    /api/agents/{id}/memory/files/{fileId}/content  DELETE …/files/{fileId}  …/notes/{noteId}

GET    /api/memory                   POST   /api/memory               (user memory, docs/MEMORY.md)
GET    /api/memory/{id}              PATCH  DELETE  POST confirm  retire  restore
POST   /api/memory/{id}/share  move  restrict  unrestrict   DELETE …/share/{agentId}
GET    /api/memory/cosmos            GET …/cosmos/bodies/{bodyId}/items  …/cosmos/search
GET    /api/memory/cosmos/items/{kind}/{id}/passport  …/reveal      (Memory Cosmos, docs/MEMORY.md)
POST   /api/memory/cosmos/reset {bodyId}   (forget one planet's or moon's part of the sky; the sun: all)
PATCH  /api/agents/{id}/memory/notes/{noteId}   POST …/move  restrict  unrestrict  promote
GET    /api/memory/subjects          POST   PATCH/DELETE …/{id}  POST …/{id}/relations
GET    /api/memory/settings          PUT    /api/memory/settings      POST /api/memory/preview

GET    /api/connections/{id}         PATCH  /api/connections/{id}     (a service's authentication)
POST   /api/connections/{id}/authorize  test  revoke
POST   /api/connections/{id}/apidoc  GET    /api/connections/{id}/tools
GET    /api/oauth/callback

GET    /api/workflows                POST   /api/workflows
POST   /api/workflows/{id}/save-skill   run
GET    /api/workflows/{id}/versions  GET    /api/skills
GET    /api/runs/{id}                POST   /api/runs/{id}/resume  cancel

GET    /api/procedures/{id}/history  diff
POST   /api/procedures/{id}/approve  reject  revise  rollback  disable

GET    /api/tasks                    POST   /api/tasks
DELETE /api/tasks/{id}               POST   /api/tasks/{id}/repetitive

GET    /api/notifications            POST   /api/notifications/{id}/answer  read
GET    /api/autonomy/policies        PUT    /api/autonomy/policies
POST   /api/autonomy/kill-switch
GET    /api/activity                 GET    /api/activity/verify
POST   /api/webhooks/{token}         GET/POST /api/webhook-endpoints
GET    /api/data/export              DELETE /api/data?confirm=DELETE%20EVERYTHING
GET    /actuator/health  info  metrics  prometheus
```

Everything except `/api/auth/{register,login,mfa/verify,recover,invitations/*}`, `/api/oauth/callback`,
`/api/webhooks/**` and the health endpoints requires a session. High-risk routes — connecting a
service, changing autonomy or security settings, approving a procedure, exporting or deleting data
— additionally require a **step-up**: call `POST /api/auth/step-up` with your password (and TOTP
code if MFA is on) within the last five minutes, or they answer `403` with
`type: …/step-up-required`.

Two-step sign-in is **required** by default (`JIZZA_MFA_REQUIRED`, default `true`). A session
signed in with only a password can reach `/api/auth/**` and nothing else (`403` with
`type: …/mfa-enrollment-required`) until it sets up an authenticator; the client routes to
`/setup-2fa` for that. `/api/auth/mfa/verify` accepts either the 6-digit code or one of the
one-time recovery codes issued at setup.

Registration closes once the owner's account exists (`JIZZA_ALLOW_ADDITIONAL_USERS`, default
`false`).

### Accounts, members and roles

Every person has an **Individual** account. Signing up with `accountType: ORGANIZATION` and an
`organizationName` also creates an **Organization** account they own, and anyone can create more
later. The session acts in one account at a time; `POST /api/me/active-workspace` switches by
issuing a new session cookie and revoking the old one. Connectors belong to the account they were
created in; agents, chat, memory and the rest stay personal.

An organization's owner or admins invite people from the Members tab with a role (Admin, Developer,
User). Someone who already has an account sees the request under Pending requests when they sign
in; someone who does not is emailed a sign-up link, which also lets them register on an instance
whose registration is otherwise closed. Requests expire after `jizza.security.invitations.expiry`
(7 days).

Who may do what is defined in
[`security/permissions.json`](jizza-engine/engine-security/src/main/resources/security/permissions.json),
read once at startup; a malformed file stops the server. Every handler must carry
`@RequiresPermission`, `@AuthenticatedOnly` or `@PublicEndpoint`, or the server refuses to start.
A missing permission answers `403` with `type: …/permission-denied` and the `permission` and
`module` that were needed. `JIZZA_SUPER_ADMIN_EMAIL` names a platform super admin who can enter any
account (only from a two-step session, and always audited).

Errors are RFC 9457 `application/problem+json` throughout.

## Tests

```bash
mvn test          # unit and slice tests; no Docker needed
mvn verify        # the same, plus the database-backed suites when Docker is available
```

Tests named `*DbTest` or `*E2eTest` need a Docker daemon: they run against the real
`pgvector/pgvector:pg16` image, because `citext`, `inet`, `jsonb` and `vector` have no honest
in-memory stand-in. Without Docker they skip rather than pretending to pass.

## Security notes worth knowing before you extend this

* **Sign-in is two-step and hard to replay.** Argon2id passwords, then a TOTP code that is accepted
  once only (the last used time step is stored). Recovery codes are hashed one by one and spent on
  use. Re-running setup stages the new authenticator, so the old one keeps protecting the account
  until a code from the new one is proven. Turning it on signs every other session out.
* **The session cookie is an opaque 256-bit token, and only its SHA-256 is stored.** It is
  `HttpOnly`, `SameSite=Strict`, and `__Host-` prefixed when served over TLS
  (`JIZZA_COOKIE_SECURE=true`). Changing the password signs out every other session; five wrong
  step-up answers in a row revoke the session that gave them.
* **The owner hears about it.** A sign-in from a new device, a spent recovery code, a password
  change or two-step being switched off raises an in-app notification and, when mail is set up,
  an email.
* **The client cleans up after itself.** It signs out after 15 minutes without activity
  (`VITE_IDLE_SIGN_OUT_MINUTES`), wipes its query cache on sign-out, and signs out every open tab
  together.

* **The Capability Registry is the only path that executes a tool.** It checks the agent is
  enabled, the kill switch is off, the arguments match the schema and the autonomy policy allows
  it — in that order — before anything runs. Adding a tool means adding a `ToolProvider`, not a
  new execution path.
* **Secrets never enter model context.** The model sees tool *schemas*; the executor injects auth
  server-side. The Anthropic client refuses to send a payload that still looks like it carries
  credential material.
* **Knowledge isolation is structural.** The calling agent id is the namespace and is part of every
  query; the knowledge tool schemas have no namespace parameter at all.
* **Outbound calls are guarded.** Only https, only publicly-routable addresses, only the host the
  connection declared, with redirects re-checked and response size capped.
* **The Transform node cannot escape.** JEXL with restricted permissions, construction and loops
  disabled, a data-only context and a hard timeout.

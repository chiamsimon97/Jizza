# Claude Code — Jizza Backend Assembly Prompt

Paste this into Claude Code at the root of an empty repo, with these four reference docs available in
the repo (e.g. under `/docs`): **jizza-project-charter.md**, **jizza-architecture-contract.md**,
**jizza-backend-blueprint.md**, **jizza-build-prompt.md**. Build strictly against them.

---

## Role
You are the lead engineer assembling the **Jizza** backend — a secure, local-first, single-user
personal-assistant platform: a named orchestrator agent (Jizza) delegating to named specialist agents
with isolated knowledge, OAuth2 connectors that turn API docs into tools, a durable Postgres-saga
workflow engine with human-intervention nodes, learned procedures that agents propose for review and
are then bound to follow, and a full autonomy/kill-switch/audit control plane.

## Stack (locked — see Charter)
Java 21 + Spring Boot 3.x · Maven multi-module (modular monolith, single deployable running API +
worker) · Spring Data JPA/Hibernate + PostgreSQL + pgvector + Flyway · Spring Security (Argon2id +
TOTP, server-side sessions, step-up) · WebSocket(STOMP)/SSE · custom Postgres-backed saga engine ·
Apache Commons JEXL3 sandbox · envelope encryption (AES-GCM behind `KeyProvider`) · RFC 9457 errors ·
MapStruct · Caffeine cache · React+Vite+React Flow frontend (separate; not in this backend pass).

## Non-negotiable invariants (verify continuously)
1. **Single point of contact:** users hit only `Orchestrator.handle`; every agent output reaches the
   user via the notification bus, attributed by agent name.
2. **Knowledge isolation** enforced in code (agent can only touch its own namespace).
3. **Autonomy gate + kill switch consulted before every autonomous tool call / run.**
4. **Secrets** envelope-encrypted at rest, never in LLM context or logs; model sees tool schemas only.
5. **Tool executor** SSRF-guarded (block private/metadata IPs), timeout + size caps, idempotent
   side-effects.
6. **Every workflow run is a checkpointed saga** — resume from the last completed node after restart.
7. **Every consequential action is written to the append-only, hash-chained audit log.**
8. Modules communicate only through `jizza-common` interfaces; no reaching into internals.

## Build order (one module per step; STOP at each checkpoint, compile + run its tests, then continue)
Follow the Blueprint specs in this exact order. After each, confirm the module's **Accept** checks pass
before moving on.

1. `jizza-common` → 2. `jizza-persistence` (Flyway `V1__init.sql` = full Contract schema) →
3. `jizza-security` → 4. `jizza-agents` → 5. `jizza-knowledge` → 6. `jizza-orchestrator` (+ seed Jizza,
Health, Finance, Professional) → 7. `jizza-connectors` → 8. `jizza-connectors-tools` →
9. `jizza-scheduler` → 10. `jizza-workflow` → 11. `jizza-procedures` → 12. `jizza-notifications` →
13. `jizza-autonomy` → 14. `jizza-platform` → 15. `jizza-web` → 16. `jizza-app`.

Where a module needs an interface whose implementation comes later (e.g. agents needs `AutonomyGate`),
wire by interface with a temporary permissive/no-op bean, and replace it when the real module lands.

## Per-step procedure
For each module: read its Blueprint spec + the relevant Contract interfaces → generate the Maven module,
entities/services/controllers → write unit tests for its **Accept** checks and the invariants it owns →
`mvn -pl <module> test` → fix until green → update a running `/docs/INTEGRATION-MANIFEST.md`
(module → interfaces exposed/consumed → status) → then proceed.

## Scaffold requirements
- Parent `pom.xml` + the 16 modules from the Blueprint; package root `com.jizza.<module>`.
- `application.yml` with `local` + `worker` profiles; externalized `ANTHROPIC_API_KEY`, master
  encryption key (from OS keystore / encrypted file — never in repo), DB creds, OAuth callback base URL.
- `docker-compose.yml`: PostgreSQL with **pgvector**, a local mail catcher (e.g. MailHog).
- `README.md`: `docker compose up` → `mvn spring-boot:run` (API + worker) → how to point the frontend
  at it; note the **OAuth callback needs a tunnel (ngrok/cloudflared) or localhost-permitting provider**
  in local dev, and that the callback base URL is config.
- Model strings for the LLM are **configurable** (tiering: cheap for routing, stronger for hard tasks);
  do not hardcode.

## End-to-end acceptance suite (run after module 16)
Automate these; they mirror the build-prompt Acceptance Criteria:
- Register → login → TOTP → session expiry/revoke; a sensitive route forces **step-up**; lockout fires.
- Secrets round-trip **encrypted**; grep confirms no token/PII in logs or any LLM request payload.
- **Namespace isolation** test: agent A cannot read/write agent B's knowledge.
- Chat: a domain question routes to the right named specialist and returns one aggregated reply; a
  cross-domain request fans out and merges; specialists never reach the user directly.
- OAuth2 connection completes via callback (**state + PKCE**), auto-refresh + **rotation**, revoke
  clears tokens; "Test connection" works.
- An **OpenAPI** doc generates tools; an assigned tool makes a real, **idempotent**, auth-injected call;
  **SSRF guard** blocks an internal-IP target.
- One-time + recurring tasks fire with **no browser**; two workers never double-fire; missed runs
  reconcile; DST-safe.
- A workflow runs; **kill the process mid-run → it resumes from the last completed node**; a
  side-effect node is exactly-once; a failure retries → dead-letters or raises intervention.
- **Intervention** node pauses → appears as "instruction needed" → **resumes on the submitted
  instruction**; the paused run survives a restart.
- A **repetitive job** yields an agent-proposed **workflow review**; approving **binds** it; the
  recurring job then executes it **exactly** (no improvising; deviation → intervention); editing
  re-enters review; **rollback** works.
- **Kill switch** halts all autonomy at the next checkpoint and resumes; **autonomy policy** blocks a
  `deny` and routes an `ask` to permission; per-agent disable works.
- Blocking notifications **expire + escalate** (email nudge); nothing hangs unseen.
- Realtime streaming + push work; **health/metrics** live; **user export + hard-delete** work.
- The **audit log** hash-chain verifies and the **activity view** reads it.

## Deliverable
A compiling, testable Spring Boot modular monolith that boots via `mvn spring-boot:run`, migrates the
schema, passes the module + end-to-end suites, and exposes the Contract §4 HTTP/WS surface for the
frontend to consume. Report any Contract gap you hit as a note in `/docs/INTEGRATION-MANIFEST.md`
rather than silently diverging.

# Testing Jizza

Three suites cover the app. All of them run against a throwaway database and a fake model, never
a real account or the real Anthropic API.

## Backend (`mvn test`)

Unit tests, database tests (`*DbTest`) and API end-to-end tests (`jizza-app/src/test/java/com/jizza/app/e2e`).
The database and e2e tests need PostgreSQL 16 with pgvector. Either have Docker running
(Testcontainers starts `pgvector/pgvector:pg16`) or point at a local server:

```bash
export JIZZA_TEST_DB_URL=jdbc:postgresql://localhost:5432/jizza   # user/password jizza/jizza
mvn test
```

The e2e package includes sweeps that read every route from Spring's handler mapping, so new
endpoints are covered automatically:

| Suite | What it checks |
|---|---|
| `ApiContractE2eTest` | Auth required, CSRF required, no 5xx on bad ids / malformed JSON / empty bodies / paging extremes, one problem+json shape, 413 on oversized bodies |
| `CrossAccountIsolationE2eTest` | A second account can't read or change anything the first owns, by path id or by an id in a request body |
| `RolePermissionMatrixE2eTest` | Every `@RequiresPermission` route against ADMIN, DEVELOPER and USER members; removed members lose access |
| `ModuleFlowsE2eTest` | One flow per module: connectors, services, guardrails, quiet hours, conversations, inbox, workflow library, usage, memory, agents, procedures |
| `ConcurrencyAndSessionE2eTest` | Double-sent turns, registration races, concurrent edit/delete (409, never 500), idle/absolute session expiry, stale CSRF |

If you changed an engine or platform module, `mvn install -DskipTests` first so `jizza-app` picks it up.

## Client unit and component tests (`npm test`)

```bash
cd jizza-client && npm test
```

Vitest with jsdom. Runs in CI.

## Browser end-to-end (`npm run test:e2e`)

Playwright drives the real backend jar, the Vite dev server and a stub model
(`e2e/stub-model.mjs`). It signs up a dedicated test account, `early-access-tester@jizza.test`,
seeds a task, memory, workflow and chat for it, then runs every page and flow on desktop and on a
phone-sized screen.

```bash
createdb -O jizza jizza_e2e                         # once; needs pgvector
rm -f jizza-app/target/*.jar*                       # make sure the jar is rebuilt
mvn -o package -DskipTests -pl jizza-app
cd jizza-client
PLAYWRIGHT_CHROMIUM_PATH=/path/to/chrome npm run test:e2e   # omit if Playwright's own browser is installed
```

Override the database with `JIZZA_E2E_DB_URL`. Ports used: 8091 (backend), 5199 (web), 4010 (stub model).
Every test also fails on an uncaught page error, a console error or any 5xx from the API.

## Pointing the dev server at another backend port

`JIZZA_API_URL=http://localhost:8081 npm run dev` (default `http://localhost:8080`).

# Jizza Agent Protocol v1

How a Jizza Agent app (Windows, macOS, Ubuntu, Android, iPhone) talks to a Jizza server. Every app
speaks this one protocol, so the server knows nothing about a platform beyond the push service that
wakes a phone. The server side lives in `jizza-engine/engine-devices`; `jizza-device-sim` is a
plain Java reference client, and `DevicesE2eTest` is the conformance suite it passes.

In code the apps are called **devices**, so they never clash with Jizza's AI agents.

## 1. Transport

| Path | Use |
| --- | --- |
| `POST /agent/v1/enroll` | Pair with a code, or enroll with a token. No auth. |
| `POST /agent/v1/auth/challenge` | Ask for a nonce to sign. No auth. |
| `POST /agent/v1/auth/token` | Trade a signed nonce for a 15-minute token. No auth. |
| `GET /agent/v1/ws` | WebSocket. The preferred channel for a desktop app. |
| `GET /agent/v1/commands?wait=25` | Long-poll for server messages (phones in the background, locked-down networks). |
| `POST /agent/v1/messages` | Send one device message over HTTPS; the answer is the reply, `ack` or `error`. |
| `PUT /agent/v1/files?commandId=&name=` | Upload a result file as the raw body (25 MB max). Answers `{"fileId"}`. |
| `GET /agent/v1/files/{id}` | Download a file a command hands the device. |
| `POST /agent/v1/push-token` | `{"provider":"apns"\|"fcm","token"}` so the server can wake a phone. |
| `POST /agent/v1/keys/rotate` | `{"publicKey","signature"}`: the new key, signed by the current one. |
| `GET /agent/v1/catalog` | The action catalog, to check a manifest before sending it. |
| `POST /agent/v1/smart-sign-in/scan` | `{"code"}` from a scanned login QR → what to show before approving (section 11). |
| `POST /agent/v1/smart-sign-in/decide` | `{"code","approve","signature"}`: approve or deny that sign-in (section 11). |

Everything after sign-in carries `Authorization: Bearer jzd_…`. The token lasts 15 minutes; sign in
again before it runs out. Over the WebSocket the token goes in the `Authorization` header of the
upgrade, or in the `token` field of the first `hello` within 10 seconds. A socket that fails auth,
or whose token has expired or whose device was disabled or revoked, is closed with code **4401**.
The server re-checks the token on every socket message.

Browser sessions never work on these paths, and these paths need no CSRF token.

## 2. Enrolling and signing in

1. The app makes a key pair and keeps the private key in the best store it has. Two kinds are
   accepted: **Ed25519** (`ed25519:<base64 X.509>`) and **ECDSA P-256** (`p256:<base64 X.509>`),
   which is the only kind an iPhone's Secure Enclave makes and the usual kind in Android hardware.
   A P-256 signature is ECDSA with SHA-256, as DER or as raw 64-byte r‖s. Stores:
   `tpm`, `secure_enclave`, `keystore` (Android), `keychain`, `dpapi`, or `file`. It reports which
   in `keyStorage`; Settings flags `file` as weaker.
2. The person clicks **Create agent** in Settings › Devices (step-up guarded) and gets a six-digit
   code and a QR code for `jizza://pair?code=482913&server=https://jizza.example.com`. The same
   link is shown as the **installation key** to copy: a desktop app should accept it pasted into
   its window, exactly as if its QR code had been scanned. Codes work
   once, for 10 minutes. The QR code also carries `&fp=`, the base64url SHA-256 (no padding) of
   the server signing key's X.509 bytes; the app refuses the enrollment if the key it gets back
   does not match. A machine nobody sits at uses an **enrollment token** (`jzt_…`) made by
   someone with `device_manage`; it joins as a shared device of the account, with the token's tags.
3. `POST /agent/v1/enroll`:

   ```json
   {"code": "482913", "publicKey": "ed25519:<base64 X.509 SubjectPublicKeyInfo>",
    "keyStorage": "tpm", "name": "Work laptop", "platform": "windows",
    "osVersion": "11 23H2", "appVersion": "0.1.0", "protocol": [1], "hostname": "chiam-9790"}
   ```

   Exactly one of `code` or `enrollmentToken`. `hostname` is optional: the machine's own host
   name, shown as the agent's host in Settings (and used as its name when `name` is empty).
   Settings watches the code with `GET /api/devices/pairings/{id}`, so its Create agent dialog
   moves on as soon as the app has enrolled. `platform` is one of `windows`, `macos`, `linux`,
   `android`, `ios`. The answer is `{"deviceId", "serverSigningKey": "ed25519:…", "protocolVersion"}`.
   Keep `serverSigningKey`: it is how the app knows a command really came from this server.
4. Sign in: `POST /auth/challenge {"deviceId"}` → `{"nonce", "expiresAt"}` (one minute, single
   use), then `POST /auth/token {"deviceId", "nonce", "signature"}` where `signature` is the
   base64 signature by the device key over the nonce's UTF-8 bytes → `{"token", "expiresAt"}`.

Enrollment and sign-in are rate limited per address (20 a minute). Every pairing raises an
in-app notification to the person and an audit entry.

## 3. Envelope

Every message, both directions, on both channels:

```json
{"v": 1, "type": "command", "id": "01JB…", "deviceId": "…", "ts": "2026-10-27T10:00:00Z",
 "replyTo": null, "body": {…}, "sig": "…"}
```

* `id` is unique per sender, at most 64 characters (the server uses ULIDs).
* `replyTo` names the message being answered.
* `sig` is present on server messages that tell the device to do something (`command`, `cancel`,
  `policy`, `inbox`). It is the base64 Ed25519 signature, by `serverSigningKey`, over the
  **canonical JSON of `body`**: compact, no whitespace, every object's keys sorted (Java
  `String.compareTo` order, which is code-point order for the characters used), arrays in order.
  An app rebuilds that text from the body it received before checking, so field order on the wire
  never matters.
* Messages are at most 256 KB; larger data goes through the files endpoints.

## 4. Messages

### Device → server

| type | body | server answers |
| --- | --- | --- |
| `hello` | `{"protocol":[1], "appVersion", "osVersion", "token"?}` | `welcome` |
| `manifest` | `{"actions":["browser.open@1",…], "capabilities":{"browser":{"allowedSites":[…]},…}, "limits"?, "tools"?, "items"?}` (§6.1) | `ack` |
| `heartbeat` | `{"state":"idle"\|"busy"\|"locked"}` every `heartbeatSec` | nothing |
| `accepted` | `{"commandId"}` | nothing |
| `rejected` | `{"commandId","code","message"}` | nothing |
| `progress` | `{"commandId","percent"?,"text"?}` | nothing |
| `result` | `{"commandId","status":"succeeded"\|"failed","output"?,"code"?,"message"?,"files"?:[fileId]}` | `ack` |
| `event` | `{"name":"file.added","data":{…}}` (64 KB max) | `ack` |
| `needs_user` | `{"commandId","reason"}`: the app is waiting for the person (a login, a captcha) | `ack` |
| `answer` | `{"notificationId","decision":"approve"\|"deny"\|"instruction","biometric","feedback"?,"instruction"?}` | `ack` |
| `log` | `{"lines":[{"ts","level","message","commandId"?}]}` (secrets are scrubbed, kept 7 days) | nothing |
| `bye` | `{}` | nothing |

Resend an acked message with the same `id` until the `ack` arrives.

### Server → device

| type | body |
| --- | --- |
| `welcome` | `{"protocol","sessionId","heartbeatSec","pollWaitSec","serverTime","pendingCommands","shellEnabled","blockedActions","supportedProtocols"}` |
| `command` | `{"commandId","deviceId","action","version","input","issuedAt","deadline","nonce","idempotencyKey"}` + `sig` |
| `cancel` | `{"commandId","deviceId","reason","issuedAt"}` + `sig` |
| `policy` | `{"deviceId","shellEnabled","blockedActions","issuedAt"}` + `sig`, sent when the owner changes them |
| `inbox` | `{"notificationId","deviceId","kind":"permission_requested"\|"instruction_required"\|"workflow_review","text"?}` + `sig` (phones only) |
| `ack` / `error` | `{}` / `{"code","message"}` |
| `bye` | `{"reason":"revoked"}` before the server closes a revoked device |

## 5. Running a command

Before running a `command` an app **must**:

1. verify `sig` against `serverSigningKey`;
2. check `deviceId` is its own;
3. check `deadline` has not passed;
4. check it has not seen `nonce` before (keep the last few thousand);
5. check the action against its own local limits (allowed sites, folders, apps) and the latest
   `policy` (shell off, blocked actions).

Any failure → `rejected` with `bad_signature`, `expired`, `policy_denied`, `permission_missing`
(an OS permission such as Accessibility is off), `busy` or `unsupported`. Otherwise send
`accepted`, optional `progress`, then exactly one `result`. A `cancel` for a running command stops
it and needs no answer beyond the eventual `result`, if any.

On the server a command is a row: `queued → dispatched → accepted → running →
succeeded | failed | rejected | expired | cancelled`. It is saved and signed before it is sent, so
a restart loses nothing; a device that connects later is sent what is queued for it, interactive
(chat) work first. At the deadline the broker expires it: `device_offline` if the device never
took it, `timed_out` if it did. `needs_user` extends the deadline by 10 minutes and raises an
urgent notification.

## 6. Action catalog

`engine-devices/src/main/resources/device-actions.json` lists every action apps build to, with its
input and output JSON Schemas, its capability, its guardrail action type, and its default time
limit. v1 has 27 actions in `system`, `notify`, `fs`, `browser`, `ui`, `office`, `phone` and
`shell` (`shell.run` is typed as the most consequential kind and is never sent unless the owner
turned shell on for that device, with step-up).

An app declares what it supports in its `manifest`, as `name` or `name@version`. Actions named
`x-<vendor>.<name>` are extras the catalog does not know; the server treats them as moving money
for the guardrails until the catalog adds them. The server only routes an action to a device that
declared it (except `system.ping`, which every app answers), and checks inputs against the schema
before sending.

Browser devices and every `browser.*` action follow the shared
[Web Automation Contract v1](web-automation-v1.md).

### 6.1 The app's own tools and saved items

Beyond the catalog, a manifest can describe **tools** the app adds itself and the **saved items**
catalog actions take by id. Both show in the workflow builder's Device action step.

```json
"tools": [{"action": "x-acme.exportLedger", "version": 2, "title": "Export the ledger",
           "description": "Writes the ledger to a CSV file",
           "input": {"type": "object", "properties": {"ledger": {"type": "string"}}, "required": ["ledger"]},
           "output": {"type": "object", "properties": {"rows": {"type": "integer"}}},
           "timeoutSec": 60}],
"items": {"shortcuts": [{"id": "Log water", "name": "Log water"}],
          "apps": [{"id": "com.microsoft.Excel", "name": "Excel"}]}
```

* A tool's name is `x-<vendor>.<name>`; it needs no separate entry in `actions`. At most 50 tools,
  each schema at most 16 KB. `input` defaults to any object; `timeoutSec` to 120 (1 to 86400).
* The server checks a command's input against the tool's own `input` schema before sending it, and
  the builder shows one field per property and offers `output` fields to later steps.
* The server cannot check what a tool does, so the guardrails count it as moving money until the
  device's owner says what it does (Settings › Devices, `PATCH /api/devices/{id}/tools/{action}`
  `{"actionType":"READ"}`, step-up guarded). By default that never runs on its own.
* `items` maps a kind (`[a-z][a-z0-9_]*`, at most 10 kinds) to at most 500 `{id, name,
  description?}`. A catalog input that takes one says so with `"x-items": "<kind>"`:
  `phone.shortcut.name` takes `shortcuts` and `ui.openApp.app` takes `apps`. The builder then
  offers the device's list. (Browser recordings live on the server; see the Web Automation
  Contract.)
* Send a new manifest whenever tools or items change; each manifest replaces the last.

A manifest whose tools or items break these rules gets `error` naming what is wrong.

## 7. Error codes

`bad_message`, `unsupported_type`, `unsupported_version`, `unauthorized`, `not_found`,
`too_large`, `rate_limited`, `policy_denied`, `permission_missing`, `busy`, `unsupported`,
`bad_signature`, `expired`, `device_offline`, `timed_out`, `cancelled`, `failed`, `revoked`.

## 8. Push

A phone that is not connected is woken by a push carrying no data beyond
`{"type":"command"|"inbox"}` and ids; the app then polls. A person's own phones are also sent
`{"type":"run_failed","runId"}` whenever one of their runs fails (not a sub-workflow's run, whose
failure reaches them through its parent), connected or not, once the failure is saved; the app
reads the run and shows the alert. The server has a `PushProvider` seam for
APNs and FCM; without one configured, a phone picks up work at its next poll. A token the provider
reports invalid is forgotten.

**FCM (Android)** is built in. Set `JIZZA_FCM_CREDENTIALS` to the path of the Firebase project's
service account key file (Firebase console › Project settings › Service accounts › Generate new
private key), kept outside the repository. The server signs in to Google with it and sends each
push as a high-priority FCM data message; every data value is a string. The app registers its FCM
token with `POST /agent/v1/push-token {"provider":"fcm","token":"…"}`. A missing or unreadable key
file is logged at start-up and push stays off.

**APNs (iPhone)** is built in too, with token-based sign-in. In the Apple Developer account, create
an APNs auth key (Certificates, IDs & Profiles › Keys › Apple Push Notifications service) and keep
the downloaded `AuthKey_XXXX.p8` outside the repository. Set `JIZZA_APNS_KEY_FILE` to its path,
`JIZZA_APNS_KEY_ID` to its key id, `JIZZA_APNS_TEAM_ID` to the team id and `JIZZA_APNS_TOPIC` to
the app's bundle id (default `com.jizza.ios`). Apps installed from Xcode get development tokens,
so set `JIZZA_APNS_SANDBOX=true` for them; TestFlight and App Store builds use production. The app
registers its token with `{"provider":"apns","token":"<hex>"}`. The ids go under the `jizza` key
of the APNs payload. `inbox` and `run_failed` show a fixed alert ("Jizza needs your answer", "A
run failed") collapsed per item, never the item's own text; other types are silent
`content-available` wakes. A 410, `BadDeviceToken` or `DeviceTokenNotForTopic` forgets the token.

## 9. Conformance

An app conforms when it does what `SimDevice` does in each `DevicesE2eTest` scenario:

1. pairs with a code (and, with a P-256 key, checks the QR fingerprint), signs in, says hello and gets `welcome`, sends a manifest and gets `ack`;
   the code does not work twice;
2. answers a test connection (`system.ping`) over HTTPS long-polling;
3. runs a Device action workflow step over the WebSocket and the run finishes with its output;
4. an action it did not declare is refused before it is sent;
   a tool it describes itself runs in a workflow once its owner types it, inputs that break its
   schema are refused, and a manifest with a badly named tool gets `error`;
5. refuses a command whose body was changed after signing (`bad_signature`);
6. enrolls with an enrollment token and carries its tags;
7. once revoked, can neither sign in nor poll;
8. is invisible to another person;
9. agent endpoints refuse calls without a device token, including a browser session;
10. a message type outside the protocol gets `error` / `unsupported_type`.

Run it with `JIZZA_TEST_DB_URL=jdbc:postgresql://localhost:5432/jizza_test mvn -pl jizza-app test -Dtest=DevicesE2eTest`.

## 10. Trying it by hand

Pair from Settings › Devices, then:

```
mvn -q -pl jizza-device-sim -am install -DskipTests
java -cp jizza-device-sim/target/classes:$(mvn -q -pl jizza-device-sim dependency:build-classpath -Dmdep.outputFile=/dev/stdout) \
  com.jizza.devicesim.SimDevice http://localhost:8081 <code>
```

The simulator answers `system.ping`, `system.info` and `notify.show`.

## 11. Smart sign-in

The Jizza login page can show a QR code instead of asking for a password. A phone paired by a
person (not a shared machine enrolled with a token) can approve it, and the browser is then signed
in to **that person's** account.

1. The QR holds a URL: `https://<jizza>/smart-sign-in#<code>`. The code is everything after `#`.
   Accept only URLs whose origin is the server the app is paired with, and whose path is
   `/smart-sign-in`; ignore anything else.
2. `POST /agent/v1/smart-sign-in/scan {"code"}` with the device token. The answer:
   `{"id","browser","ip","createdAt","expiresAt","sameNetwork","state"}`. `browser` reads like
   "Chrome on Windows". Scanning claims the code for this person: no other account can approve it.
3. Show the browser, the IP and the time, and the warning "Only approve if you are signing in on a
   screen in front of you right now." Ask for the phone's fingerprint, face or PIN (Android
   `BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`) before an approval: the server
   counts an approval from a paired phone as the second factor, so the app must check that the
   phone's owner is holding it.
4. Sign `jizza-smart-sign-in:v1:<code>:approve` (or `:deny`) with the device key, the same way as a
   challenge nonce, and `POST /agent/v1/smart-sign-in/decide {"code","approve":true|false,
   "signature"}`. The answer is `{"state":"APPROVED"|"DENIED"}`.

Errors are problem details: 404 `smart-sign-in-unknown` (bad code), 410 `smart-sign-in-expired`
(codes last 2 minutes), 409 `smart-sign-in-used` (already answered, or claimed by another account),
401 `bad_signature`, 401 `device_not_personal`.

## 12. The Jizza app on your phone

The Jizza app is a hands-on companion, not a copy of the web app: it shows a person their runs,
approvals, usage, activity, devices, agents and guardrails, and lets them approve, run, cancel and
Pause all from the phone. It uses the same pairing and device key as everything above.

### Signing in on the phone

Besides scanning the pairing QR from Settings › Devices, the app can sign in by itself:

1. `POST /agent/v1/login {"email","password"}` → `{"mfaRequired":true,"mfaToken":"…"}`.
2. `POST /agent/v1/login/mfa {"mfaToken","code"}` → `{"pairingCode":"482913","expiresAt","pairUrl"}`.
3. Enroll with that code (`POST /agent/v1/enroll`, section 2), check `fp` from `pairUrl` as usual,
   then sign in with the device key.

The login opens no session for the phone: the one it creates is closed again at once. Two-step
sign-in must be on, because a paired phone can approve a browser sign-in as the second factor
(section 11). Without it the first call answers 403 `two-step-required`. Wrong passwords count
towards the same lockouts as the web login.

### Reading: `GET /agent/v1/me/…`

Every call takes the device token, and only a phone a person paired gets an answer; a shared
machine enrolled with a token gets 401 `device_not_personal`. Each route returns the same data as
its `/api` twin for the phone's owner.

| Route | What it returns |
| --- | --- |
| `/agent/v1/me` | `userId`, `workspaceId`, `deviceId`, `deviceName`, `access` |
| `/agent/v1/me/today?tz=` | The Today screen: `waiting` (needsYou, urgent, unread), `runs` since local midnight (total, done, failed, running, waiting, `active` list), `spend`, `devices` (total, online, offline names), `paused` |
| `/agent/v1/me/notifications[?unreadOnly=]`, `/notifications/summary`, `/notifications/{id}` | The inbox, as `/api/notifications` |
| `/agent/v1/me/runs?status=&workflowId=&from=&to=&q=&limit=&offset=&tz=`, `/runs/{id}/trace` | Run history, as `/api/runs` (limit at most 100) |
| `/agent/v1/me/workflows` | id, name, description, status, version, inputSchema, enabled (on for its triggers: a form, a schedule), formId and formName (the form whose submissions start it, or null) |
| `/agent/v1/me/usage`, `/usage/entries?limit=` | As `/api/usage/summary` and `/entries` |
| `/agent/v1/me/activity?limit=&action=` | As `/api/activity` |
| `/agent/v1/me/guardrails` | As `/api/guardrails` |
| `/agent/v1/me/devices` | As `/api/devices` |
| `/agent/v1/me/agents` | id, name, description, status, enabled, modelTier, monthlyCostLimit, parentAgentId (no instructions) |
| `/agent/v1/me/help` | The web's Help guides (jizza-client/src/help/guides): slug, title, summary, section, order, updated |
| `/agent/v1/me/help/{slug}` | One guide with its Markdown `body`, header removed |

### Acting: signed requests

A call that changes something must be signed with the device key, which the app unlocks with the
person's fingerprint, face or PIN (Android `BiometricPrompt` with `BIOMETRIC_STRONG or
DEVICE_CREDENTIAL`). The app signs

```
jizza-mobile:v1:<METHOD>:<path>:<signedAt>:<nonce>:<base64url SHA-256 of the exact body bytes, no padding>
```

where `path` is the request path without the query, `signedAt` is milliseconds since 1970 and
`nonce` is fresh random text (at most 100 characters), and sends the three as headers
`X-Jizza-Signature` (base64 signature, as for a challenge), `X-Jizza-Signed-At` and
`X-Jizza-Nonce`. An empty body hashes the empty string. The server refuses a signature more than
two minutes from its clock (401 `signature_expired`), one it has already seen from that phone
(401 `replayed`) and one that does not match (401 `bad_signature`).

| Route | Body | Needs |
| --- | --- | --- |
| `POST /agent/v1/me/notifications/{id}/answer` | `{"kind","instruction","feedback"}`, kind APPROVE, DENY or SUBMIT_INSTRUCTION | approve |
| `POST /agent/v1/me/notifications/{id}/read` | empty: marks it read, as opening it on the web does | read |
| `POST /agent/v1/me/notifications/{id}/snooze` | `{"until"}` (empty brings it back) | approve |
| `POST /agent/v1/me/notifications/{id}/archive` | `{"archived"}` (default true; false brings it back) | approve |
| `POST /agent/v1/me/notifications/{id}/always-allow` | empty: saves an allow rule for that agent and `context.tool`, then approves (open PERMISSION_REQUEST only) | full |
| `POST /agent/v1/me/pause` | `{"paused":true}` | approve |
| `POST /agent/v1/me/pause` | `{"paused":false}` | full |
| `POST /agent/v1/me/agents/{id}/enabled` | `{"enabled":false}` | approve |
| `POST /agent/v1/me/agents/{id}/enabled` | `{"enabled":true}` | full |
| `POST /agent/v1/me/workflows/{id}/enabled` | `{"enabled":false}` | approve |
| `POST /agent/v1/me/workflows/{id}/enabled` | `{"enabled":true}` | full |
| `POST /agent/v1/me/workflows/{id}/run` | `{"input":{…},"version"}` | full |
| `POST /agent/v1/me/runs/{id}/cancel` | empty | full |
| `POST /agent/v1/me/runs/{id}/resume` | `{"instruction","approved"}` | full |

### Ask

`POST /agent/v1/me/ask {"question","tz"}` (device token, not signed, read only) answers a question
about the person's own Jizza in a few sentences, on the cheap model, from facts the server gathers:
today's numbers, the last 7 days of runs, workflows, active runs and what waits for them. It
answers `{"answer", "command"}`. `command` is null or one of `RUN_WORKFLOW` (with `workflowId`, only a
workflow that runs without input), `CANCEL_RUN` (with `runId`, only an active run), `PAUSE_ALL` or
`RESUME_ALL`, each with a `label`. The app shows it as a card to confirm with the fingerprint and
then calls that action's signed route above; asking itself changes nothing. Spend is recorded as
`phone-ask` and the budget applies.

### Phone access

The owner chooses what the app may do in Settings › Devices › Details on that phone (or
`PUT /api/devices/{id}/phone-access {"access":"FULL"}` with READ, APPROVE or FULL, step-up guarded): read
only; read and approve (the default); or full. A call beyond it answers 403 `phone-access`. Every
change from a phone is in the activity log as `device.phone.*` with the phone's id and name.

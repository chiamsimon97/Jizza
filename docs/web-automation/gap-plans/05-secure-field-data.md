# 05. Secure field data between the server and the agent (Requirement 4)

> Web automation field values are accessed and passed between the web server and the agent; we
> need strong security for that transfer.

## Where field data travels today

```
 Person (web app / chat) ──HTTPS+JWT──▶ Jizza server ──WSS/HTTPS, signed command──▶ Extension ──▶ Page
            ▲                               │  ▲                                        │
            └──── card answers (REST) ──────┘  └──── events: recording steps, outputs ───┘
```

| Hop / store | Protection today | Code |
|-------------|------------------|------|
| Server → extension commands | TLS; **Ed25519 signature** over the command, nonce window (replay), deadline | `src/protocol/verify.ts`, `src/commands/nonces.ts` |
| Extension → server events | TLS; bearer access token from a P-256 device-key challenge; **no per-message signature** | `src/protocol/envelope.ts`, `src/device/keys.ts` |
| `device_commands.input/output` | **Encrypted at rest** (`EncryptionService`) | `CommandBroker` |
| `web_recording_steps.step` | Encrypted at rest; sensitive steps keep no value | `RecordingService` |
| Device files (screenshots, downloads) | Encrypted at rest in storage | `AgentGatewayService` |
| Saved logins | Only in the extension vault, AES-GCM with a non-extractable key; server sees id + name | `src/vault/vault.ts` |
| `workflow_runs.context` (run variables, approved inputs) | **Plaintext jsonb** | `WorkflowRunEntity` |
| `workflow_run_steps.input/output` | **Plaintext jsonb** | `WorkflowRunStepEntity` |
| `notifications.context` (card form: values, answers, list items) | **Plaintext jsonb** | `NotificationEntity` |
| `workflows.graph` / versions (recorded defaults, e.g. real passenger names) | **Plaintext jsonb**, can be shared/published | `WorkflowEntity` |
| Dev/LAN setup | Extension dev build can pair over **plain HTTP** (`ALLOW_HTTP`), as used on the Mac at `http://192.168.0.226:8081` | `src/device/enrollment.ts` |

So the "wire" part is in good shape; the weak points are **values at rest outside the command
table**, **recorded defaults**, **no secret type for run-time values**, **unsigned device
events**, and **plain HTTP in testing**.

## Threats considered

1. Database dump or backup leak → passenger names, phone numbers, OTPs, card answers readable.
2. Shared/public workflow → recorded defaults leak the recorder's personal data.
3. Stolen device token → attacker posts fake recording steps or fake outputs ("booking done").
4. Compromised or malicious page script reads values the extension types (unavoidable once a
   value is typed into a page; limit what is typed where).
5. Command redirected to the wrong site (DNS, look-alike domain) → credentials typed into a
   phishing page.
6. Network sniffing on LAN/dev (plain HTTP).
7. Logs, error messages and LLM prompts containing values ("Let Jizza decide" sends list items
   and sometimes inputs to the model).
8. Insider/admin on the server reading values.

## Plan

### P0 (small, do first)

1. **Classify every value** with the `sensitivity` from plan 02: `none | personal | secret`.
   Recorder already detects sensitive fields (`isSensitiveField`); map that to `secret`, and add
   `personal` for names, phone, email, address, age, ID numbers (by field type/name).
2. **Encrypt at rest** `workflow_runs.context`, `workflow_run_steps.input/output` and
   `notifications.context` fields that hold `personal` values: store them in an encrypted side
   map (`context.sealed`, bytea via `EncryptionService`), keep non-sensitive keys queryable.
   Simpler alternative: encrypt these three columns whole (bytea like `device_commands`). I
   recommend the whole-column approach first: fewer edge cases; search on them isn't needed.
3. **Never keep `secret` values**: OTP, captcha text, one-off passwords, CVV live only in memory
   for the command that types them. Card answers of type `secret` are passed straight to the
   command and replaced by `"••••"` in the notification and run records.
4. **No recorded defaults for personal/secret inputs** (plan 02 G13); existing workflows: a
   one-off migration job that blanks defaults of inputs whose step field looks personal, and the
   publish flow refuses graphs with personal defaults.
5. **Plain HTTP**: the production extension build already refuses; add a server-side flag
   `jizza.security.require-https=true` by default (dev profile false) that refuses device
   enrollment and agent sockets over non-TLS, and a red banner in the dev extension when paired
   over HTTP. For LAN phone testing, document a local TLS option (mkcert) instead.
6. **Redaction**: one `Redactor` used by logs, error messages, audit entries and LLM prompts;
   values tagged `personal` are masked (`R***h, 34`), `secret` removed.

### P1 (stronger transfer)

7. **Signed device events**: the extension signs each outgoing envelope with its P-256 device
   key (`sig` over canonical JSON, the same canonicalisation used for commands), with a counter;
   the server verifies and drops replays. Stops a stolen bearer token from forging outputs.
   Bearer tokens become short-lived (e.g. 15 min) and bound to the device key (DPoP-style proof).
8. **Site binding for commands carrying values**: each command that types a value includes the
   expected origin (from the recording). The extension refuses to type into a tab whose origin
   differs (`irctc.co.in` only) and for `secret` values also requires the page to be HTTPS and
   the field to be a real input of the expected kind (`type=password` for passwords). Guards
   against threat 5. The site allowlist already exists; this makes it per command.
9. **Field-level encryption to the agent (option A, recommended)**: for `secret` and `personal`
   values in commands, the server encrypts the value to the device's X25519 key (new key pair at
   pairing, alongside the P-256 key) using HPKE / ECDH+AES-GCM. The command table already
   encrypts at rest; this adds that only the target device can open the value, and the payload
   seen by intermediate logging/proxies is ciphertext.
10. **End-to-end from the person to the agent (option B, only for `secret`)**: the web app card
    encrypts a `secret` answer (OTP, captcha, one-off password) directly to the device's public
    key in the browser; the server only relays ciphertext and can't read it. Cost: the server
    can't validate the value (only length/pattern hints done client-side). Recommended for
    `secret` only; `personal` values need server validation and matching (plan 02).

### P2 (hardening)

11. Per-user data keys (envelope encryption: master key → user key → values), so one user's data
    can be crypto-shredded on account deletion, and key rotation is per user.
12. **Retention**: run context and card answers with personal data purged after N days
    (configurable, default 30); screenshots of pages with personal data after 7 days; downloads
    (tickets, statements) kept until the person deletes them, encrypted.
13. **Audit**: who/what read a sealed value (run engine, card render, LLM call) is written to the
    audit log; the person can see "values used by this run".
14. Extension: values received for a command are kept only in the service worker's memory, never
    in `chrome.storage`; side panel shows masked values; recorder meta never stores typed text
    of `personal` fields beyond a masked preview.
15. Review the LLM path: "Let Jizza decide" sends list item text (train names) but must not send
    passenger details; route through the Redactor with an allowlist of keys.

## Acceptance checks

1. A DB dump of `workflow_runs`, `workflow_run_steps`, `notifications` shows no passenger names,
   phone numbers, OTPs or captcha text in plaintext.
2. An OTP typed on a card is typed on the page and appears nowhere afterwards (DB, logs, audit
   shows "secret used").
3. A forged `browser.recording.step` sent with a stolen token but no valid signature is rejected.
4. A command with `expectOrigin: https://www.irctc.co.in` refuses to type when the tab is on
   another origin, and the run shows a clear failure.
5. Publishing a workflow with a personal default is refused with a message naming the input.
6. Production server refuses agent sockets over plain HTTP.

## Size

L overall. P0 about 3–4 days (server mostly). P1 about a week (extension crypto + server).
P2 as follow-ups.

## Questions for Chiam

1. Option B (end-to-end for secrets) is worth it? It makes OTP/captcha answers unreadable by the
   server. I recommend yes for `secret` only.
2. Retention defaults (30 days runs, 7 days screenshots) OK?
3. Is mkcert-based local HTTPS acceptable for LAN phone testing, so plain HTTP can be off by
   default even in dev?

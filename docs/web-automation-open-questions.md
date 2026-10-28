# Web automation open questions

This file records places where the implementation prompt and the current `release/M2_3_1` code do
not line up, plus the narrow choices made so work can continue safely.

## W0

- PRs #60 and #61 are not merged into `release/M2_3_1`. The branch has migrations only through
  V30, no saved device `items`, no Create agent dialog, and no installer configuration surface.
  W0 reserves V34 as directed so V31-V33 remain available to those pull requests. The browser
  pairing tab is a small addition to the existing Pair device dialog and shows the extension as
  "Coming soon"; it is expected to conflict with #61 and should be folded into that dialog's
  `browser-chromium` installer tab when #61 lands.
- A database already at V33 cannot be constructed from this branch because migrations V31-V33 are
  not present. The migration is tested as part of a fresh Flyway migration. CI should also run the
  same migration after #60/#61 are merged.
- The proposed data model shows `web_recording_steps.step` as `jsonb`, then requires it to be
  encrypted in the same way as `device_commands` inputs and outputs. Those command columns are
  `bytea`, so W0 uses `bytea` for recorded steps. W3 must encrypt and decrypt this column through
  `EncryptionService`; plaintext step JSON must never be persisted.
- The current pairing UI has no configured installer API. W0 cannot read
  `jizza.devices.installers.browser-chromium`, so it deliberately does not invent a second
  configuration path. The pending #61 UI must supply that configured link.
- The existing simulator always enrolled Ed25519 devices with `keyStorage: file`. W0 added an
  explicit simulator key-storage setting so `--browser` and conformance tests can accurately send
  `keyStorage: browser` without changing existing simulator defaults.

## W1

- PRs #60 and #61 are still absent from `release/M2_3_1`. W1 does not depend on saved manifest
  items and avoids changing the pending Create agent dialog. The Browser selector uses only the
  device fields already available on this branch.
- The prompt says a design-time test runs guardrails "as a chat tool would", but the REST response
  has no approval-card round trip. W1 calls `AutonomyGate.decide`; an allowed action runs, a denied
  action is forbidden, and an ask/Jizza decision returns a conflict explaining that guardrail
  approval is required. The explicit Test step click does not silently bypass or rewrite policy.
- Platform use cases are enforced to compile against `engine-api` only. `BrowserAgents` and
  `DesignTimeService` are therefore engine-api ports implemented by the new `engine-webauto`
  module, with that implementation added to the application's runtime classpath.
- `DeviceCommands.await` returns the current command when the 55-second design-time wait ends; it
  does not create a separate timeout status. W1 maps an unfinished response to `timed_out` for the
  synchronous REST response while the durable command remains visible through the existing device
  command history.
- Browser affinity applies only to `target = sameBrowser`. Choosing `any` asks the broker to select
  a browser independently, and choosing a device id always honors that exact device; neither choice
  silently receives the prior session id.
- A session named explicitly in a web step is treated as caller-owned unless `browser.open` also
  requests `in = newSession`. Child runs inherit the parent's affinity as non-owning, while a
  workflow-owned session is closed on DONE, FAILED or CANCELLED unless the trigger keeps it open.
- `newBrowserSession` is carried into the child run and forces its first `browser.open` to use
  `in = newSession`; merely omitting the parent's session is not enough because a browser device
  may otherwise reuse its ambient session.
- Workspace browser ids carry their workspace through affinity and terminal cleanup so a browser
  shown by the workspace-scoped picker remains routable when the workflow runs.
- Terminal browser closes are safety cleanup rather than new autonomous work. They use a reserved
  internal command marker, may be queued while autonomy is paused, and are excluded from a later
  kill-switch sweep; normal callers cannot submit a command with that marker.

## W2 — Pick on page

- **Pick and guardrails.** `browser.pickElement` goes through the same guardrail check as Test step. If the guardrail says ASK or JIZZA, `POST /api/web/pick` returns a Conflict instead of starting a pick.
- **Preview.** The locator bundle schema allows extra properties, so the preview file id is stored inside `inputs.element.preview`. The server does not interpret it.
- **Session.** For `sameBrowser` steps no session id is sent with the pick; the extension uses the active tab.
- **Results.** A failed or cancelled pick with no error kind is reported as `cancelled_by_person` or `failed`. Only the command's owner can read it; anyone else gets 404.
- **Get element selectors.** Its outputs (`element`, `css`, `xpath`) already reach the chips through the catalog output schema, so no code change was needed.

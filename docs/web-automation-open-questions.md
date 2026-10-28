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

## W3 — Record and save as a module

- **Insert route (addition).** `POST /api/web/recordings/{id}/insert` is not in the REST table. The builder's "Record steps here" needs steps, not a saved workflow, so it compiles the reviewed steps without Start and End and marks the recording `saved`. Mapped inputs keep their recorded value, because a half-built workflow has no module inputs to fill.
- **Stop.** `POST /recordings/{id}/stop` moves the recording to `review` at once. If the extension's own `stopped` event arrives later it is ignored.
- **Grace period.** When a record command has finished but the recording is still `recording`, the server waits 3 seconds before moving it to review, so events still in the intake queue are not lost. Nothing captured moves it to `failed`.
- **Order.** New steps get `position = seq`. Events can arrive out of order, so steps are keyed by `seq`, duplicates are dropped, and edits renumber positions.
- **Limits.** 500 steps per recording and 32 KB per step. Values of sensitive steps are removed on intake, never stored.
- **Guardrails.** Starting a recording is judged as `browser.record`. If it needs approval the call returns a Conflict, as Pick does.
- **Default reuse profile.** A saved recording asks before each run and is not allowed to run on a schedule. The person changes this in the workflow's reuse settings.
- **Saved logins.** PR #60 (`items.logins`) is not merged, so the review screen has a free-text "Saved login id" field. Switch it to a picker when #60 lands. A password step cannot be saved without a login.
- **Clean-up.** Reviews untouched for 7 days are discarded; steps of discarded recordings are removed (sweep every `jizza.webauto.sweep-interval`, default `PT1M`).
- **Saved list.** The Recordings page lists workflows with `origin = RECORDED`; drafts are recordings in `recording` or `review`.
- **Late events.** Steps handled within 3 seconds after a recording went to review are still kept (events are processed by queue workers, so order is not guaranteed). A device edit for a step not yet stored is dropped; the person can make the same edit in review.

## W4 — Chat

- **Stop and Open review buttons.** Steps cards can only link to absolute http(s) addresses or send a reply, so both buttons are replies. `web_record` therefore takes `action` (`start`, `stop`, `review`) and `recordingId` as well as `url`, `name` and `deviceId`; the Stop reply asks Jizza to call it with `stop`.
- **Open review.** The recordings page is added to the pages Jizza can open (`app_navigate`, key `recordings`). It opens the list, where the draft is resumed; there is no deep link to one recording yet.
- **Which browser.** With one browser agent that can record, it is used. With several, Jizza must ask and pass `deviceId`.
- **Always ask.** `ActionTypeSource` gained a default `alwaysAsks` method. The gate turns an ALLOW or JIZZA answer into ASK for a tool whose source says so; a DENY stays a DENY. Only `DeviceToolProvider` uses it, for `browser.*` actions whose type is not READ, in chat. Workflow runs are unchanged: each step still meets its own rule.
- **Saved recordings in chat.** `find_saved_workflows` and `run_saved_workflow` already include any described workflow, and a saved recording is described when it is saved, so no code change was needed. Its reuse profile asks before each run.
- **Help text.** `app_propose_workflow` has no web step kind (its steps are tool, agent or ask), so its description only says to prefer a saved recording or `web_record` and never to invent selectors. The web step shape is in the `propose_workflow` graph help.

## W5 — Reliability

- **Table.** `web_locator_fixes` was already created by V34 in W0, so W5 adds no migration.
- **When a fix is proposed.** The device step hands the browser's `output.error` to `LocatorFixes.propose` when the kind is `element_not_found` and there is at least one candidate. It runs in its own transaction and a failure there is logged, never changing how the step fails. Runs without a workflow (design-time tests) propose nothing.
- **One open fix per step.** If the same step fails again while its fix is open, the fix gets the new candidates and run id, and no second notification is sent. A dismissed fix does not stop a new one being opened the next time the step fails.
- **Candidates kept.** At most 5, each with an `element` that has strategies (16 KB max), plus `score` (clamped to 0–1) and `text` (200 characters). Anything else the browser sent is dropped.
- **Step number in the notification.** Counted in graph order, without Start and End steps. The notification is INFO; its detail has `source: "webauto"`, `kind: "locator_fix"`, `fixId`, `nodeId` and `link` (`/workflows?open=<id>&node=<step>`). The client only follows links of that shape.
- **New version.** `WorkflowRegistry.saveNewVersion` was added: for a draft, the current version keeps its snapshot and the fixed graph becomes version + 1, still a draft. A workflow past draft goes through `ProcedureService.revise`, which makes a new version in review, because the builder rule is that anything past draft changes through review. Apply on a step that is no longer in the current version is a Conflict asking the person to dismiss.
- **Merging the element.** The chosen candidate's bundle comes first; every old strategy not already in it follows as a fallback. `frames` and `shadow` carry over from the old element when the candidate has none, the old `label` is kept when the candidate has none, and the old `preview` is dropped because it shows the old element.
- **Library permissions.** A library workflow allowed to run unattended is allowed for one graph fingerprint. A fix changes the graph, so unattended runs need to be allowed again. This is the existing rule and is kept on purpose.
- **Ranking.** `jizza.webauto.ai-fix-ranking` (default `false`) sends the lost element's label, tag, text and near text and each candidate's text and label to the routing-tier model, and reads back a list of numbers. Any answer it cannot read keeps the browser's order. Only these short texts are sent; never typed values or page HTML.
- **Run history.** `TraceStep` gained `failure` for failed device steps: `kind`, `detail`, `url`, screenshot `files`, `deviceId` (the step's error now records which device kept the screenshot) and the number of candidates. The candidates themselves stay with the fix. The **Review fix** link shows only while the step has an open fix.
- **Builder.** The workflow builder shows the open fixes for the workflow above the canvas and opens on the step named by `&node=`. After Apply, the builder reopens on the new version.
- **Fast replay scope.** `jizza.webauto.fast-replay` (default `false`). It applies only when the graph is Start → web steps in one line → one End, every web step targets `sameBrowser` (or no target), names no session or account, and no input reads another step's output (`{{ … nodes … }}`), since those can't be filled before the step runs. Steps with explicit device ids are left step by step: the narrowest safe reading of "every device in play".
- **Fast replay guardrails.** Every step is first evaluated, then decided, with its own tool ref, node id and action type. Anything other than a plain ALLOW (ask, Jizza, deny) leaves the module step by step, where the person is asked as usual. The broker also checks every step in the batch against the catalog, the device's declared versions, its blocked actions and its allowed sites. `browser.runSteps` is not offered as a chat tool.
- **Fast replay fallback.** The batch is tried once, by the first web step on its first attempt. If it can't be sent (most often because the browser has no `browser.runSteps`), the steps run one by one. If it fails part-way, steps before `failedAt` keep their outputs and the failing step and the ones after it run one by one, so the failing step is tried again on its own and fails, retries and proposes a fix exactly as it does without the batch.

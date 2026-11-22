# Web Automation Contract v1

This contract is shared by the Jizza server and the Jizza Browser Agent. Changes must be made in
both repositories.

## What the extension declares in its manifest

```json
{"actions": ["system.ping", "system.info@1", "notify.show@1",
             "browser.open@2", "browser.click@2", "browser.hover@1", "browser.setText@1", "browser.getText@1",
             "browser.setCheckbox@1", "browser.getCheckbox@1", "browser.selectItem@1", "browser.extractTable@1",
             "browser.selectRadio@1", "browser.getRadio@1", "browser.scroll@1", "browser.sendKeys@1",
             "browser.typeInto@1", "browser.exists@1", "browser.switchTo@1", "browser.refresh@1",
             "browser.getSelectors@1", "browser.pageInfo@1", "browser.forward@1", "browser.back@1",
             "browser.close@1", "browser.wait@1", "browser.screenshot@1", "browser.download@1",
             "browser.pickElement@1", "browser.record@1"],
 "capabilities": {"browser": {"family": "chrome", "version": "131.0.6778.86",
                              "allowedSites": ["*.zoho.com", "portal.example.com"],
                              "trustedInput": true, "sessions": 3},
                  "notify": {}},
 "items": {"logins": [{"id": "login_3f2a", "name": "Supplier portal (finance@acme)", "description": "portal.example.com"}]}}
```

* `allowedSites` is the person's local list (host patterns, `*.` prefix allowed). Send a new manifest whenever it, the saved logins, or granted host permissions change.
* `items.logins` lists saved logins by id and name only (PR #60 "saved items": ≤ 500 entries, kind name `[a-z][a-z0-9_]*`). The builder shows them in a picker for `secretRef`. **Never** send usernames or passwords.
* Do not declare `browser.runSteps` until milestone B9 is done.

## Web action catalog (added to `device-actions.json`; ● = `element` required)

Every browser action takes these **common inputs** (merge them into each schema):

| field | type | meaning |
| --- | --- | --- |
| `session` | string, optional | Session id from `browser.open`. Missing → the most recent live session; none → `failed` with `output.error.kind = "no_session"` (except `browser.open`, which then creates one). |
| `element` | Locator bundle, required where marked ● | The element to act on (see **Locator bundle**). |
| `timeoutMs` | integer, default 15000, max 120000 | How long to wait for the element to exist and be actionable. |
| `trusted` | boolean, default false | Use CDP input (real key and mouse events) instead of DOM events. |
| `screenshotOnError` | boolean, default true | On failure, upload a PNG of the tab and list it in `result.files`. |

| # | Step in the builder | action@version | type | inputs (beyond common) | output |
| --- | --- | --- | --- | --- | --- |
| 1 | Open URL | `browser.open@2` | READ | `url` (http/https only), `in`: `newSession`\|`newTab`\|`currentTab`, `waitUntil`: `load`\|`domcontentloaded`\|`networkidle`, `label`? | `{sessionId, tabId, url, title}` |
| 2 | Click | `browser.click@2` ● | UPDATE | `button`, `clickCount`, `modifiers`, `offset`?, `waitAfter`, `waitElement` | `{clicked, navigated, url}` |
| 3 | Hover | `browser.hover@1` ● | READ | `holdMs` | `{hovered}` |
| 4 | Set text | `browser.setText@1` ● | UPDATE | `text` or `secretRef`, `clearFirst`, `pressEnter` | `{set, value?}` |
| 5 | Get text | `browser.getText@1` ● | READ | `what`, `attribute` | `{text}` |
| 6 | Set checkbox | `browser.setCheckbox@1` ● | UPDATE | `state` | `{checked, changed}` |
| 7 | Get checkbox state | `browser.getCheckbox@1` ● | READ | none | `{checked}` |
| 8 | Select item | `browser.selectItem@1` ● | UPDATE | `by`, `items`, `multiple` | `{selected}` |
| 9 | Extract table data | `browser.extractTable@1` ● | READ | `headerRow`, `columns`?, `maxRows`, `nextPage`?, `maxPages`, `as` | `{columns, rows, rowCount, pages, file?}` |
| 10 | Select radio button | `browser.selectRadio@1` | UPDATE | `element` or `group` + `by` + `item` | `{selected}` |
| 11 | Get radio button state | `browser.getRadio@1` | READ | `element` or `group` | `{checked?, selected?}` |
| 12 | Scroll | `browser.scroll@1` | READ | `to`, `element`, `dx`, `dy`, `until`?, `maxScrolls` | `{scrollX, scrollY, atEnd}` |
| 13 | Send hotkeys | `browser.sendKeys@1` | UPDATE | `keys`, `element`? | `{sent}` |
| 14 | Type into | `browser.typeInto@1` ● | UPDATE | `text` or `secretRef`, `delayMs`, `clearFirst`, `pressEnter` | `{typed}` |
| 15 | Check if element exists | `browser.exists@1` ● | READ | `visibleOnly`; `timeoutMs` defaults to 3000 | `{exists, count, visible}` |
| 16 | Switch to | `browser.switchTo@1` | READ | `to`, `match`, `element` | `{tabId, url, title, frame?}` |
| 17 | Refresh page | `browser.refresh@1` | READ | `hard`, `waitUntil` | `{url, title}` |
| 18 | Get element selectors | `browser.getSelectors@1` | READ | `element`? | `{element, css, xpath, preview?, pagePreview?}` |
| 19 | Get web page details | `browser.pageInfo@1` | READ | `include` | page details and requested files |
| 20 | Go forward a page | `browser.forward@1` | READ | `waitUntil` | `{url, title, moved}` |
| 21 | Go back a page | `browser.back@1` | READ | `waitUntil` | `{url, title, moved}` |
| 22 | Close browser | `browser.close@1` | READ | `what` | `{closed}` |
| – | Wait (extra) | `browser.wait@1` | READ | `for` (`element`, `elementGone`, `navigation`, `time`, `text`, `textGone`, `url`), `ms`, `text`, `urlContains` | `{waitedMs}` |
| – | Handle pop-up dialogs (extra) | `browser.handleDialog@1` | UPDATE | `answer`: `accept`\|`dismiss`\|`off`, `promptText`?, `times`: `always`\|`once`; browser only | `{armed, frames, answered}` |
| – | Drag and drop (extra) | `browser.dragDrop@1` ● | UPDATE | `to` (locator bundle, same frame), `offset`?; browser only | `{dragged, dropped}` |
| – | Screenshot (extra) | `browser.screenshot@1` | READ | `element`?, `fullPage` | `{file}` |
| – | Download (exists, v1) | `browser.download@1` | CREATE | `url`? or `selector` / `element` | `{file, name, size}` |
| – | Pick element (design time) | `browser.pickElement@1` | READ | `session`? or `url`?, `prompt`? | `{element, preview, pagePreview?, url}` |
| – | Record (design time) | `browser.record@1` | READ | `recordingId`, `url`?, `session`?, `maxMinutes` | `{stepCount, stoppedBy}` |
| – | Run steps (fast replay, B9) | `browser.runSteps@1` | UPDATE | `session`?, `steps: [{key, action, version, input}]` (1–100), `stopOnError` (default true) | `{steps: {<key>: output}, failedAt?: key}` |
| – | Fill form | `browser.fillForm@1` | UPDATE | `fields: [{element, kind, value?, label?, required?, format?}]` (1–50); `kind` ∈ `text`, `select`, `checkbox`, `checkboxes`, `radio`, `date`; browser only | `{filled: [{label, kind}], count}` |
| – | Set date | `browser.setDate@1` ● | UPDATE | `value` (a date, or date and time, written any common way), `format`? (text fields only, e.g. `DD/MM/YYYY`), `pressEnter`; browser only | `{set, value?}` |
| – | Check items | `browser.checkItems@1` ● | UPDATE | `items` (labels or values), `only` (default true: untick the rest); `element` is the group or one box in it; browser only | `{checked, changed}` |
| – | List items | `browser.listItems@1` ● | READ | `max` (default 50); `element` is one item of the list; browser only | `{items: [{index, label, text}], count}` |
| – | Choose from a list | `browser.clickItem@1` ● | UPDATE | `item` (text, or position with `by: "index"`), `by`, `waitAfter`; `element` is one item of the list; browser only | `{clicked, item, navigated, url}` |

`browser.runSteps@1` runs its steps in order through the same action code as single commands,
sending `progress` `{percent, text}` per step. When a step fails it stops, sends `result`
`status: "failed"` with the outputs of the steps before it in `output.steps`, the failing step's key
in `output.failedAt` and its `output.error`. The server sends a batch only for a module made only of
web steps, after every step has passed its own guardrail; it never nests batches or includes
`browser.record` or `browser.pickElement`, and it checks each step against the device's declared
versions, blocked actions and allowed sites before sending. Batch steps carry no `session`; the batch's
own `session` (or the one a `browser.open` step in it opens) is used.

### Dynamic values, form filling and choices made at run time

Any input may be mapped from earlier steps (`{{ input.movie }}`); the server renders it before
sending. It also takes the shapes people's data comes in: `items` of Select item and Check items as a
list or as comma-separated text, Set checkbox's `state` from yes/no or true/false, a Fill form
field's value from text, yes/no or a list. An optional Fill form field whose value comes out empty
is left as the page has it.

`browser.fillForm@1` fills its fields in order through the same code as their own steps (Set text,
Select item by label, Set checkbox, Check items, Select radio button by label on the group, Set
date) and stops at the first field that fails, naming it.

`browser.setDate@1` reads ISO dates, `9 Nov 2026`, `Nov 9, 2026`, and numeric dates day first
(`09/11/2026` is 9 November; `11/25/2026` is read month first because 25 cannot be a month), with
an optional time (`18:30`, `6:30 PM`). A browser date field (`date`, `datetime-local`, `time`,
`month`, `week`) gets the form it takes; a text field gets `format` (tokens `YYYY YY MMMM MMM MM M
DD D HH H hh h mm ss A`), or ISO without one.

`browser.listItems@1` and `browser.clickItem@1` find a list from one recorded item: its repeated
siblings when the item is still on the page, otherwise every element its text-free selectors
(`testid`, `css` and `xpath`, each also with one position filter taken out) match. An item's
`label` is its aria-label or title, a heading in it, its first line with three letters, or an
image's alt text. `clickItem` matches `item` ignoring case, accents and punctuation: the same
label, then the same line, then a label starting with it, then one containing it as words. Two
equally good matches are never guessed between.

**Approval card.** When a web step stops for approval (its guardrail is Ask me, or Let Jizza decide
could not settle it), or a mandatory value came out empty (even when the guardrail allows the step),
the permission request's `context.form` lists the step's values as they resolved:
`{action, title, fields: [{key, label, type, value?, required, missing, source?, options?}],
missing: [key], choicesAt?, data: [{path, value}]}`. `type` ∈ `text`, `number`, `checkbox`,
`select`, `list`, `date`; `source` is the mapping the value came from; `data` holds the run's
values so far, to map an empty field from. Approving
(`POST /api/notifications/{id}/answer`, `kind: "APPROVE"`) may send `inputs` keyed like the fields
(`"text"`, `"item"`, `"fields.2.value"`); the step applies only keys its card offered, as plain values
that are never evaluated. Approving without a value for each `missing` key is refused (400).

**Choice made at run time** (`browser.clickItem@1` by text). The step first sends
`browser.listItems@1` for the same element and session, then:

- **Ask me**: asks, with `item` as a `select` of the labels the page lists now (the mapped value
  preselected when the page lists it). After the answer the list is read again, so the pick is
  still on the page when it is clicked.
- **Let Jizza decide** or **Auto**: clicks the listed item the run asked for; when the page does not
  list it (or the value is empty), asks as above.
- **Never**: fails.

A Choose from a list step never runs inside a fast-replay batch.

Every action that acts on an `element` also takes `highlight` (boolean, default false): the
browser outlines the element for a moment before acting, for a person watching the run.

Deadlines: the server's command `deadline` already includes `timeoutMs`; always finish before it.
Navigation actions wait at most `min(deadline, 60 s)` for `waitUntil`.

### Failures

A failed action sends `result` `status: "failed"`, `code: "failed"` (or `timed_out`, `cancelled`,
`permission_missing` when those apply), a short human `message`, and `output.error`:

```json
{"error": {"kind": "element_not_found", "detail": "No match for 4 strategies after 15000 ms",
           "candidates": [{"element": {}, "score": 0.81, "text": "Submit order"}], "url": "https://…"}}
```

`kind` ∈ `element_not_found`, `element_ambiguous`, `not_actionable`, `navigation_failed`,
`no_session`, `site_not_allowed`, `frame_not_found`, `secret_missing`, `bad_input`,
`page_crashed`. Include up to 5 `candidates` for `element_not_found`. Never put typed text or page
HTML in `message`.

### Locator bundle

```json
{"frames": [{"strategies": [{"by": "css", "value": "iframe#payment"}]}],
 "shadow": [{"by": "css", "value": "checkout-form"}],
 "strategies": [
   {"by": "testid", "value": "submit-order"},
   {"by": "role", "role": "button", "name": "Submit order"},
   {"by": "label", "value": "Invoice number"},
   {"by": "css", "value": "form#order button[type=submit]"},
   {"by": "text", "value": "Submit order", "exact": true},
   {"by": "xpath", "value": "//form[@id='order']//button[2]"}],
 "fingerprint": {"tag": "button", "text": "Submit order", "attrs": {"type": "submit", "name": "save", "class": "btn primary"},
                 "rect": [812, 640, 96, 36], "nearText": "Total: $120.00", "index": 2},
 "label": "Submit order button"}
```

**Building** a bundle (picker, recorder, `getSelectors`): produce strategies in this priority and
keep only those that match exactly this element right now: `testid` (`data-testid`, `data-test`,
`data-qa`, `data-cy`), `id` (as css `#id`, skipped if it looks generated: digits ≥ 4 in a row,
UUID-like, or common framework prefixes like `ember`, `react-select-`, `mui-`),
`role`+accessible name (compute the accessible name per WAI-ARIA accname: `aria-labelledby`,
`aria-label`, `<label>`, `alt`, `title`, text content), `label` (form fields by their label text),
`placeholder`, `name` attribute, `near` (a form field by the words just before it, such as
"From" beside its box, when no label is tied to it), a short unique CSS path (prefer stable
attributes, avoid `:nth-child` unless needed, max 5 levels), `text` (only for elements with short
visible text), XPath last. Attribute ways are sent as `css`: steady `data-*` attributes and
Angular `formcontrolname` right after the id, then `title`, `alt` and a link's `href` after
`name`. At most 12 strategies.

**Patterns** for lists, rows and cards (`itemCss`, `titleCss`, `rowCss`, `within.itemCss`) may
carry backups: CSS selectors joined by ` || `, best first, each of which found the same elements
when recorded. The first one that finds anything on the page is used.

**Reporting the way** (since browser agent 0.28): a successful step whose element was found by a
strategy other than its first, or picked by the fingerprint among several matches, adds
`output._located: [{at, strategy, byFingerprint?}]`. The server then offers the person a fix that
moves that strategy first. `frames` is the chain of iframes from the top document (each with its own strategies);
`shadow` the chain of open shadow hosts. `label` is a short human description for the builder.

**Resolving** a bundle: switch into each frame (cross-origin frames: run the content script in that
frame via `chrome.scripting` with `frameIds` from `webNavigation.getAllFrames`; match the iframe
element's bundle in the parent, then find the child frame whose `frameId` belongs to it), pierce
each shadow host, then try strategies in order. The first strategy that matches **exactly one**
element wins. If a strategy matches several, use the fingerprint to choose; if two score within
0.05 of each other, keep trying other strategies; if nothing settles it, fail
`element_ambiguous`. If none match, poll every 100 ms (plus a `MutationObserver`) until
`timeoutMs`. After the timeout, score every element of the same tag (and same role) with the
fingerprint (text similarity 0.4, attributes 0.3, position 0.15, near text 0.15), and fail
`element_not_found` with the top 5 as `candidates`. **Never act on a candidate yourself.** The
server decides whether a candidate becomes the new target.

**Actionable** before acting (like Playwright's actionability checks): attached, visible (non-zero
box, not `visibility:hidden`, not `display:none`, opacity > 0), enabled (for form controls), stable
(same box for two animation frames), receives events (the centre point's `elementFromPoint` is the
element or inside it; if covered, scroll it into view and retry; still covered →
`not_actionable` with "covered by <tag.class>"). Scroll into view (`block: "center"`) before every
pointer action.

### A web step as the server stores it

```json
{"id": "click_submit", "type": "DEVICE_ACTION", "label": "Click \"Submit order\"",
 "config": {"preset": "web", "action": "browser.click", "version": 2, "target": "sameBrowser",
            "inputs": {"element": {"strategies": [{"by": "role", "role": "button", "name": "Submit order"}],
                                   "fingerprint": {"tag": "button", "text": "Submit order"}, "label": "Submit order button"},
                       "waitAfter": "navigation"},
            "timeoutSec": 60}}
```

`config.target` is the device selector. The page element is always `config.inputs.element`.
Template values are rendered by the server before the command is sent.

### One command and its result, end to end

What the extension receives for that step (envelope trimmed; `sig` covers `body`):

```json
{"v": 1, "type": "command", "id": "01JBC4…", "deviceId": "7f3e9c42-…", "ts": "2026-10-28T09:00:00Z", "replyTo": null,
 "body": {"commandId": "5d0c…", "deviceId": "7f3e9c42-…", "action": "browser.click", "version": 2,
          "input": {"session": "s_k3j9x2qa", "element": {}, "waitAfter": "navigation"},
          "issuedAt": "2026-10-28T09:00:00Z", "deadline": "2026-10-28T09:01:00Z",
          "nonce": "b6f1…", "idempotencyKey": "run-uuid:click_submit:1"},
 "sig": "…"}
```

The server fills `input.session` from the run's browser session; a step built by hand never needs
to set it. The extension answers `accepted`, then:

```json
{"v": 1, "type": "result", "id": "dev-uuid-1", "deviceId": "7f3e9c42-…", "ts": "…", "replyTo": null,
 "body": {"commandId": "5d0c…", "status": "succeeded",
          "output": {"clicked": true, "navigated": true, "url": "https://portal.example.com/orders/2041"}}}
```

or, when the button is gone:

```json
{"body": {"commandId": "5d0c…", "status": "failed", "code": "failed", "message": "Could not find Submit order button",
          "output": {"error": {"kind": "element_not_found", "detail": "No match for 1 strategy after 15000 ms",
                               "candidates": [{"element": {}, "score": 0.88, "text": "Place order"}],
                               "url": "https://portal.example.com/cart"}},
          "files": ["e1b2…"]}}
```

### Recording events (extension → server)

Recording uses protocol `event` messages named `browser.recording.step`,
`browser.recording.edit`, and `browser.recording.stopped`. A step contains `recordingId`, a
monotonically increasing `seq`, action and version, input, label, and metadata. Sensitive steps
send `secretRef: null` and never send the value. Events may arrive out of order; sort by `seq` and
ignore a `seq` already stored.

`meta.preview` is the element cropped (PNG). Newer extensions also send `meta.pagePreview`, the
visible page with the element outlined (JPEG, at most 1280 px wide), from the same capture with
sensitive fields blurred; `browser.pickElement@1` and `browser.getSelectors@1` return it as
`pagePreview`. Neither is sent for a sensitive step.

```json
{"name": "browser.recording.step",
 "data": {"recordingId": "9a7c…", "step": {"seq": 4, "action": "browser.setText", "version": 1,
          "input": {"element": {}, "text": "INV-2041", "clearFirst": true},
          "label": "Set \"Invoice number\" to \"INV-2041\"",
          "meta": {"ts": "2026-10-28T09:03:12Z", "url": "https://portal.example.com/invoices", "title": "Invoices",
                   "frameUrl": null, "preview": "c41d…", "sensitive": false, "truncated": false}}}}
```

```json
{"name": "browser.recording.edit", "data": {"recordingId": "9a7c…", "op": "delete", "seq": 4}}
{"name": "browser.recording.edit", "data": {"recordingId": "9a7c…", "op": "move", "seq": 6, "toIndex": 2}}
{"name": "browser.recording.edit", "data": {"recordingId": "9a7c…", "op": "relabel", "seq": 4, "label": "Invoice number"}}
{"name": "browser.recording.edit", "data": {"recordingId": "9a7c…", "op": "map", "seq": 4, "field": "text", "input": {"name": "invoiceNo", "type": "text"}}}
{"name": "browser.recording.stopped", "data": {"recordingId": "9a7c…", "stepCount": 11, "stoppedBy": "person"}}
```

The side panel may send `delete`, `move`, `relabel` and `map` (turn a typed value into a module
input; the value becomes its default). The server refuses `map` on a sensitive step, and choosing
a saved login (`secret`) is only done by the person in Jizza.

### Editing a saved recording

Jizza's **Edit** on a saved recording (`POST /api/web/recordings/edit` with the workflow, the
browser and test values) reads the workflow's graph back into steps and opens a new recording
linked to it (`web_recordings.source_workflow_id`, V43). The steps go to the browser as a file the
server hands it (`GET /agent/v1/files/{id}`, no command attached), each with the test values in
place of `{{ input.X }}` and `mapped` naming the input, and `browser.record@1` gets
`mapFirst: false` and `edit: {"stepsFile": "<file id>", "stepCount": 6, "name": "Book a movie"}`.

Conditions are marker steps in the list: `flow.if` (`input.element`, `timeoutMs`, `when`:
`shown` or `missing`) and `flow.endIf`. They compile to a `browser.exists` step and a branch whose
true edge runs the steps inside. `flow.keep` carries a step made in the builder unchanged. Steps
may be `disabled` (not run) or `optional` (completes as skipped when the element is not on the
page). While editing, the extension may send:

```json
{"name": "browser.recording.step", "data": {"recordingId": "9a7c…", "after": 3, "step": {"seq": 7, "action": "browser.click", "version": 2, "input": {}}}}
{"name": "browser.recording.step", "data": {"recordingId": "9a7c…", "after": 2, "step": {"seq": 8, "action": "flow.if", "version": 1, "input": {"element": {}, "timeoutMs": 3000, "when": "shown"}}}}
{"name": "browser.recording.edit", "data": {"recordingId": "9a7c…", "op": "update", "seq": 3, "patch": {"optional": true}}}
```

A `flow.else` between them starts the steps that run when the condition does not hold (the
branch gets `config.else: true`, and its false edge goes to those steps). Conditions may also be
recorded the first time, appended like any step. `browser.recording.stopped` with
`"discarded": true` (Cancel in the side panel) discards the recording.

`after` is the seq of the step the new one goes after (`-1` for the start). `update` takes a
`patch` of `disabled`, `optional`, `timeoutMs`, `when`, `element` or `value: {"field", "value"}`.
Saving gives the workflow a new version (drafts only; `asNew: true` saves a new workflow instead)
and keeps node ids, so data mapping to earlier steps still works. The save result lists the
workflows that run it in a Run a module or For each step; `POST
/api/web/recordings/linked/{id}/update` fits their inputs to the recording's.

### Desktop recordings (Windows agent → server)

The Jizza Windows agent records in Windows apps with a hotkey (Ctrl+Alt+R by default). The PC
starts these recordings itself, so the events use their own names and the first one creates the
recording:

```json
{"name": "desktop.recording.started", "data": {"recordingId": "<uuid chosen by the PC>", "name": "Notepad task 5 Nov 16:59", "app": "Notepad"}}
{"name": "desktop.recording.step",
 "data": {"recordingId": "…", "step": {"seq": 1, "action": "ui.type", "version": 1,
          "input": {"app": "notepad", "element": "Edit:File name", "text": "notes.txt"},
          "label": "Type into \"File name\"",
          "meta": {"app": "Notepad", "window": "Save as", "sensitive": false}}}}
{"name": "desktop.recording.stopped", "data": {"recordingId": "…", "stepCount": 4, "stoppedBy": "person"}}
```

- `started` is handled before the event is acked, so the recording exists before the PC's first
  step is read. A repeated `started` (a resend) changes nothing. The device must declare
  `ui.record`; a second recording on the same PC sends the first one to review.
- The server then sends a `ui.record` command with `{"recordingId", "attach": true}`. The PC's
  command waits until the person stops recording; cancelling it (Stop on the Recordings page)
  stops the recording on the PC. When the command finishes, the recording goes to review as a
  browser recording would.
- Steps are `ui.openApp`, `ui.click`, `ui.type` and `ui.sendKeys` (SendKeys notation: `{ENTER}`,
  `^s`). A password box sends `meta.sensitive: true` and no `text`; before saving, the person names
  a secret saved on the PC (Credential Manager, `Jizza:<name>`) as its `secretRef`.
- Recordings keep `kind = desktop` and the starting `app` (V39); there is no start URL. Saving
  compiles plain `DEVICE_ACTION` steps targeted at that PC: no Open URL or Close browser steps.

### Guardrail types

`READ` actions never change data on a site; `UPDATE` actions can. The server judges every web step
through the existing guardrails. A saved recording's type is the most consequential type among its
steps.

#### More run-time choices (0.3.0)

- `browser.clickItem@1` / `browser.listItems@1` take `within: { element, item }` (choose inside
  one card of another list first: the server lists that outer list, matches `item`, then lists
  inside the matched card) and `loadMore: { rounds, button? }`. `clickItem` takes `prefer`
  (`ask` | `first` | `lowestPrice` | `highestPrice`) and its `item` is optional.
- The server's matching (`ItemMatch`): exact name, a name that starts with it, then every
  comma-separated term in the card's name or text; a date (ISO, day-first numbers, words, "today",
  "tomorrow") finds its day in a strip; ties ask with the tied items first unless `prefer` settles
  them by the price in the text. Two cards with the same name are offered as "Name · rest of text"
  and clicked `by: "index"`.
- `browser.pickDate@1` (a calendar the site draws) and `browser.chooseOption@1` (type and choose a
  suggestion). An `item_not_found` failure of `clickItem` or `chooseOption` with `error.items`
  turns into an approval with those items to choose from (`error.within` for the outer card).
- `browser.fillForm@1` kinds `datepicker` and `combobox`; `submit` presses the send button and the
  output's `errors: [{ field?, label?, message }]` come back to the approval card as each field's
  `error`, with a message; the person's fixes are filled and sent again (at most 3 rounds).
- The approval card offers `_remember` (a checkbox) for a pick from a list. Ticked, the pick is kept
  in the person's workflow storage under `jizza.picks.<workflowId>.<stepId>`, by the folded wording
  asked for; a later run that asks for the same wording takes it without asking while the page lists it.
- Answers from the phone apps carry `inputs` too (`POST /agent/v1/me/notifications/{id}/answer`
  and `/always-allow`), checked the same way.
- `ui.fillForm@1`, `ui.listItems@1` and `ui.clickItem@1` do the same in Windows apps through UI
  Automation, with the same approval card and choice at run time.
- A workflow's inputs, as chat sees them, include every `{{ input.name }}` its steps read, so a run
  started from chat can pass the movie and date the person named.

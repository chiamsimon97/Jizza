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

`browser.runSteps@1` runs its steps in order through the same action code as single commands,
sending `progress` `{percent, text}` per step. When a step fails it stops, sends `result`
`status: "failed"` with the outputs of the steps before it in `output.steps`, the failing step's key
in `output.failedAt` and its `output.error`. The server sends a batch only for a module made only of
web steps, after every step has passed its own guardrail; it never nests batches or includes
`browser.record` or `browser.pickElement`, and it checks each step against the device's declared
versions, blocked actions and allowed sites before sending. Batch steps carry no `session`; the batch's
own `session` (or the one a `browser.open` step in it opens) is used.

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
`placeholder`, `name` attribute, a short unique CSS path (prefer stable attributes, avoid
`:nth-child` unless needed, max 5 levels), `text` (only for elements with short visible text),
XPath last. `frames` is the chain of iframes from the top document (each with its own strategies);
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
{"name": "browser.recording.stopped", "data": {"recordingId": "9a7c…", "stepCount": 11, "stoppedBy": "person"}}
```

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

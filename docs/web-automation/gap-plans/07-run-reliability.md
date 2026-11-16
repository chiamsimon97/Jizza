# 07. Run reliability and recovery

## Status (2026-11-16, round 3)

Built: G1 + G8 (`browser.waitFor@1`: up to 8 named outcomes, each an element shown or gone, text
shown or gone, or part of the address; the first one the page shows is the step's `outcome`
output. Each outcome's rule is kept on the node as `onOutcome`: carry on, or stop the run with a
message, which becomes the usual failed-step card. Recorder tool "Wait for what happens…" points
at the first outcome; more text outcomes and their rules are edited in the recording review. A
builder Branch can read `nodes.<step>.outcome`.) G2 (auto-settle: before each element step the
browser waits, at most 10 s and half the step's time, until the page has loaded and no big
loader overlay or indeterminate spinner covers it; generic signals only, `settle: false` turns it
off). G5 (steps that cannot be undone: the recorder marks clicks on Pay / Place order / Confirm
booking / Buy now and the like, and the recording review has a toggle. Such a step is never
batched in fast replay, never retried automatically, a lost command is not resent, its card
offers "I checked, it did not go through: try again" instead of Try again, a plain Try again or a
written note is refused, and no-answer never retries.)

Not built yet: G3 session guard, G4 checkpoints, G6 automatic pop-up following, G7 frames matrix,
G9 run-time self-healing, G10 politeness (plan 14), G11 waiting-for-browser state.

## What exists today

Failed-step cards (Try again / Skip / Stop) and resume after commit (#84, #91); locator bundles
with fingerprint and locator-fix suggestions the person approves (`LocatorFixService`); waits
(`browser.wait` time/element), `waitElement` options, Only-if-shown conditions, site-error
reading on forms (`form-errors.ts`), failure screenshots, guardrails (`WebGuardrails`).

## Gaps

| # | Gap | Scenario |
|---|-----|----------|
| G1 | **Smart waits**: no "wait until one of several outcomes" (results list OR "No trains found" OR error banner) with a branch per outcome. Fixed waits are slow or flaky. | S1, S4 |
| G2 | **Loaders/spinners**: no automatic wait for overlays, network idle, or a site's known spinner before acting. | S1 |
| G3 | **Session expired / logged out** mid-run: no detection, no re-login, no resume from a checkpoint. | S1, S5 |
| G4 | **Checkpoints**: a run restarted after failure starts from step 1; long flows need "resume from the passenger page" when the page is still there, or from the last safe checkpoint. | S1, S7 |
| G5 | **Idempotency** for irreversible actions: a retried run (or Try again) may place a second order / book twice. | S1, S4 |
| G6 | **New windows and pop-ups**: payment windows, `window.open` flows, OAuth pop-ups. Tab-group join exists; switching is a step but not automatic for "the window this click opens". | S1, S5 |
| G7 | **Cross-origin iframes** (payment, captcha, embedded forms): allowed frames are read, but recording/replay inside third-party frames is limited and not explained. | S1, S5 |
| G8 | **Site messages as outcomes**: "Coupon not valid", "Seats not available", "Server busy, try again" should map to retry/branch/fail with a reason, not a generic failure. | S1, S4 |
| G9 | **Run-time self-healing**: when a locator fails, the run asks the person; there's no automatic retry with the next strategy + text match with a confidence threshold, logging the fix for review. | all |
| G10 | **Rate and politeness**: no per-site minimum delay between actions or max runs per hour. | S2, S9 |
| G11 | **Browser closed / tab closed / computer asleep** during a run: no clean "paused, waiting for browser" state with resume. | all |

## Proposed design

1. **`browser.waitFor@1` with outcomes** (G1, G8): a list of outcomes, each a condition
   (element shown, text shown, URL matches, element gone), with a name; compiled to a BRANCH on
   the outcome name. Recorder: after a click that navigates or loads, the panel offers "What can
   happen next?" and records the current outcome; others can be added later (plan 04 insert).
2. **Auto-settle** before each action (G2): wait for document ready, no visible full-screen
   overlay/spinner (common patterns + per-site spinner selectors recorded once), and the target
   element stable for 200 ms; capped by the step timeout. On by default.
3. **Session guard** (G3): per recording, a "signed-out" signature (login button visible, URL to
   login page) learned during recording. When it appears mid-run: run the recording's **login
   section** (steps tagged as login, plan 12 sections) and resume at the last checkpoint.
4. **Checkpoints** (G4): steps that land on a new page are checkpoints; the run stores the
   checkpoint index + URL. Try again after failure offers "From here" (if the page is still
   right) or "From the last checkpoint" (navigates to its URL if it's directly reachable) or
   "From the start".
5. **Irreversible steps** (G5): the person (or the recorder heuristically: Pay, Place order,
   Book, Submit final) marks a step *irreversible*. The engine records an idempotency key
   (run id + node) before it; Try again past an irreversible step is blocked unless the person
   confirms "I checked, it did not go through". Combined with plan 13 dry run.
6. **Pop-up following** (G6): a click that opens a new tab/window is recorded with
   `opensWindow: true`; at run time the next steps automatically run in the new window, and a
   `closes window` event returns to the opener. Already-captured `openerTabId` helps.
7. **Frames** (G7): document the support matrix; for payment iframes rely on hand-off (plan 06);
   for others, record the frame path (URL pattern + index) in the locator bundle.
8. **Site message rules** (G8): per recording, a table of page messages → action (retry after N
   s up to M times / branch / fail with this reason / ask the person). Recorder captures any
   toast/alert seen during recording as candidate messages.
9. **Self-healing** (G9): on locator miss, try remaining strategies, then text+role match with
   fingerprint score ≥ threshold; act only if one candidate clearly wins, record a pending
   locator fix for the person to approve (reuses `web_locator_fixes`). Never self-heal on
   irreversible steps.
10. **Politeness** (G10): per-site min delay and max runs/hour in site rules (plan 14).
11. **Browser gone** (G11): run state `waiting_for_device`, card "Open Chrome to continue", resume
    from checkpoint when the device reconnects; timeout configurable.

## Acceptance checks

- IRCTC search with no trains takes the "No trains" branch and reports it in chat within 2 s of
  the message appearing.
- Logging out mid-run (manually) triggers re-login and continues on the passenger page.
- Try again after a failed Pay step requires explicit confirmation.
- A locator broken by a class rename heals with a fix suggestion visible in Recordings.

## Size

L, can ship in slices: waitFor + auto-settle (3 days), checkpoints + session guard (3 days),
irreversible + idempotency (2 days), pop-ups (2 days), self-heal (2 days).

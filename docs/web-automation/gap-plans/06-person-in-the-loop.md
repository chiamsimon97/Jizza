# 06. Person in the loop: captcha, OTP, payment, seat maps and live view

> During booking only we know some data; it must be asked through custom notification UI with
> input data, screenshots etc., based on configuration.

## Status (2026-11-17, round 3)

Built in round 2: G1 (CAPTCHA and code cards answered from Jizza), G2 (picture + answer box),
G3 ("Ask me", `browser.ask@1`), G4 ("Hand over to me", `browser.handOff@1`; payment boxes are
never filled), G5 (cards end when the page's time runs out, with a countdown).

Built in round 3:
- G7 seat maps as **"Tap on a picture"** (`browser.tapPoints@1`). In the recording review, a
  click gets "Let me tap on a picture of this each run". At run time, the server takes a picture
  of that element (`browser.screenshot` with the element), and the step's card shows it. The
  person taps up to 20 places, which are kept as fractions of the picture's width and height.
  The browser clicks the same places on the element as it is now, finding it again before each
  tap with a 250 ms pause between taps. This step is never batched in fast replay.
- G8 **live view**: "Watch the browser" on a run followed in chat. It shows a fresh picture of
  the run's tab every 4 seconds (`POST /api/runs/{id}/live-view`, a JPEG screenshot that wakes
  nothing in the run). Take over uses what exists: **Pause automation** in the extension makes
  the browser refuse the next step, the person acts in the tab, then **Resume automation** and
  **Try again** or **Skip** on the step's card. There is no separate engine pause.

Not built yet: G6 keep-alive while waiting, G9 merging several asks on one page into one card,
G10 choosing where to ask, G11 "ask me above an amount" (`ui.confirmIf`), the recorder
suggesting "Ask me" when it sees a CAPTCHA, and capturing what the person did during a take-over
as a suggested fix (plan 13).

## What exists today

- Step cards (`PERMISSION_REQUEST` notifications) with a details form (`config.details.blocks`,
  Submit/Cancel, answers as a `details` output), per-item screenshots (`config.screenshots`),
  editable resolved values, list choices, mandatory inputs, rows tables (PRs #95/#96, #114, #115).
- Local "needs the person" pause: the extension detects captcha / OTP / verification / login
  (`src/content/dom/needs-user.ts`), shows a Chrome notification and side panel *Done,
  continue*. The person must be **at that browser**.
- Notification expiry with a default effect; snooze.

## Gaps

| # | Gap | Scenario |
|---|-----|----------|
| G1 | Captcha/OTP can only be handled at the browser; the person in chat or on another device can't answer. | S1, S5 |
| G2 | No card block for **"picture of a page area + answer box"** (crop of the captcha image, typed answer goes into a named field). | S1 |
| G3 | No **"ask the person" step** you can place in a recording (plan 04 menu): today cards come only from approval of web steps. | all |
| G4 | No **payment hand-off**: the run doesn't know how to wait while the person pays in the tab and resume on the confirmation page. | S1, S3, S4, S6 |
| G5 | Card deadline isn't tied to **page timeouts** (IRCTC passenger page, payment page 10–15 min); an answer after logout fails. | S1 |
| G6 | No **keep-alive** while waiting (harmless activity to avoid idle logout) and no warning "you have 2 minutes". | S1, S5 |
| G7 | **Seat maps** (cinema, flights, bus): no way to show the map and let the person tap seats, or to apply seat rules. | S3, S8 |
| G8 | No **live view / take over**: the person can't watch the run or take control from the web app and hand back. | all |
| G9 | Cards are per step; several quick questions on one page create a burst of cards. | S7 |
| G10 | No configuration for **who/where to ask**: chat thread that started the run, inbox, push to phone (mobile plan-only), email. | all |
| G11 | No **confirmation threshold** card: "Ask me before paying more than ₹3,000" with the fare read from the page. | S1, S4 |

## Proposed design

### A. New card blocks (web + server `WebStepForm`)

| Block | Purpose |
|-------|---------|
| `image` | A screenshot or element crop (captcha, fare summary, seat map). Crop taken at ask time by the extension (`browser.screenshot` with element target), stored as an encrypted device file, short-lived. |
| `answer` | Input bound to a page field: typed into that field when submitted (captcha box, OTP box). Type `secret` by default (plan 05). |
| `tapImage` | Image the person taps on; tap coordinates (relative) go back and the extension clicks them (seat maps, image captchas the person solves themself). |
| `countdown` | Time left before the page times out. |
| `values` | Read-only values read from the page (train, fare, PNR) for confirmation. |

### B. "Ask the person" step (recording + builder)

`ui.ask@1` with blocks from A, inputs it produces, timeout, timeout effect (stop / skip / use
default), and **where to ask** (G10). Insertable anywhere (plan 04). The recorder suggests it
automatically when it detects a captcha/OTP on the page during recording: "This page has a
captcha. Ask me for it on each run?" → inserts `ui.ask` with image crop of the captcha + answer
bound to the captcha box.

### C. Remote needs-user (G1)

When the extension's needs-user detector fires during a run, besides the local notification it
sends `browser.needsUser { kind, crop?, field? }`; the server raises a card with the crop and an
answer box (captcha/OTP) or a *Done, I did it in the browser* button (verification, login).
Whichever answer comes first wins (card or side panel).

### D. Payment hand-off (G4)

`browser.handOff@1` step: message to the person ("Pay ₹2,345 in the IRCTC tab"), the tab is
brought to front and pinned; the run waits until **one of**: URL matches a success pattern,
text "Booking confirmed" appears, a failure pattern, or timeout. Recording: the recorder
proposes hand-off when it sees a payment page (known gateway hosts, card number fields) and
stops recording values there. Nothing on payment pages is ever recorded or typed by Jizza
(decision 2 in README).

### E. Page timeouts and keep-alive (G5, G6)

- Recording captures session timers where visible (countdown text) and the person can set
  "this page logs out after N min" per page group.
- Card `expiresAt` = min(page timeout − safety margin, workflow timeout); countdown block shown.
- Optional keep-alive per site: a harmless action every N seconds while waiting (move focus,
  `scroll 0`), off by default; site rules (plan 14) decide.
- If the page timed out anyway: plan 07 recovery (re-login and resume from the last checkpoint).

### F. Live view and take over (G8)

- Live view: the run page shows the latest screenshot of the tab every few seconds while a run
  waits or on request (extension already captures screenshots; send low-res, encrypted, not kept).
- Take over: *I'll do it* button pauses the run, the person acts in the browser, *Continue from
  here* resumes; the recorder can optionally capture what they did as a suggested fix (plan 13).

### G. Batching and routing (G9, G10)

- Consecutive asks on the same page within a short window are merged into one card (plan 02 B).
- Routing per workflow: ask in the chat thread that started the run (card inline in chat),
  inbox always, and push for urgent ones (captcha/OTP with < 3 min). Mobile push exists for
  notifications already; rich captcha cards on the phone are plan-only (plan 15).

### H. Threshold confirmations (G11)

`ui.confirmIf`: read a value (fare) → if above the person's limit (input or profile setting) ask
with `values` + `image`; else continue. Uses plan 12 typed extraction.

## Acceptance checks

1. IRCTC run from chat: the captcha appears inside the chat as a picture with a box; typed
   answer is entered and login continues, without touching the browser.
2. OTP card expires in the time the page allows and shows a countdown.
3. Payment: the run stops at the gateway, the person pays in the tab, the run continues on the
   confirmation page and reads the PNR.
4. BookMyShow seat map: the card shows the map; tapping two seats selects them on the page.
5. Live view shows the tab while a card waits; *I'll do it* / *Continue from here* works.

## Size

L. Card blocks + ask step (4 days), remote needs-user + crops (2 days), hand-off (2 days), live
view/take over (3 days), seat map tap (2 days).

## Questions for Chiam

1. Payment: hand-off only (recommended), or should Jizza ever fill payment details?
2. Should the captcha card also appear as a push notification on the phone (when the mobile app
   comes back into scope)?

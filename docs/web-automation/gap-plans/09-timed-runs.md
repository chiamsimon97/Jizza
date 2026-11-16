# 09. Timed runs: Tatkal, flash sales, slot polling

## Status (2026-11-16, round 3)

Built: G1 + G2 (`browser.waitUntil@1`, "Wait until": `at` with an offset, or a local time with
`timeZone`; the run sleeps on the server until two minutes before, then the browser reads the
site's clock from its `Date` header (median of 5, half-second resolution, error noted), waits
out the rest itself with a tight last 200 ms, and reports `offsetMs` and `lateMs`. `clock:
device` uses the computer's clock; `leadMs` goes on early. A moment already gone stops the run
with why.) G3 (`poll` on "Wait for what happens": `everySec` (15 s or more), `forMinutes` or
`until`, `maxTries`; each look after the first reloads the page (`reloadFirst`), the run sleeps
between looks, and stops with how many looks it made. Set in the recording review: "If none
shows, keep looking".) G6 partly: a browser that is not open when the step is due pauses the run
with "open it, then Try again" (plan 07 G11).

Not built yet: G4/G5 arming a timed run from chat with pre-flight checks and an armed card,
readiness alerts ten minutes before, reading a clock element shown on the page.

## Scenarios

- IRCTC Tatkal: AC classes open at 10:00, non-AC at 11:00 IST; seats go in a minute or two.
- Flash sale at 12:00; vaccination/visa slot that appears at random; check-in opening 48 h before
  departure; "reorder when price drops below ₹X".

## What exists today

Scheduler triggers (cron/one-off) start a workflow; Wait step; workflows can loop with For each.

## Gaps

| # | Gap |
|---|-----|
| G1 | No **two-phase run**: prepare (log in, fill journey, passengers ready) well before, then **fire at an exact time**. |
| G2 | No **clock accuracy**: the extension's clock vs the site's clock (sites show server time; a few seconds of skew loses Tatkal). |
| G3 | No **poll until** pattern: repeat check every N seconds/minutes until a condition (slot available, price below), with a stop time and politeness limits. |
| G4 | Prepared inputs: at 10:00 there is no time to ask; all inputs (incl. passengers, train choice rule) must be complete and validated before arming. |
| G5 | No **pre-flight** at arm time: browser paired and online, logged in, site reachable, saved login works. |
| G6 | Computer asleep / browser closed at fire time: no wake or early warning. |

## Proposed design

1. **`flow.waitUntil@1`** step with `at` (absolute time, time zone), `clock: site | device |
   server`, `lead` (ms to fire early to cover latency). The run executes steps up to it, waits,
   then continues. Precision: the extension holds the final wait locally (setTimeout + a short
   busy-wait in the last 200 ms) so network latency to the server doesn't add delay.
2. **Clock sync** (G2): at arm time the extension measures offset to the site from the `Date`
   response header of a HEAD request (median of 5), or reads a visible site clock element if
   recorded; offset applied to `waitUntil`.
3. **`flow.pollUntil@1`** (G3): body steps + condition + `every` + `until` + `maxTries`; uses
   site rules (plan 14) for minimum interval; notifies on success with plan 08 results.
4. **Arm a timed run** (G4, G5): from chat ("book Tatkal tomorrow 10 AM") Jizza fills the inputs,
   asks for anything missing **now**, validates (plan 02), runs pre-flight (G5), and shows an
   armed card: "Armed for 10:00:00 IST, starts preparing at 09:56". Train choice uses rules
   ("first with AVAILABLE in 3A, departure 18:00–23:00") since there's no time to ask; card
   asks only for captcha (plan 06), which the person must be ready for.
5. **Readiness alerts** (G6): 10 min before preparing, check device online; if not, push/email
   "Open Chrome on your Mac for the Tatkal booking".

## Acceptance checks

- Armed run fires within 300 ms of the site's 10:00:00 in a test page with a server clock.
- Poll-until on a test page stops when the item appears and posts the result in chat.
- Pre-flight catches "not logged in to Chrome extension" an hour before.

## Size

M. waitUntil + clock sync (2 days), pollUntil (1–2 days), arm flow + pre-flight + alerts (2 days).

## Note

IRCTC's rules on automated Tatkal booking must be checked before we promote this use (plan 14).

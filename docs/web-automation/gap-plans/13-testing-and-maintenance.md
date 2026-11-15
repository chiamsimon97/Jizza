# 13. Testing, dry runs and keeping recordings working as sites change

## What exists today

Locator bundles (several strategies + fingerprint), locator fixes suggested and approved
(`LocatorFixService`, `FixRanker`), failure screenshots, Edit recording step-through, extension
unit/e2e tests with a fake gateway, server E2E tests with a stub device.

## Gaps

| # | Gap |
|---|-----|
| G1 | No **dry-run mode**: test a booking without paying/submitting. Irreversible steps (plan 07) run for real. |
| G2 | No **health checks**: a recording breaks when the site changes, and the person learns at the worst time (Tatkal at 10:00). |
| G3 | **Fix suggestions** need the person to approve one by one; no batch view per recording ("5 steps changed on irctc.co.in this week"). |
| G4 | No **recorded-run replay for debugging**: which step, what the page looked like, what was typed (masked). |
| G5 | No **regression fixtures** for our own code: real-site behaviours (IRCTC autocomplete, BMS seat map, date pickers) aren't captured as offline test pages, so recorder changes can break them unnoticed. |
| G6 | No **site-change telemetry**: the same site breaking for many users (once shared recordings exist) isn't visible. |

## Proposed design

1. **Dry run** (G1): run option `dryRun: true`: irreversible steps are not performed; the run
   stops before them with a screenshot and "Would click *Pay*". New recordings start untrusted:
   their first run is dry by default until the person presses *Mark as trusted* (README
   decision 5).
2. **Health check** (G2): scheduled, per recording (e.g. daily, and 1 h before an armed timed
   run): run the non-irreversible prefix up to the first step that needs login or secrets, or a
   *locator check* that only resolves each step's element on reachable pages without acting.
   Report: green / changed (healed) / broken, with a card for broken ones.
3. **Fix review** (G3): Recordings page tab *Needs attention* grouping pending fixes per recording
   with before/after crops; *Accept all* for high-confidence fixes.
4. **Run timeline** (G4): per run, a timeline of steps with thumbnails (already captured on
   failure; capture on each page change, low-res, retention per plan 05), masked values, timings.
5. **Fixture pages** (G5): in `jizza-agent-browser/test/fixtures/sites/`, small static pages that
   imitate patterns (autocomplete with code suffix, custom calendar, seat grid, passenger rows,
   captcha image, payment redirect, session-expired modal) — generic look-alikes, not copies of the
   real sites. E2E tests record and replay against them.
6. **Telemetry** (G6): opt-in, per site origin counts of healed/broken steps, no values; later.

## Size

M. Dry run + trusted flag (2 days), health check (2 days), fix review tab (1–2 days), timeline (2
days), fixture pages + e2e (3 days).

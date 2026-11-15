# 11. Many agents at once: sessions, accounts and concurrency

> The user can execute n different agents to run web automations with input data.

## What exists today

Browser affinity per run (`_browser` {deviceId, sessionId} in run variables, `BrowserAffinity`),
tab groups per session (`src/sessions/tab-groups.ts`), idle cleanup, two paired browsers on the
Mac (Chrome, Ulaa), sub-workflows and For each.

## Gaps

| # | Gap |
|---|-----|
| G1 | **Concurrency limits** per browser are not explicit: two runs on the same site in one browser share cookies (same IRCTC login), and can log each other out. |
| G2 | **Multiple accounts** on one site (two IRCTC IDs, personal and work Amazon): one Chrome profile has one cookie jar. |
| G3 | **Queueing**: when a browser is busy, a new run should queue with a position and ETA instead of failing or interleaving. |
| G4 | **Batch view** for For-each over many rows (S10): progress, per-row status, retry failed rows only. |
| G5 | Runs **steal focus**: a run brings tabs to the front while the person works; some sites need the tab focused (foreground actions). |
| G6 | **Unattended** runs (computer locked, no person) are W6 (Playwright) in the old plan, not built. |

## Proposed design

1. **Site lanes** (G1, G3): per device, a lane per site origin with capacity 1 by default (site
   rules can raise it). Runs needing a busy lane queue; the run page shows "Queued behind
   *IRCTC booking* (started 2 min ago)".
2. **Accounts** (G2): a saved login is an **account**; runs pick the account (input or default).
   Isolation options, in order of effort: (a) one account per lane with logout/login between
   runs (cookie clear for that site via `chrome.cookies`, simple); (b) separate Chrome profiles,
   each paired as its own device (works today, needs docs); (c) incognito/partitioned sessions
   (limited by extension APIs). Start with (a) + (b).
3. **Batch view** (G4): For-each over a recording gets a run summary table with per-item status,
   outputs and "Retry failed".
4. **Focus policy** (G5): runs open in a separate window (minimised where the site allows) and
   only focus when a step needs it; the person sees a small "Jizza is running" badge.
5. **Unattended** (G6): keep as a later phase (W6 Playwright runner on a server or the person's
   machine); out of scope here, listed for completeness.

## Size

M. Lanes + queue (2 days), account switching (2 days), batch view (2 days), focus policy (1 day).

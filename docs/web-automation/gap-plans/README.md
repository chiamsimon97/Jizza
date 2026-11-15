# Web automation: gap analysis and plans

Status: **draft for Chiam's review. Nothing here is built yet.** Written 2026-11-14 against
`Jizza` `release/M2_3_1` (faadad5, after PR #115) and `jizza-agent-browser` `main` (a4f1194, 0.16.0).

## The goal these plans test against

Many services have no API. Jizza automates them with workflows recorded in the person's own
browser (the extension), and runs them later with new data. The target experience:

> In Jizza chat: "Book a 3A ticket Chennai to Delhi on 20 Dec for me and my mother."
> Jizza picks the recorded IRCTC workflow, fills what it already knows, starts it in the person's
> browser, and asks for anything else **when the page needs it**: a card with the train list
> read live from the page, the passenger rows with their berth options, a picture of the
> captcha with a box to type it, and a screenshot of the fare before paying.

## How to read this folder

Start with [01-scenarios.md](01-scenarios.md). It walks through real products end to end and
tags every gap with the plan that closes it. Each plan file is self-contained: what exists today
(with code pointers), the gaps, the proposed design for recording and for runs, the security
notes, acceptance checks, rough size and the questions for Chiam.

| # | Plan | Chiam's requirement | Size | Suggested phase |
|---|------|--------------------|------|-----------------|
| 01 | [Scenarios walk-through](01-scenarios.md) | all | – | – |
| 02 | [Collect and map inputs on demand](02-inputs-on-demand.md) | **Req 1** | L | P1 |
| 03 | [Remove mapped fields, auto-arrange](03-remove-mapping-and-arrange.md) | **Req 2** | M | P1 |
| 04 | [Insert a missed action anywhere](04-insert-step-anywhere.md) | **Req 3** | M | P1 |
| 05 | [Secure field data between server and agent](05-secure-field-data.md) | **Req 4** | L | P1 (part P0) |
| 06 | [Person-in-the-loop: captcha, OTP, payment, live view](06-person-in-the-loop.md) | new | L | P1 |
| 07 | [Run reliability and recovery](07-run-reliability.md) | new | L | P2 |
| 08 | [Results, files, uploads and downloads](08-results-and-files.md) | new | M | P2 |
| 09 | [Timed runs: Tatkal, flash sales, slot polling](09-timed-runs.md) | new | M | P2 |
| 10 | [From chat to a running workflow](10-chat-to-run.md) | new | M | P1 |
| 11 | [Many agents at once: sessions, accounts, concurrency](11-many-agents.md) | new | M | P2 |
| 12 | [Recording editor gaps (checks, variables, loops, undo)](12-recording-editor.md) | new | M | P2 |
| 13 | [Testing, dry runs and site-change maintenance](13-testing-and-maintenance.md) | new | M | P3 |
| 14 | [Site rules, bot detection and compliance](14-site-rules-and-compliance.md) | new | S | P1 (decisions) |
| 15 | [Mobile notes (plan only)](15-mobile-notes.md) | – | – | not built |

Sizes: S about a day, M two to four days, L a week or more (web app + extension + server together).

## Suggested order

- **P0 (security quick wins, small):** the items marked P0 in plan 05: refuse plain HTTP outside
  dev, stop secrets and card answers landing in plaintext tables, redact recorded defaults.
- **P1 (makes IRCTC work end to end):** 02, 03, 04, 05, 06, 10, plus the decisions in 14.
- **P2 (makes it dependable):** 07, 08, 09, 11, 12.
- **P3 (keeps it working as sites change):** 13.

## What is already in place (so plans build on it, not around it)

- Recording: auto and manual modes, Scan and map (full / partial / pick), titles static or dynamic,
  Mark a list, Name this list, Group buttons, dropdowns static/dynamic, date formats and pickers,
  form steps, Mandatory inputs, "Repeat for each row" (`browser.fillRows`, passengers as one list
  input with columns), conditions (Only if shown / Otherwise), pause, discard.
- Edit recording: step-through (Next / Run to end / Skip), cursor + **New action** at the cursor,
  conditions, disabled/optional steps, repick element, Add wait, versions.
- Runs: step cards with a details form, per-item screenshots, editable resolved values, run-time
  list choice (Ask me / Let Jizza decide), remembered picks, item matching, site-error reading,
  local "needs the person" pause for captcha/OTP/login (side panel "Done, continue").
- Security: Ed25519-signed commands with nonce and deadline, device key (P-256) challenge, command
  input/output and recording steps encrypted at rest, saved logins sealed in the extension vault
  (AES-GCM, non-extractable key) with only id + name visible to the server, sensitive-field
  detection, plain-HTTP vault refusal, extension-page-only control messages.

## Decisions I need from Chiam (collected from all files)

1. Captcha: is "show the captcha picture on the card and type it" acceptable for IRCTC (plan 06,
   plan 14)? I recommend yes, person-solved only, never automatic solving.
2. Payment: the run stops at the payment page and hands over to the person (recommended), or
   UPI collect / saved-card flows with the person approving on the card?
3. Saved profiles (passengers, addresses): keep them in the extension vault (private, per browser)
   or on the server encrypted per user (works from any browser)? I recommend the server,
   encrypted with a per-user key, with "sensitive" fields left in the vault (plan 02, plan 05).
4. End-to-end encryption of field values between the web app and the extension so the server
   only relays ciphertext (plan 05, option B). It costs: server-side "Let Jizza decide" matching
   can't read those values. I recommend it only for fields marked secret, not for all.
5. Dry-run default: should every new recording run in "stop before final submit" mode until the
   person marks it trusted (plan 13)? I recommend yes.

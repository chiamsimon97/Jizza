# 01. Scenarios walk-through

Each scenario is a practical end-to-end run as a person would do it from Jizza chat, recorded
once and run many times. For every step I note what works today and the gap, tagged with the
plan that closes it (`→ 02` means plan 02).

Legend: ✅ works today, ⚠️ works with a workaround, ❌ gap.

---

## S1. IRCTC: book a reserved train ticket (the reference scenario)

**Ask:** "Book 3A Chennai Central to New Delhi on 20 Dec for me and Amma, lower berths if possible."

| # | Page step | Data needed | Today | Gap |
|---|-----------|-------------|-------|-----|
| 1 | Open irctc.co.in, close the promo pop-up | – | ✅ Only if shown | Pop-ups that show some days only: works with conditions. |
| 2 | Click LOGIN, type user ID | IRCTC user ID | ✅ "Username from saved login" (#111) | – |
| 3 | Type password | password | ✅ saved login (vault) | ❌ No way to give a password at run time without saving it (→ 02 secret input, → 05) |
| 4 | Type the **captcha** shown as an image | captcha text | ⚠️ local "needs the person" pause only; must sit at that browser | ❌ Card with the captcha picture + a text box, answer typed into the field (→ 06) |
| 5 | Sometimes an OTP / "verify it's you" | OTP | ⚠️ local pause | ❌ OTP on the card, short expiry, never stored (→ 06, → 05) |
| 6 | From / To station (autocomplete box) | station names | ⚠️ typed text works; picking the suggestion is fragile | ❌ "Type then choose a suggestion" field kind with matching ("MAS" vs "CHENNAI CENTRAL - MAS") (→ 02) |
| 7 | Journey date (custom calendar) | date | ✅ date formats/pickers (#113) | ⚠️ "tomorrow", "next Friday" from chat need resolving in the person's time zone (→ 10) |
| 8 | Class, quota (General / Tatkal / Ladies) dropdowns | class, quota | ✅ static/dynamic dropdowns | ❌ Enum values from chat ("3A", "AC 3 tier") need synonyms (→ 02) |
| 9 | Search; train list loads | – | ✅ | ❌ "Wait until results or 'No trains'" needs a two-outcome wait (→ 07) |
| 10 | Choose the train (card list with name, times, availability per class) | train choice | ✅ Mark a list + run-time choice | ❌ Card should show live columns (departure, duration, availability, fare) and filter rules ("before 10 PM, AVAILABLE only") (→ 02, → 06) |
| 11 | Click class tab on that train, read availability, click Book Now | – | ✅ within list | ❌ Branch on availability text ("WL 23" vs "AVAILABLE-0012"): needs "read text then decide" (→ 12) |
| 12 | Passenger rows: name, age, gender, berth preference, food choice, nationality | rows | ✅ fillRows + list input (#115) | ❌ Per-column options read from the page (berth list differs per class); saved passenger profiles; age rules (child < 5 gets no seat); senior citizen concession checkbox per row (→ 02) |
| 13 | Contact mobile, travel insurance radio, auto-upgrade checkbox, "book only if confirmed berths" | flags | ✅ form step fields | ⚠️ Defaults the person never wants to be asked again: per-input "always use" (→ 02) |
| 14 | Second captcha on review page | captcha | ⚠️ | ❌ same as step 4 (→ 06) |
| 15 | Review fare: show screenshot, ask "Pay ₹2,345?" | approval | ✅ approval card + screenshot | ❌ Fare must be read into a value and checked against a limit the person set (→ 12, → 06) |
| 16 | Payment gateway (new page / bank window / UPI app) | payment | ⚠️ undefined | ❌ Hand-off: the run stops, the person pays in the tab, Jizza resumes on the confirmation URL (→ 06, decision 2 in README) |
| 17 | Confirmation: PNR, coach/berth, download e-ticket PDF | outputs | ⚠️ getText / download exist | ❌ Named outputs back to chat + ticket file attached + "booked" memory (→ 08) |
| – | IRCTC logs the session out after a few idle minutes while a card waits | – | ❌ | Card deadline tied to page timeout, keep-alive, re-login and resume (→ 06, → 07) |
| – | The person's chat had only "me and Amma" | passenger details | ❌ | Resolve "me" and "Amma" from saved passenger profiles (→ 02, → 10) |

## S2. IRCTC Tatkal at 10:00 sharp

Same as S1 plus: log in at 09:57, fill everything up to the search, wait until 10:00:00 by the
server clock, then search and book in seconds; passengers must come from a prepared list because
there is no time to ask. Today there is no "start at exact time", no pre-filled "ready" state and
no clock check.
→ 09 (timed runs), → 02 (prepared input sets), → 14 (IRCTC rules for automated Tatkal).

## S3. BookMyShow: two tickets, Friday evening, near me

| Step | Today | Gap |
|------|-------|-----|
| City pop-up, movie search, language/format pop-up | ✅ conditions, Only if shown | – |
| Date strip → theatre cards → showtimes | ✅ dependent lists (date → theatre → showtime) | ❌ "Friday evening" means a time window, not a name: match rules on time ranges (→ 02) |
| Seat count pop-up, seat map (canvas/SVG grid) | ❌ | Seat-map picking: show the seat map screenshot on the card, the person taps seats, or rules "best 2 adjacent in rows E–H" (→ 06) |
| Food upsell pop-up | ✅ Only if shown | – |
| Pay | ❌ | Payment hand-off (→ 06) |
| Booking ID + QR | ❌ | Capture outputs + QR image to chat (→ 08) |

## S4. E-commerce (Amazon / Flipkart style): reorder an item if under a price

| Step | Today | Gap |
|------|-------|-----|
| Search, open product | ✅ | – |
| Read price, compare with the limit | ⚠️ getText | ❌ Typed value extraction (currency to number) and "if price ≤ limit" (→ 12) |
| Choose size/colour variant | ✅ Group buttons | ❌ Out-of-stock variants must be shown greyed on the card and skipped (→ 02) |
| Change delivery address (saved list) | ✅ list choice | ❌ Address profile matching (→ 02) |
| Apply coupon, read "coupon not valid" | ⚠️ site errors on forms | ❌ Recoverable branch on message (→ 07) |
| Place order (Cash on delivery) | ✅ approval | ❌ Idempotency: a retried run must not place a second order (→ 07) |
| Order ID | ❌ | → 08 |
| Run daily until price drops | ❌ | Schedule + stop condition (→ 09) |

## S5. Bank / card statement download (banking-like)

Log in (customer ID, password, OTP), go to statements, choose account and month, download PDF,
send it to the person or to Drive.

- Login with OTP → 06. Virtual keyboards (password typed by clicking keys) → 02 (field kind
  "on-screen keypad"), and the vault must still be the only source (→ 05).
- "Session expires in 5 minutes" banners, logout on back button → 07 (no back navigation; use
  in-app links; re-login and resume).
- Download into Jizza, not just the Downloads folder → 08. Statement content is highly sensitive
  → encrypted at rest, auto-delete (→ 05).
- Many banks block automation fingerprints; some forbid it in terms → 14.

## S6. Utility bill payment / recharge (electricity board, telecom)

Consumer number (from a saved list), read bill amount and due date, pay via UPI. Gaps: typed
outputs (amount, due date) before paying → 12, 08; payment hand-off → 06; monthly schedule with
"only if a bill is due" → 09, 12; receipt PDF → 08.

## S7. Government / long forms (passport, GST return, college admission)

Many pages, 60+ fields, sections that appear based on earlier answers, file uploads (photo,
signature, ID proof with size/format limits), save-draft, and a final declaration.

- Inputs grouped by page and section, asked page by page, not one 60-field card → 02.
- Conditional sections ("Have you held a passport before? Yes → old passport details") → 02, 12.
- **File upload** (no action exists today) with resizing to the site's limit → 08.
- Removing fields mapped by mistake on a long page and keeping order tidy → 03.
- A step missed in the middle of page 4 → 04.
- Aadhaar/PAN numbers are personal identifiers → 05 (classification "personal ID").

## S8. Airline web check-in / hotel booking

PNR + last name in, seat map, boarding pass PDF out. Seat map → 06; boarding pass → 08;
check-in opens exactly 48 h before departure → 09.

## S9. Job portal: apply to matching jobs (repeat for each item)

Search, for each result card: open, read title/company, if it matches rules click Apply, fill a
short form, upload CV. Gaps: "for each card on the page" loop with paging (today only rows and
list choices) → 12; upload → 08; daily cap and duplicate check (already applied) → 07, 12.

## S10. Internal admin portal (no API): create a user for every row of a sheet

Inputs come from a Google Sheet or CSV, not from a person. Gaps: run a recording once per row
with results written back (sub-workflow + For each exist; need a "batch run" view with per-row
status and retry failed rows) → 11, 08.

---

## Gaps that show up in almost every scenario

1. Inputs are not modelled as a **catalogue** (type, group, source, when to ask, options source,
   validation). Today they are per-step mappings with a name and a default. → 02
2. The person must be at the browser for captcha/OTP/payment. → 06
3. Values sit in plaintext run context and notification rows, and plain HTTP is allowed. → 05
4. Recording mistakes are expensive to fix: no unmap in the UI, no insert without replaying, no
   "tidy up". → 03, 04
5. Runs are one-shot: no resume after logout, no idempotency, no typed outputs. → 07, 08

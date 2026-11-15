# 02. Collect and map inputs on demand (Requirement 1)

> For IRCTC there are many inputs, from login to train details to passenger details with
> options. They must be collected and mapped to the right field on demand, both while
> recording and in workflow runs.

## What exists today

- A recorded step value becomes an input through a **mapping** on the step:
  `step.mappings[field] = { name, type, default }` (server `RecordingService.map`, client
  `RecordingReview` → `InputDialog`, types `text | number | boolean | date | datetime`; plus
  `list` for `rows`). Scan and map sets `perRun`, `inputName`, `mandatory` per mapped field
  (`src/recorder/mapping.ts`).
- Mandatory inputs compile to `config.requiredInputs`; the step card hides Submit until filled
  (PR #115, ext #39).
- Passenger rows: `browser.fillRows`, one list input (`passengers`) with columns, a table on the
  run form and on the card.
- Lists: run-time choice from the live page, with Ask me / Let Jizza decide, remembered picks,
  matching rules (`ItemMatch.java`).
- The run form asks for every workflow input **before** the run; missing mandatory values are
  asked at the step card (`WebStepForm`).

## Gaps

| # | Gap | Seen in |
|---|-----|---------|
| G1 | No input **catalogue**: inputs live on steps, so the workflow can't say "these 14 inputs, in 4 groups, asked at these moments". Order on the run form follows step order by accident. | S1, S7 |
| G2 | No **when to ask** per input: always up front (run form) or only if missing at the step. IRCTC needs "train choice: ask at the step, from the live list" and "class: ask up front". | S1, S3 |
| G3 | No **source** per input: chat, saved profile, previous step's output, fixed value, ask. | S1, S4, S10 |
| G4 | No **secret** input type for one-off values (password not saved, OTP, CVV): today a sensitive step only accepts a saved login. | S1, S5 |
| G5 | Row columns have no **types or options**: berth preference is free text, gender is free text, age isn't checked as a number. Options for a column can't be read from the page (they differ per class). | S1 |
| G6 | No **saved profiles** (passenger master list, addresses, consumer numbers) to fill rows from "me and Amma". IRCTC itself has a master list; our runs can't use either. | S1, S4, S6 |
| G7 | No **validation** beyond required: min/max, pattern (mobile 10 digits, PNR 10 digits), enum, date in range ("journey within 60 days"), cross-field (child age vs seat). | S1, S7 |
| G8 | No **synonyms** for enum values: "3A", "AC 3 Tier", "Third AC" must map to the same option. | S1 |
| G9 | Autocomplete fields ("type then choose suggestion") are a typed text + a separate click; the suggestion choice isn't an input and isn't matched. | S1 stations, S4 address |
| G10 | Inputs can't be **grouped** (Login, Journey, Passengers, Contact, Payment) or shown page by page, so a long form becomes one huge card. | S7 |
| G11 | No **conditional inputs**: "GST number only if business purchase", "old passport details only if Yes". | S7 |
| G12 | No way to map an input **after** recording from the workflow builder to an arbitrary web step field (only in review of the recording / edit recording). | all |
| G13 | Values typed during recording become **defaults** (real passenger names, phone numbers). Shared or published workflows leak them. | S1, see 05 |
| G14 | Run-time row count: rows input has as many rows as given, but the page limit (6 passengers, 4 for Tatkal) isn't known or enforced. | S1, S2 |

## Proposed design

### A. Input catalogue on the workflow (server + web)

Add `inputs` as a first-class list in the workflow's `inputSchema` (it already exists as JSON);
steps reference inputs by name instead of carrying the definition.

```jsonc
{
  "name": "passengers",
  "label": "Passengers",
  "group": "Passengers",          // G10
  "type": "rows",                  // text|number|boolean|date|datetime|enum|secret|rows|file|phone|email
  "columns": [
    { "name": "name",   "type": "text",   "required": true, "maxLength": 16 },
    { "name": "age",    "type": "number", "min": 1, "max": 125, "required": true },
    { "name": "gender", "type": "enum",   "options": ["Male", "Female", "Transgender"],
      "synonyms": { "M": "Male", "F": "Female" } },
    { "name": "berth",  "type": "enum",   "optionsFrom": "page" }      // G5: read live
  ],
  "minRows": 1, "maxRows": 6,                                          // G14
  "ask": "atStep",                // upFront | atStep | ifMissing   (G2)
  "source": ["chat", "profile:passenger", "ask"],                    // G3, tried in order
  "required": true,
  "sensitivity": "personal",      // none | personal | secret   (feeds plan 05)
  "showIf": null,                 // G11: { "input": "quota", "equals": "TATKAL" }
  "default": null                 // never a recorded real value (G13)
}
```

- Server: `WorkflowInputs` (new, engine-workflow) validates and normalises values: types, enums
  with synonyms (G8), patterns, ranges, cross-field rules as simple expressions using the existing
  JEXL evaluator (G7). One place used by the run form, chat, cards and `/run`.
- Migration of existing workflows: build the catalogue from current `mappings` on first open
  (name, type, default, required); no data loss; step mappings keep working, now pointing at
  catalogue names.

### B. When and how to ask at run time

- `upFront`: on the run form / chat before the run starts.
- `atStep`: asked on that step's card, with options read from the page **at that moment**
  (train list, berth options of the chosen class). This generalises today's run-time list choice
  to any field.
- `ifMissing` (default): try the sources; ask at the step only if still empty.
- Grouping: the card for a form/page asks only that page's group (G10). One page = one card.
- The engine collects all `atStep` inputs that belong to the **same page** into one card, not
  one card per field (today a fillForm already does this per step; extend to "consecutive steps
  on the same URL").

### C. Saved profiles (G6)

- New "Profiles" area in Jizza: Passengers, Addresses, Payment references (no card numbers),
  Accounts (consumer numbers, customer IDs). Each profile type has a schema; rows input columns
  map to profile fields (`profile:passenger.name → name`).
- Chat "me and Amma" resolves names/aliases against profiles (plan 10). The card shows the
  chosen profiles, editable, before filling.
- Storage options (decision 3 in README): server-side, encrypted per user (recommended; plan 05),
  or extension vault (private to one browser). Secret fields always stay in the vault.

### D. Recording side (extension)

- When a field is mapped (Add field / Scan and map / form step), the side panel offers the
  catalogue settings in a compact sheet: **name, group, type, ask when, options from
  page/static, required**. Defaults chosen smartly: dropdowns → enum with page options; date
  boxes → date; password → secret; repeated sections → rows.
- Group is pre-filled from the page: the nearest heading/fieldset legend, or the page title
  ("Passenger Details"). The person can rename.
- Autocomplete kind (G9): when typing is followed within a short time by a click on a suggestion
  list item, the recorder merges both into one `browser.typeAndChoose` step: `{ text, choose:
  { itemCss, match } }`, one input (`fromStation`) with match rules (prefix / contains / code in
  brackets).
- Row columns (G5): on "Repeat for each row", each field in the row gets a type; dropdowns and
  radio groups in the row record their options and are flagged `optionsFrom: page` when they
  change between runs.
- Recording uses **placeholder data**: a "Use sample data" toggle fills mapped fields with
  generated values (`Test Passenger`, age 30) so the person never types real PII into a
  recording (G13).

### E. Builder side (web)

- Workflow "Inputs" panel: the catalogue as a table (drag to reorder, group, type, ask when,
  source, required, sensitivity). Changes here update all steps that use the input.
- On any web step in the builder: "Use an input for this field" picker listing catalogue inputs
  and step outputs (`{{ steps.readFare.amount }}`) (G12).
- Run form renders groups as sections; `atStep` inputs are listed as "asked during the run".

## Security notes

Values typed by the person at run time follow plan 05: `secret` inputs never stored in run
context, `personal` values encrypted at rest and redacted in logs. Recorded defaults are dropped
for `personal` and `secret` inputs.

## Acceptance checks

1. Record IRCTC once with sample data; the workflow shows 4 groups (Login, Journey, Passengers,
   Contact) and about 12 inputs in page order.
2. Chat "book 3A Chennai to Delhi 20 Dec for me and Amma": class resolves to "AC 3 Tier (3A)",
   passengers fill from profiles, the run asks only for the train (live list) and the captcha.
3. Berth preference options on the card are the ones the page shows for the chosen class.
4. Age "abc" or 7 passengers is refused on the card with a clear message, before anything is
   typed on the page.
5. Existing recorded workflows open and run unchanged (catalogue built from mappings).

## Size

L. Server catalogue + validation (3–4 days), web Inputs panel + run form sections (3 days),
extension mapping sheet + typeAndChoose + row column types (4 days), profiles (3 days).

## Questions for Chiam

1. Profiles on the server (encrypted) or in the extension vault? (README decision 3)
2. Is "one card per page" the right default, or one card per step as today?
3. Should recording force sample data for fields marked personal, or only suggest it?

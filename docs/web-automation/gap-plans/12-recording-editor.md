# 12. Recording editor gaps: checks, variables, loops, sections, undo

## What exists today

Conditions on "element shown" (Only if shown / Otherwise), disabled/optional steps, list choices,
form and rows steps, Add wait, step rename/move/delete, Edit recording with versions, condition
editing (Take in next step / Leave out last step).

## Gaps

| # | Gap | Scenario |
|---|-----|----------|
| G1 | Conditions only test **element shown/missing**. Need text/value tests: availability contains "AVAILABLE", fare ≤ limit, status equals "WL". | S1, S4 |
| G2 | No **variables from the page** in the recorder: "read fare into `fare`" then use it in a condition or a card. (`getText` exists as Add check, but naming outputs and using them later is a builder task.) | S1, S4, S6 |
| G3 | **Loops** beyond rows/list choice: "for each result card on the page (with paging) do …", "repeat until no Next page". | S9, S10 |
| G4 | **Sections**: no way to name parts of a recording (Login, Search, Passengers, Review, Pay). Needed for re-login (plan 07), grouping inputs (plan 02), reuse of the Login section across workflows. | S1, S5 |
| G5 | **Reusable sub-recordings**: IRCTC login used by "book", "cancel", "check PNR" recordings is recorded three times. | S1 |
| G6 | **Assertions**: "check the page shows the train I chose before paying"; fail with a reason if not. | S1 |
| G7 | **Undo** (also plan 03). | all |
| G8 | **Export/import** a recording (share a template without personal data, back up). Proposed 2026-11-13, not built. | all |
| G9 | **Version diff**: what changed between v3 and v4 of a recording. | all |
| G10 | **Test with sample data** from the editor: run the recording once with given inputs without saving it as a workflow run. | all |

## Proposed design

1. **Value conditions** (G1): condition kinds `text contains / equals / matches`, `number < > =`
   (with plan 08 typed extraction), `URL matches`; recorder: *Only if…* menu offers "the text of
   this element…" after picking it. Compiles to `browser.getText` + the engine's Filter/BRANCH.
2. **Read into variable** (G2): Add step › *Read text into…* (name, type); outputs appear in the
   side panel's variable list and in value pickers for later steps, conditions and cards.
3. **For each on the page** (G3): mark a list (existing) then *Repeat these steps for each item*
   with optional *Next page* button and max items; compiles to FOR_EACH over `listItems` with the
   item's index; inner steps' locators are relative to the item (like rows).
4. **Sections** (G4): side panel *Start section* / names at page changes (suggested from page
   titles); stored as `meta.section`; shown as headers in panel, review and builder.
5. **Reuse** (G5): *Save section as a module* turns a section into a recorded module (workflow
   modules #41 exist) that other recordings can *Use module* for; login sections declare the
   saved-login input.
6. **Check step** (G6): `browser.assert@1` (element/text/value condition, message on failure);
   proposed by the recorder before irreversible steps.
7. **Undo** (G7): see plan 03 C.
8. **Export/import** (G8): JSON file with steps, catalogue (plan 02) and sections; personal
   defaults stripped (plan 05); saved-login references kept by name only; import maps them.
9. **Version diff** (G9): Recordings page compares two versions step by step (added, removed,
   moved, changed values/locators).
10. **Test run** (G10): *Test with…* opens a small input form (sample values) and runs in a new
    tab, in dry-run mode (plan 13) by default.

## Size

M–L depending on slice. Value conditions + variables (3 days), for-each on page (3 days),
sections + reuse (3 days), assert (1 day), export/import (2 days), diff (1–2 days), test run (1 day).

# 03. Remove mapped fields and auto-arrange (Requirement 2)

> Need the capability to remove mapped fields and auto-arrange in recordings.

## What exists today

- **Scan and map** (before recording): a mapped field/list can be removed (`Mapper` op
  `remove`), and `suggestOrder` links lists top-to-bottom (`dependsOn`, relation `after`).
- **Form step while it is being filled**: `formRemove` drops a field from the draft.
- **Server**: `RecordingService.map` already supports unmapping (an edit op `map` with
  `input: null` removes `mappings[field]`).
- **Recording review (web)** and **side panel**: steps can be deleted and moved (drag, up/down).
  Inputs show as badges (`input: passengers`).

## Gaps

| # | Gap |
|---|-----|
| G1 | No **Unmap** button anywhere in the UI: a value turned into an input can't be turned back into a fixed value, either in the side panel or in `RecordingReview` (the server supports it, the client never sends it). |
| G2 | A field can't be removed from a **saved** form step (`browser.fillForm`) or a column from a **rows** step; only from a draft form. |
| G3 | Removing a mapped field in Scan and map **after** steps were recorded on it leaves those steps with the old `meta.field` title and list marks; nothing tells the person. |
| G4 | Deleting a step that **produces** something used later (a list choice the next list depends on, a getText used in a condition, a step inside a condition) gives no warning; the condition may become empty and is dropped silently at compile. |
| G5 | Removing an input used by several steps (same `fromStation` in two places) must be done step by step. |
| G6 | **Auto-arrange** does not exist for steps. After moves, inserts and deletes the order can be wrong: a typed field after the Submit that sends it, a list choice before the list it depends on, a step on page 2 between page 1 steps. |
| G7 | Form fields keep the order they were added, not the page's visual order. |
| G8 | Inputs on the run form follow step order, so after moves the run form order is odd (fixed in plan 02 by the catalogue, arranged here). |
| G9 | No **undo** after delete/remove (proposed in the 2026-11-13 review, not built). |

## Proposed design

### A. Remove / unmap (side panel and web review)

- Each input badge gets a **⋯ menu**: *Keep the recorded value* (unmap, value stays as fixed),
  *Clear the value* (unmap and empty it), *Rename input*, *Remove from all steps* (G5: one edit
  op batch `map input:null` for every step that uses the name).
- Saved form steps and rows steps list their fields with a remove (×) per field (G2): new edit
  op `removeField { seq, index }` on the server; it re-indexes `fields.N.value` mappings and
  `config.requiredInputs`.
- Scan and map `remove` after recording (G3): the panel asks "3 recorded steps use *Journey
  date*. Remove the mapping only (steps stay, keep their recorded value) / Remove the steps
  too". Both are explicit edit ops.

### B. Safe delete with dependency check (G4)

Build a small dependency graph per recording (server `RecordingGraph` already walks steps):

- producers: list choices (`dependsOn`), `getText` / `extractTable` outputs, condition starts.
- consumers: steps whose input refers to `{{ steps.X }}`, lists with `dependsOn`, condition ends.

Deleting a producer shows: "*Theatre* is used by *Showtime* (step 9). Delete both / Delete only
this one (Showtime will choose from all theatres)". Empty conditions after a delete are shown
in the list with the existing amber notice instead of being dropped silently.

### C. Undo (G9)

Each edit op returns its inverse (`delete` → `restore {seq, index, step}`, `map` → previous
mapping, `move` → move back). The side panel and web review keep the last 20 inverses for the
session; **Undo** (Ctrl+Z / button) sends the inverse. Server keeps soft-deleted steps already
(`entity.isDeleted()`), so restore is cheap.

### D. Auto-arrange (G6, G7, G8)

A button **Tidy up** in the side panel and web review that proposes a new order and shows a
before/after diff the person accepts; it never reorders silently.

Rules, in order:
1. Keep page order: steps are grouped by the page (URL path + title) they ran on, in the order
   pages were first visited. A step can't move before the step that navigated to its page.
2. Within a page: fields in visual order (top, then left, from the bounding boxes the recorder
   already captures for previews), then choices, then the button that submits/navigates last.
3. Dependencies win: a list choice comes after the list it depends on; a condition keeps its
   steps contiguous; waits stay right before the step they guard.
4. Form step fields re-sorted by visual position (G7).
5. Inputs catalogue order follows the arranged steps, grouped by page/group (plan 02).

Implementation: arrange runs in the extension (it has positions and page facts) and sends a
list of `move` ops; the server validates dependencies again before applying.

## Acceptance checks

1. Map *Journey date*, then Unmap → the step types the recorded date again; run form no longer
   asks for it.
2. Remove *Food choice* from a saved passenger rows step; runs leave that box untouched.
3. Delete the *Theatre* choice → warning naming *Showtime*; Undo brings it back with its input.
4. Record a form in random order; Tidy up proposes top-to-bottom order with Submit last; the
   person accepts and the run works.
5. Remove from all steps on `fromStation` used twice → both steps get the fixed value.

## Size

M. Web review + side panel menus (2 days), server `removeField` + restore + dependency check
(1–2 days), Tidy up (2 days).

## Questions for Chiam

1. "Auto-arrange" meant tidy step order (as above) or also arranging inputs on the run form? I
   planned both, with steps as the main one.
2. Should Tidy up run automatically when a recording is stopped, showing the diff in review?

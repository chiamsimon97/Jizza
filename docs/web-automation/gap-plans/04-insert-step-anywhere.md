# 04. Insert a missed action anywhere (Requirement 3)

> While recording, an in-between step may be missed; we need to add an action at any place.

## What exists today

- **Edit recording** (saved recording opened from Jizza): a cursor marker; Next / Run to end
  replay steps so the page reaches the right state; **New action** captures what the person
  does at the cursor; Add wait inserts at the cursor (`recording.ts` `insertEditStep`, op
  `capture`, `cursor`, `addWait`). Inserted steps reach the server as
  `browser.recording.step` with `after`.
- **Builder**: "Record steps here" records a new mini-recording and inserts it as nodes at a
  point in a workflow (`RecordFlow` mode `insert`).
- **First-time recording**: steps can be moved and deleted; new steps always append at the end.

## Gaps

| # | Gap |
|---|-----|
| G1 | During a **first-time recording** you can't say "add this action after step 5": everything appends. Moving it afterwards works but is fiddly and the person doesn't know where it ran. |
| G2 | To insert in Edit recording the person must **replay up to that point** (log in, captcha...). For a missed click on page 6 of IRCTC that is slow and may not be possible (needs a fresh captcha, a live train). |
| G3 | Only actions you **do on the page** (or Add wait) can be inserted. No way to insert a non-page action at a position: Open URL, Press key, Scroll, Switch tab, Read text, Check exists, Screenshot, Ask the person, Set variable. |
| G4 | No **pick-only** insertion: choose the element on the page now without performing the action (useful when doing it would break state, e.g. Submit). |
| G5 | The web review has no "Add step here" at all; only reorder/delete. |
| G6 | Inserting inside a **condition** or inside a **repeated row** section is not explicit. |
| G7 | A step inserted while the page is not in the state it will be at run time gets locators from the wrong state (e.g. the element exists only after the pop-up). No warning. |

## Proposed design

### A. "Insert here" marker in first-time recording (G1)

- The side panel step list gets a thin **insertion line** between steps (hover shows ＋). Clicking
  ＋ sets `insertAt` (the same cursor concept as Edit recording); new actions go there until the
  person clicks *Back to the end*. The marker is visible in the list and in a banner: "Adding
  after step 5: *Click Search*".
- Recorder change: `RecordingSession.append` becomes `insert(at)`; the existing `after` field on
  `browser.recording.step` already lets the server place it.

### B. Add step menu at any position (G3, G5)

Both in the side panel (any mode) and in the web review, ＋ opens **Add a step**:

| Kind | Needs the page? | Notes |
|------|-----------------|-------|
| Click / Type / Choose / Tick (pick element) | yes, pick-only (G4) | Picks the element with the existing picker, builds the locator, does **not** perform the action unless "Do it now" is ticked. |
| Open URL, Back, Refresh, Switch tab, Close tab | no | Plain forms. |
| Press keys, Scroll, Wait (time / element / text / URL) | no / optional pick | |
| Read text into a variable, Check exists, Screenshot | pick | Output name asked (plan 12). |
| Ask the person (card) | no | Plan 06 card with inputs/screenshot. |
| Set variable, Condition, Repeat | no | Plan 12. |

In the **web review**, element-needing kinds are sent to the paired browser as a pick request
(today's `browser.pickElement` action) on the tab that's open; if the browser isn't on the right
page the review says "Open the page where this step happens, then Pick".

### C. Insert without full replay (G2)

- **Jump to step**: Edit recording gets "Start from step N": the person brings the browser to
  that page by hand (logs in themselves, solves the captcha) and presses *I'm here*; the cursor
  is put at N without running steps 1..N-1. The recorder checks the page matches step N's
  recorded URL pattern and warns if not.
- Remembered **page snapshots**: the recorder already keeps a page preview per step; showing it
  next to the insertion line helps the person confirm they're at the right place.

### D. Inside conditions and rows (G6)

- The insertion line knows its depth: inserting between two indented steps puts the new step
  inside that condition / row section. A small chip says "inside: *Only if shown – Language
  pop-up*". Dragging out works as today.

### E. State check for inserted steps (G7)

When a step is inserted at position N, the recorder compares the current page (URL path + title
+ presence of the next step's element) with step N+1's recorded facts. If they differ: "This
page doesn't look like where step 6 runs (expected */booking/psgninput*). Insert anyway?"

## Acceptance checks

1. First-time IRCTC recording: after reaching passengers, click ＋ after step 4, pick the
   *Quota* dropdown, choose Tatkal; the step lands as step 5; later actions append at the end
   again after *Back to the end*.
2. Web review: ＋ between steps 7 and 8 → *Wait until text "Train list"*; saved; run waits there.
3. Edit recording: *Start from step 12*, log in by hand, *I'm here*, New action on the page,
   saved as version N+1 without replaying 1–11.
4. Inserting inside an *Only if shown* block keeps it inside after save and compile.

## Size

M. Insertion marker + insert in recorder (1–2 days), Add-a-step menu in panel and web (2 days),
Start-from-step + state check (1–2 days).

## Questions for Chiam

1. In first-time recording, should the insertion marker go back to the end automatically after
   one inserted action, or stay until the person moves it? I suggest after one action, with a
   pin to keep it.

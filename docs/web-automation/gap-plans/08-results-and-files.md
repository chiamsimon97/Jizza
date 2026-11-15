# 08. Results, files, uploads and downloads

## What exists today

- `browser.getText`, `browser.extractTable`, `browser.listItems`, `browser.pageInfo`,
  `browser.screenshot` produce step outputs.
- `browser.download@1` (url / selector / element) captures the file Chrome downloads (also
  `blob:` URLs), up to 100 MB, and uploads it to Jizza as an encrypted device file; output
  `{ file, name, size }`.
- Run log chip in chat (#93); `{{ }}` mapping of outputs into later steps.

## Gaps

| # | Gap | Scenario |
|---|-----|----------|
| G1 | No **file upload** action (`<input type=file>`, drag-drop zones): photo, signature, ID proof, CV. | S7, S9 |
| G2 | No file **pre-processing** for site limits (resize to ≤ 50 KB JPEG, 200×230 px, convert to PDF). | S7 |
| G3 | Outputs are untyped strings: "₹2,345.00", "20-Dec-2025", "PNR: 4512345678" need parsing into number / date / pattern-extracted value. | S1, S4, S6 |
| G4 | No **named result** of a workflow: the chat reply can't say "Booked. PNR 4512345678, B2-34 LB, ₹2,345" with the ticket attached unless someone builds a message step by hand. | S1, S3 |
| G5 | Recording doesn't propose capturing results: on a confirmation page the person must remember to add Read text steps. | S1 |
| G6 | Downloads triggered by a normal click during recording: whether the recorder turns that into `browser.download` should be verified; often it's recorded as a plain click and the file is lost at run time. | S1, S5 |
| G7 | No place where results **accumulate** (all PNRs booked, all orders, statements per month) for later questions. | S1, S5, S10 |
| G8 | Batch runs (S10) can't write a per-row result back to the source sheet. | S10 |

## Proposed design

1. **`browser.uploadFile@1`** (G1): input `file` (a Jizza file id from chat attachment, profile
   document, or a previous step's download), element = the file input or the drop zone.
   Extension uses CDP `DOM.setFileInputFiles` when the debugger is attached (it already has
   `src/browser/debugger.ts`), else a `DataTransfer` drop. Recorder: choosing a file during
   recording records the step with an input of type `file` (the actual file is **not** stored).
2. **File transforms** (G2): optional `transform: { maxKb, width, height, format }`, done in the
   extension with OffscreenCanvas for images; PDFs out of scope at first.
3. **Typed extraction** (G3): `getText` gains `as: number | money | date | pattern` with a
   pattern (regex with one group) and date format; output keeps both `text` and `value`.
4. **Workflow result** (G4): the catalogue (plan 02) gets an **outputs** list: name, label, type,
   from which step; plus a result template ("Booked. PNR {{pnr}} …"). Chat shows it as the final
   message with files attached. `/run` and Let Jizza decide return it too.
5. **Capture results while recording** (G5): side panel button *This is the result page*; the
   recorder scans for prominent values (labels like PNR, Booking ID, Order ID, Amount) and offers
   them as outputs with names, plus "Download the ticket" if a download link is present.
6. **Downloads in recording** (G6): when Chrome starts a download during recording, the recorder
   replaces the click step with `browser.download` on the same element.
7. **Result history** (G7): each workflow keeps a results table (date, run, outputs), encrypted
   per plan 05; chat can answer "what was my last PNR?" by reading it (via memory/storage).
8. **Batch write-back** (G8): For-each over a sheet writes each row's outputs/status to chosen
   columns through the existing connector step.

## Acceptance checks

- Passport-style form: photo uploaded and resized under the site limit; run continues.
- IRCTC confirmation: chat shows PNR, berths and fare, ticket PDF attached; Recordings page lists
  past results.
- Amazon price read as a number usable in "if price ≤ 500".

## Size

M. Upload + transforms (3 days), typed extraction (1 day), outputs + result template + chat (2
days), result capture in recorder (1–2 days).

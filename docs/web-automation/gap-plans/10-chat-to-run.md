# 10. From chat to a running workflow

> In the user's browser, in chat, they tell Jizza to book a train ticket, give some details,
> and the rest is asked during the run.

## What exists today

- `/run <workflow>` with `name: value` lines, no model call (#105); chat-box picker of runnable
  workflows.
- "Let Jizza decide" orchestrator and loose workflow find (#92, #108); run-log chip in chat (#93).
- Step cards in the inbox; missing mandatory values asked at the step.

## Gaps

| # | Gap |
|---|-----|
| G1 | Natural language → **which recorded workflow**: "book a train" should find the IRCTC recording by intent, not by its name ("Simon"). Recordings have no description/intents. |
| G2 | Natural language → **inputs**: extracting from, to, date, class, passengers into the input catalogue (plan 02) with normalisation ("tomorrow", "3A", "me and Amma"). |
| G3 | **Confirm before starting**: a short summary card in chat ("IRCTC: MAS → NDLS, 20 Dec, 3A, 2 passengers (you, Amma). Train: ask me from the list. Start?") with edit. |
| G4 | **Cards inline in the chat thread** that started the run (today they're in the inbox; chat shows a run-log chip). |
| G5 | **Progress in chat**: "Logged in · Searching trains · Waiting for you: choose a train" as the run moves. |
| G6 | Token cost: chat turns offer every device action as a tool (~33k chars per browser; noted in memory for #105). Running a recorded workflow from chat should not need those tools. |
| G7 | **Follow-ups in the same thread**: "cancel it", "use the 22:00 train instead", "what's the PNR?". |
| G8 | Which **browser/device** runs it when two are paired (Chrome, Ulaa), and what if none is online. |

## Proposed design

1. **Intents on workflows** (G1): when a recording is saved, Jizza suggests a description and 3–5
   example asks ("book a train ticket", "IRCTC booking"); stored on the workflow; workflow find
   ranks by these (embedding search already used by memory can be reused).
2. **Input extraction** (G2): one cheap-model call with the input catalogue as a JSON schema
   (types, enums with synonyms, profile references) → values + "unknown" list; then
   `WorkflowInputs` validates and normalises (dates in the person's time zone, enums by synonym,
   profiles by name/alias). Unknowns follow each input's `ask` rule (plan 02).
3. **Start card** (G3): a chat card with the resolved inputs (editable), the inputs that will be
   asked later, the device that will run it, and Start / Edit / Cancel. Skippable per workflow
   ("don't ask me to confirm") except when the workflow has irreversible steps (plan 07).
4. **Inline cards** (G4): notifications raised by a run started from chat are also posted into
   that chat thread as interactive cards (same component as the inbox; answering in one place
   resolves both).
5. **Progress** (G5): run events (step group started, waiting for person, done, failed) update one
   live chat message rather than many.
6. **Lean tool set** (G6): when chat routes to "run a saved workflow", the model gets only
   `find_workflow`, `extract_inputs`, `start_run`; device action tools are not offered.
7. **Thread commands** (G7): cancel run, change an input not yet used (allowed until the step
   using it has run), ask for outputs (plan 08).
8. **Device choice** (G8): workflow default device; else the browser it was recorded in; else ask
   on the start card. Offline → plan 07 G11 waiting state with a nudge.

## Acceptance checks

- "Book 3A Chennai to Delhi 20 Dec for me and Amma" → start card with correct values in one turn,
  under ~5k tokens.
- Captcha card appears in the same chat thread; answering it continues the run.
- "Use the 22:00 train instead" before the train step runs changes the rule; after, Jizza says it
  can't.

## Size

M. Intents + extraction + start card (3 days), inline cards + progress (2 days), thread commands
(1–2 days).

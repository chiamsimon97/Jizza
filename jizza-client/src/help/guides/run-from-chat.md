---
title: Run a workflow from chat
summary: Type /run and a workflow's name to start it at once, without Jizza thinking about it and without using any AI tokens.
section: Automation
order: 15
updated: 2026-11-12
---

## What it is

`/run` starts one of your saved workflows straight from the chat box. Jizza does not work anything out first, so it is instant, costs no AI tokens, and still works when Jizza has run out of budget for a reply.

## Running a workflow

1. In the chat box, type `/run` and a space. Your workflows are listed above the box.
2. Keep typing part of the name to narrow the list, then press **Enter** (or click the workflow).
3. The run starts. The reply says how it went and links to **View run log**.

You can also type the whole command yourself and send it, such as `/run Simon workflow`. Capital letters don't matter, and part of the name is enough when only one workflow has it. When several workflows could be meant, Jizza lists them and you tap the one you want.

## Workflows that take inputs

When a workflow takes inputs, picking it puts a line for each one in the chat box. Fill them in and send:

```
/run Ticket booking
movie: Ramayana
seats: 2
```

If you leave out one it needs, the reply shows the command again with the lines to fill in.

## Good to know

- Typing `/run` is your go-ahead, the same as pressing **Run** on the workflow, so Jizza does not ask again. A workflow you turned off, or one that reached its runs-per-day limit, still won't run.
- Questions a run asks (an approval, a choice) arrive in **Notifications**, as with any run.
- To have Jizza pick the workflow from a description such as "book the movie", ask in your own words instead. That uses AI tokens.

---
title: Run a workflow from chat
summary: Ask in your own words ("book a 3A ticket Chennai to Delhi on 20 Dec") and check the start card, or type /run and a workflow's name to start it at once.
section: Automation
order: 15
updated: 2026-11-16
---

## Ask in your own words

Tell Jizza what you want done, the way you'd say it to a person:

> Book a 3A ticket from Chennai to Delhi on 20 Dec for me and my mother

Jizza looks through your saved workflows for the one that does this job (by its name, what it's for, the sites its steps open, and the example requests it keeps), reads the details you gave, and replies with a **start card**:

- each detail the workflow takes, filled in from your message. Change anything that's wrong. A value that doesn't fit (a class the site doesn't offer, too many passengers) is marked in red with the reason.
- what is still needed before it can start, marked with a red star.
- what it will ask you **while it runs**, when the page shows the choices (the train list, berths).
- which browser runs it. It starts with the browser the workflow was recorded in, when that one is connected.

Nothing runs until you press **Start**. **No thanks** closes the card.

Once started, the card follows the run in the chat: the steps as they finish, and any card the run needs you for (a choice from the page, a CAPTCHA, a code, a hand-over for payment) right there. Answering it in the chat or in **Notifications** is the same thing. **Stop** ends the run.

While it runs, or once it's done, you can ask in the same chat:

- "stop it" or "cancel" to end it
- "how is it going?" or "status" to see where it is
- "what's the PNR?" to read something the workflow gives back, once it's finished

Reading your request takes one small AI call, a few thousand tokens. A message that isn't asking to run a workflow goes to Jizza as usual.

### Teach Jizza how you ask

When you save a recording, Jizza suggests what it's for and a few ways you'd ask for it ("book a train ticket", "IRCTC booking"), so a recording named "Simon" is still found by "book a train". You'll see them under **Reuse by Jizza** on the workflow. **Suggest again** makes new ones, for older workflows too.

## /run: start one straight away

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
- To have Jizza pick the workflow and read the details from a description such as "book the movie for 2 tonight", ask in your own words instead (see above). That uses one small AI call.

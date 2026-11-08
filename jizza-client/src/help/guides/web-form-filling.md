---
title: Filling forms and choosing at run time
summary: Fill web forms with values from earlier steps, check them on the approval card, and pick a card or date from what a page shows when the run gets there.
section: Web automation
order: 15
updated: 2026-11-08
---

## What it is

Real web tasks rarely use the same values twice. A booking form needs today's name and phone number; a ticket site lists different movies and dates every day. These steps let a workflow fill a form with values from earlier steps, and choose a card or date from what the page lists when the run reaches it.

## Fill a form with values from the workflow

Add **Fill form** from the Web Automation steps. Add one row per field:

- **Field**: pick it on the page. For a checkbox group or radio buttons, pick the group (or any box in it).
- **Kind**: Text or text area, Dropdown, Checkbox, Checkbox group, Radio buttons, or Date or date and time.
- **Value**: type it, or map it from earlier steps with a data chip. A checkbox takes yes or no; a checkbox group takes the boxes to tick, comma-separated (the others are unticked).
- **Mandatory**: when its value comes out empty, the run stops and asks you to fill it in, instead of sending an empty field.

An optional field whose value comes out empty is left as the page has it.

The single steps take mapped values too: **Set text**, **Select item** (items comma-separated, or a list), **Set checkbox** (choose "Map from earlier steps…" and map yes or no), **Check items** for a checkbox group, and **Set date**.

### Dates

**Set date** and date fields in Fill form read dates written any common way: `2026-11-09`, `9 Nov 2026`, `Nov 9, 2026`, `09/11/2026` (day first), with an optional time such as `18:30` or `6:30 PM`. Date pickers built into the browser get the form they need. For a plain text box, give the site's **date format**, such as `DD/MM/YYYY` or `DD MMM YYYY, hh:mm A`.

A date strip of buttons (Sun 9, Mon 10…) is a list: use Choose from a list for it.

## Check and change values before a step runs

When a step's guardrail is **Ask me**, its request in [Notifications](/notifications) shows the step's values as they came out for this run, with the mapping each came from (for example "from {{ input.movie }}"). Change any of them before you approve; the step uses what you approve.

When a mandatory value is empty, it is marked in red and **Approve** stays off until you fill it in. Type it, or choose **Use a value from this run** to take one from the trigger or an earlier step. This happens even when the step is allowed to run on its own, because it can't run without the value.

## Choose a card or date at run time

You recorded clicking the "Ramayana" card, but tomorrow the page lists other movies. Open that Click step and tick **Choose the item at run time**. The step becomes **Choose from a list**:

- **One item of the list**: the card you recorded. The step uses it to find the whole list, even when that card is gone.
- **Item**: which one to click, usually mapped from earlier steps, such as the movie named when the workflow was started.

When the run gets there, the step reads the list as the page shows it at that moment. Then it follows the step's guardrail:

- **Ask me**: your approval lists the cards on the page now. Pick one and approve. The one the run asked for is already picked when the page lists it.
- **Let Jizza decide** (or **Auto**): Jizza clicks the card the run asked for when the page lists it. Matching ignores case and small differences, so "ramayana" finds "Ramayana (Hindi)". When the page doesn't list it, or two cards match equally, you get the approval with the list instead.

Dates work the same way: record clicking a date, tick **Choose the item at run time**, and map the date you want.

**List items** reads the same kind of list into data (each item's name and text) for later steps, without clicking.

## Good to know

- The browser extension needs version 0.2.0 or newer for these steps. Older versions refuse them.
- A Choose from a list step always runs on its own, never batched with other steps.
- What you change on an approval card applies to that run only. To change the step for every run, edit it in the workflow.

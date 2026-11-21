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
- **Kind**: Text or text area, Dropdown, Checkbox, Checkbox group, Radio buttons, Date or date and time, Calendar the site draws, or Search and choose (a field that shows suggestions as you type).
- **Value**: type it, or map it from earlier steps with a data chip. A checkbox takes yes or no; a checkbox group takes the boxes to tick, comma-separated (the others are unticked).
- **Mandatory**: when its value comes out empty, the run stops and asks you to fill it in, instead of sending an empty field.

An optional field whose value comes out empty is left as the page has it.

Each row shows its name, **Value** and **Mandatory**; **Field**, **Kind**, **Name on approvals** and **Date format** are under **More options**, which opens by itself when a field still needs picking.

Set **Send button** to have the step press it after filling. The step then reads what the site says is wrong (such as "Enter a 10-digit number" under the phone field). When the site refuses the form, the run stops and your approval shows each message next to its value. Fix the values and approve, and the form is filled and sent again.

The single steps take mapped values too: **Set text**, **Select item** (items comma-separated, or a list), **Set checkbox** (choose "Map from earlier steps…" and map yes or no), **Check items** for a checkbox group, and **Set date**.

### Dates

**Set date** and date fields in Fill form read dates written any common way: `2026-11-09`, `9 Nov 2026`, `Nov 9, 2026`, `09/11/2026` (day first), with an optional time such as `18:30` or `6:30 PM`. Date pickers built into the browser get the form they need. For a plain text box, give the site's **date format**, such as `DD/MM/YYYY` or `DD MMM YYYY, hh:mm A`.

### Calendars and date strips

Many sites draw their own calendar instead of the browser's date picker. Use **Pick a date in a calendar** (or the Calendar kind in Fill form): pick a day in the calendar as the element, and the field that opens it under **Opens the calendar**. The step opens it, moves month by month to the date, and clicks the day.

A date strip of buttons (Sun 9, Mon 10…) is a list: use **Choose from a list** and map the date. Any common way of writing the date finds its day, and so do "today" and "tomorrow".

### Fields that suggest as you type

For a city or theatre search box, use **Type and choose** (or the Search and choose kind): it types the text, waits for the suggestions and clicks the one that matches. When none does, you get an approval listing the suggestions to choose from.

## Check and change values before a step runs

When a step's guardrail is **Ask me**, its request in [Notifications](/notifications) shows the step's values as they came out for this run, with the mapping each came from (for example "from {{ input.movie }}"). Change any of them before you approve; the step uses what you approve.

When a mandatory value is empty, it is marked in red and **Approve** stays off until you fill it in. Type it, or choose **Use a value from this run** to take one from the trigger or an earlier step (such as text read from a page). It lists only values worth putting in a field: not a browser step's own details, such as its session, tab, address or yes/no results. This happens even when the step is allowed to run on its own, because it can't run without the value.

## Choose a card or date at run time

You recorded clicking the "Ramayana" card, but tomorrow the page lists other movies. Open that Click step and tick **Choose the item at run time**. The step becomes **Choose from a list**:

- **One item of the list**: the card you recorded. The step uses it to find the whole list, even when that card is gone.
- **Item**: which one to click, usually mapped from earlier steps, such as the movie named when the workflow was started.

When the run gets there, the step reads the list as the page shows it at that moment. Then it follows the step's guardrail:

- **Ask me**: your approval lists the cards on the page now. Pick one and approve. The one the run asked for is already picked when the page lists it.
- **Let Jizza decide** (or **Auto**): Jizza clicks the card the run asked for when the page lists it. Matching ignores case and small differences, so "ramayana" finds "Ramayana (Hindi)". When the page doesn't list it, or two cards match equally, you get the approval with the list instead.

Dates work the same way: record clicking a date, tick **Choose the item at run time**, and map the date you want.

More ways to say which one:

- **Several words**: "Ramayana, Hindi, 3D" picks the card whose text has all three.
- **When several match**: Ask (the default), take the first, or take the cheapest or the dearest by the price on the card. With no item named, the cheapest or first card is taken.
- **Choose inside a card of another list first**: for a showtime of one theatre, pick one theatre card as the outer list and say which theatre. The run finds that theatre, then the showtime inside it.
- **The list loads more as you scroll**: the step scrolls to the end, or presses the **Load more button** you pick, until the list stops growing.

### Choices carried from earlier steps

Several steps often choose inside the same card: one step picks the train and its coach, the next picks a date in that train, the next clicks Book Now in it. Once a step of the run has chosen the train, the later steps that choose inside the train list (the same list name, or the same mapped value such as `{{ input.trains }}`) carry that train on. Their approval doesn't ask for the train again: it shows **Chosen in earlier steps** with the train, and anything else already chosen in it, such as the coach, and asks only for what is new (the date).

When a list only shows its choices after another one is chosen, such as dates that appear once a coach is picked, open the step and set **Loads after** to that list (Coaches). The dropdown names each list with the lists it follows (**Trains › Coaches**), and the step shows the whole order it is chosen in: **Trains › Coaches › Dates**. The approval then lists those choices in the same order (the train, then the coach) as chosen earlier. If the page no longer lists the train chosen earlier, the approval asks for it again from what the page lists now.

When you choose a card on the approval yourself, you can tick **choose this one without asking**. The next time the run asks for the same thing (say "Ramayana"), Let Jizza decide picks your choice on its own while the page still lists it.

When you ask Jizza in chat to run a workflow, it passes what you said into the values the steps read. "Book Ramayana tomorrow" fills the movie and the date when the steps map them, for example `{{ input.movie }}`.

**List items** reads the same kind of list into data (each item's name and text) for later steps, without clicking.

## Good to know

- The browser extension needs version 0.3.0 or newer for calendars, search and choose, cards inside cards, loading more and reading a site's errors (0.2.0 for the rest). Older versions refuse them.
- The Jizza phone apps show the same approval: change values, fill in mandatory ones and pick from the list there too.
- A Choose from a list step always runs on its own, never batched with other steps.
- What you change on an approval card applies to that run only. To change the step for every run, edit it in the workflow.

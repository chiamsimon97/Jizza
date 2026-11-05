---
title: Browser recording
summary: Record a task once in Chrome, review the steps, and run it as a module whenever you need it.
section: Web automation
order: 10
updated: 2026-11-02
---

## What it is

Browser recording turns a task you do once in Chrome into a module that Jizza repeats the same way every time. While you do the task, the Jizza Browser Agent extension writes down each click, typed value and page change. Then you review the steps and save them as an ordinary workflow.

With a saved recording you can:

- **Run it on demand**, from the [Recordings](/recordings) page or by asking Jizza in chat ("download last month's invoices").
- **Use it inside other workflows**, through "Run a module" or "For each", for example once per row of a spreadsheet.
- **Change values per run**: any value you typed (an invoice number, a date, a search term) can become an input.
- **Read data off pages**: pull text, check that something is on the page, or extract a whole table, and pass it to later steps.
- **Schedule it**, once you allow that in the workflow's reuse settings.
- **Edit it later** in the workflow builder, step by step, like any other workflow.

Nothing is decided on the fly. You can see and change every step, and a run stops and asks you when a page no longer matches what was recorded.

### How it fits together

1. **Record in Chrome**: do the task once while the extension writes down the steps.
2. **Review the steps**: rename, reorder, delete, and turn typed values into inputs.
3. **Save as module**: it becomes a workflow with the Recorded origin. It ends with a **Close browser** step; remove that step if you want the page left open after a run.
4. **Run the module** as often as you need.

A run pauses when a page needs a person and goes on after **Done, continue**. When a step can't find its element, the run waits for you to apply a fix, and applying it saves a new version.

## Before you start

You set up three things once: the extension, a paired browser, and the sites it may work on.

1. **Install the extension.** Load Jizza Browser Agent in Chrome or another Chromium browser (such as Ulaa), version 120 or newer.
2. **Pair the browser.** In Jizza, open [Settings › Devices](/settings/devices), press **Pair a device**, open the **Browser** tab, and follow the steps. Paste the key into the extension's popup. The browser then shows as **Online**, and **Test** should answer within a few seconds.
3. **Allow the sites.** In the extension's **Options**, add each site the recorder may work on, as `example.com`, or `*.example.com` to include its subdomains. Chrome asks you to grant access to each one. No site is allowed by default.
4. **Save your logins (optional).** Also in Options, under **Saved logins**, add a name, the site, the username and the password for each site that needs a sign-in. They stay encrypted in the browser. Jizza only ever sees the login's name.

Keep the browser open while you record or run, because the module runs in that browser, not on the server.

## Step by step: record a task and test it

This is the full procedure, checked end to end against a running Jizza server and the extension on 2 Nov 2026. It records a short task, saves it as a module and replays it.

### A. Check the browser is ready

1. Open the browser that has the Jizza Browser Agent loaded, and keep it open the whole time.
2. In Jizza, open [Settings › Devices](/settings/devices). Your browser should show **Online**. Press **Test**: it should answer within a few seconds. If it says it is not connected, reload the extension on the browser's extensions page (`chrome://extensions`) and press **Test** again.
3. Click the Jizza icon in the toolbar, open **Options**, and check that the site you want to record is in **Allowed sites** (for example `portal.example.com`). If you add it now, accept Chrome's prompt to grant access.

### B. Record

4. In Jizza, open [Recordings](/recordings) and press **Record**.
5. Choose your browser under **Browser agent**, type the full **Start page** address starting with `https://` (or `http://`), give the recording a **Name**, and press **Record**.
6. A new tab opens on the start page in the browser. Jizza shows "Recording in Chrome on …" with a red dot. Wait for the page to finish loading before you start.
7. Do the task at a normal pace. Click into a field, type, then click the next thing or press Tab: the typed value is saved as a step when you leave the field. Click buttons and links, tick boxes, choose from lists.
8. Watch the **Captured steps** list in Jizza (or the extension's side panel, from the browser's side panel button). Each action appears within a second or two. If nothing appears after your first click, see the last part of this section.
9. When the task is done, press **Stop** in Jizza.

### C. Review and save

10. The review opens. Check every step reads the way you expect. Delete stray clicks, rename steps, and use **Make this an input** on any typed value you want to change on each run.
11. Check the **Module name**, add a short **Description**, and press **Save as module**.

### D. Test the module

12. The module now shows under saved recordings on the [Recordings](/recordings) page. Keep the browser open and press **Run now**.
13. The browser opens the start page again and Jizza replays the steps in order. Before each step the extension brings the run's tab to the front, because some sites (for example a booking site's "Select language and format" dialog) only draw part of the page while their tab is visible. You can watch the run in Jizza; the browser tab comes back to the front at the next step.
14. Each step that changes the page (typing, clicking, ticking) goes through your guardrails, so the first run usually stops before such a step and asks you. Open [Notifications](/notifications) (or [Run history](/runs) and the run) and approve the step. The run carries on to the next step. To stop being asked for a step, open the workflow's **Guardrails** on the [Workflows](/workflows) page and set that step to **Auto** for the agent that leads it (see "Who runs it and which steps ask" below).
15. Open [Run history](/runs). The run should end as **Done**, with every step ticked. A failed step shows what went wrong in words, and a screenshot when the browser took one.

### If recording does not start or captures nothing

- **"Nothing was captured. Try again, and check Chrome is open on the page" straight after you press Record.** The browser refused the recording. Check the browser is **Online** and **Test** answers. Before the extension fix of 2 Nov 2026 every recording failed this way, so update the extension and reload it.
- **A tab opens but no steps appear.** The site is not in **Allowed sites**, or Chrome was not given access to it. The side panel says "Not recording on this site".
- **Steps appear only for clicks.** Typed text is saved when you leave the field, so click elsewhere or press Tab after typing.
- **The run waits and nothing happens.** It is waiting for you to approve a step. See step 14.

## Record a task

A recording runs from **Record** to **Stop**, and gives you a list of steps to review.

1. In Jizza, open [Recordings](/recordings) and press **Record**.
2. Choose the **browser agent**, a **start page** (for example `https://portal.example.com/invoices`) and a **name** for the recording.
3. Chrome opens the start page in its own tab group, and the extension's side panel lists each step as you go.
4. Do the task the way you normally would: click, type, pick from lists, tick boxes, switch tabs.
5. To capture something the page shows rather than something you do, use the side panel:
   - **Add check**: pick an element to check that it is there, or to read its text.
   - **Add table**: pick a table to extract its rows.
   - **Mark hover**: pick an element the run should hover over, such as a menu.
6. If you make a stray click, delete it in the side panel. You can also drag steps into a different order there.
7. Press **Stop**, in the side panel or in Jizza. Closing the last tab of the recording also stops it.

You can also start a recording in two other ways:

- **From the workflow builder**: choose **Record steps here** in the add-step list. The steps go straight into the workflow you are editing.
- **From chat**: tell Jizza you want to show it a task. It starts the recording and shows a card with **Stop** and **Open review**.

## What gets recorded

Each thing you do becomes one named step. Steps inside frames (embedded pages) are recorded with their frame, so they replay in the same place.

| What you do | Step you get |
| --- | --- |
| Type an address or open a bookmark | Open URL |
| Click a button or link (also double-click, Ctrl/Shift-click) | Click, on the control itself |
| Type in a field | Set text, one step per field when you leave it |
| Type a password, one-time code or card number | Set text marked as a password; the value is never read |
| Press a shortcut, Escape, or an arrow key outside a field | Send hotkeys, for example `Control+K` |
| Tick or untick a box | Set checkbox |
| Choose from a drop-down or a custom list | Select item |
| Choose a radio button | Select radio |
| Scroll more than a screen and pause | Scroll |
| Back, Forward, Reload | Go back, Go forward, Refresh |
| A popup opens, or you move to another tab | Switch to |
| Close the last tab | Close browser |
| Add check, Add table, Mark hover (side panel) | Check if element exists or Get text, Extract table data, Hover |

Each step stores several ways to find its element (its label, text, role and position), plus a small picture of it, so you can tell steps apart in review.

## Review and save

After you press **Stop**, the recording waits for you in review. Nothing runs until you save it.

- **Rename** a step so it reads like an instruction, such as "Open the invoice search".
- **Move up / Move down** to change the order, and **Delete** steps you don't need.
- **Make this an input** on a typed value. Give it a name and a type. The value you typed becomes the default, and each run can pass a different one.
- **Saved login** on each password step: enter the saved login to use. A recording with a password step can't be saved without one. (For now this is a text field for the login's id; a picker is planned.)

Then give the module a name and a short description, and press **Save as module**. The description helps Jizza find the module when you ask for it in chat.

Drafts you leave in review for 7 days are removed.

## Use a saved recording

A saved recording is a workflow with the **Recorded** origin, so it works anywhere a workflow does.

| Where | How | Good for |
| --- | --- | --- |
| Recordings page | **Run now** on the saved recording | A one-off run while you watch |
| Chat | Ask for the task in your own words; Jizza offers the matching recording and runs it after you say yes | Doing the task without opening anything |
| Another workflow | **Run a module** step, passing its inputs | One step of a bigger process |
| Many items | **For each** over a list, running the module once per item | The same task for every invoice, order or row |
| Schedule | First turn on scheduled runs in the workflow's reuse settings | Daily or weekly jobs |
| Workflow builder | **Open in builder** | Adding steps, conditions or data mapping |

In the builder, the **Web Automation** group has the same steps the recorder makes, so you can also add steps by hand. Each step that needs an element has **Pick on page** (click the element in Chrome) and **Test step** (run that one step).

What a step reads, such as a table's rows or an element's text, is available to later steps through data mapping, like any other step output.

By default, a saved recording asks before each run and does not run on a schedule. You can change that in the workflow's reuse settings.

## Who runs it and which steps ask

Each workflow has a **Guardrails** panel on the [Workflows](/workflows) page.

- **Maintained by**: pick one or more agents, the same way you pick agents for a connector. The first one you pick **leads**: its guardrails decide every run of this workflow. With none picked, Jizza (or the agent that made the workflow) leads.
- **Each step, per agent**: every step that acts on your apps or your browser is listed, with what each maintaining agent's guardrails will do: **Auto**, **Ask me**, **Let Jizza decide** or **Never**.
- **Skip the approval for a step**: set that step to **Auto** for the lead agent. The run then goes on without asking you at that step. It covers this version of the workflow only; after you edit the steps, they are asked about again.

Changing who maintains a workflow, or a step's mode, asks you to confirm it's you.

## When the browser closes

The browser closes only because of a **Close browser** step. A new recording ends with one. Remove it in the workflow builder if you want the page left open after a run, for example to pay for tickets yourself. A run that fails or is cancelled still closes the tabs it opened, because its steps never reached the end.

Recordings saved before 2 Nov 2026 have no Close browser step, so their pages now stay open after a run. Add the step from **Web Automation** in the builder if you want them closed.

## Sign-ins and when Jizza needs you

Passwords never go into a recording. A password step types from a saved login, and only into a password field on that login's own site.

Some pages need a person: a CAPTCHA, a one-time code, a "confirm it's you" check, or a sign-in form when no saved login fits. When a step can't go on because of one of these, the run pauses and:

1. You get a notification, "Jizza needs you". Clicking it brings up the tab.
2. The side panel shows a card that says what the page needs.
3. You finish that part yourself in the tab, then press **Done, continue**.
4. The step runs again from the start and the run carries on.

If nobody responds before the step's time limit, the run stops as timed out. Cancelling the run also ends the wait.

## Safety and privacy

The recorder only watches what you allow, and you can stop it at any time.

- **Only allowed sites.** Steps on a site that isn't in your allowed list are not recorded, and the side panel says "Not recording on this site". Browser settings pages, extension pages and the Chrome Web Store are never recorded or automated.
- **Only its own tabs.** The recorder watches only the tab group it opened, not your other tabs.
- **Only real actions.** Only the clicks and keys you actually make are recorded, never ones a page fakes.
- **No secrets stored.** Password, one-time-code and card fields are never read. They are blurred in every screenshot and step picture, and if the server ever receives one of their values, it removes it.
- **Your approval.** Steps that change a site (submitting, typing) go through your [guardrails](/guardrails) like any other action. In chat, clicking or typing in your browser always asks first.
- **Stop everything.** **Pause automation** in the extension popup refuses all commands until you resume. **Stop all** (Alt+Shift+S) ends whatever is running now.

## Advanced web steps and options

Besides recording, you can add web steps by hand from **Web Automation** in the step list. A few
do more than a recording can:

| Step or option | What it does |
| --- | --- |
| **Handle pop-up dialogs** | Answers the page's OK/Cancel and "type a value" pop-ups for you until the page changes. Put it before the step that makes the pop-up appear, otherwise that step waits for a person. |
| **Drag and drop** | Drags one element onto another (**Drop onto**). Turn on **Trusted input** for sites that only react to a real mouse. |
| **Wait** for text or an address | Waits until some text shows (or goes away), or until the page address contains a value, for example after a payment page redirects. |
| **Advanced options › Highlight the element** | Outlines the element for a moment before acting, so you can watch what the run does. |
| **Advanced options › Tries before giving up** | How many times the step runs before it counts as failed (default 3), and how long to wait between tries. |
| **Advanced options › When it still fails** | **Ask me what to do** (the default) or **Stop the run**. |
| **Edit selectors › Inside frames / shadow roots** | For elements inside an iframe or a web component: the iframes or component hosts that lead to it, as CSS selectors. Picking on the page fills these in for you. |

These need the browser extension from 5 Nov 2026 or later.

## Limits and troubleshooting

### Limits

| Limit | Value |
| --- | --- |
| Longest recording | 30 minutes |
| Steps per recording | 500 |
| Typed text per step | 4 KB (longer text is cut and flagged) |
| Drafts left in review | removed after 7 days |

### When a page changes

Sites change, and a step may no longer find its element. When that happens, the run stops at that step and Chrome looks for similar elements (same kind, similar text and position). You get a notification such as "Step 4 of Supplier invoices could not find Submit order. Review a fix". **Review fix** opens the workflow on that step. Choose the right element and press **Apply fix**, or press **Dismiss**. Jizza never picks one for you. Applying saves a new version, and the old ways of finding the element stay as fallbacks.

### Common problems

| What you see | What to do |
| --- | --- |
| "Not recording on this site" in the side panel | Add the site in the extension's Options, then grant access when Chrome asks |
| Test says "It is not connected right now" | Keep the browser open, reload the extension on its extensions page, and check the server address it was paired with |
| A step is missing after a scroll | This is on purpose: scrolls shorter than a screen, or followed by another step within 2 seconds, are left out |
| "Choose a saved login for each password step" | Add the login in Options › Saved logins, then enter it on that step in review |
| A run stops at a sign-in, CAPTCHA or code | Finish it in the tab and press **Done, continue** in the side panel |
| A run waits on a step until you open its tab | Update the extension: since 2 Nov 2026 it brings the run's tab to the front before each step. Also keep the browser window open, not minimised |
| The run history shows a failed web step | Open it. It says what went wrong and, when the browser took one, shows a screenshot of the page |

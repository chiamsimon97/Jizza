# Web automation: a short guide

Web automation lets a workflow work in your own Chrome, with the same steps every time. Nothing
decides on the fly; each step is one you can see and change.

## Add a browser agent

1. Install the Jizza Browser Agent extension in Chrome.
2. In Settings › Agents choose **Create agent**, then the **Browser** tab, and follow the steps.
3. The agent shows as online when Chrome is open.

## Build with steps

In the workflow builder open the **Web Automation** group: Open URL, Click, Set text, Get text,
Extract table data and more. Each step with an element has **Pick on page**: Chrome opens, you
click the element, and the step stores how to find it. **Test step** runs it once.

## Record a task

1. Open **Recordings** and press **Record**. Choose your browser, a start page and a name.
2. Do the task in Chrome. Press **Stop** when done.
3. Review the steps: rename, reorder or delete them. Use **Make this an input** for a value you
   want to change on each run, such as an invoice number. Choose a **saved login** for each
   password; passwords are never stored in the recording.
4. **Save as module.** It is an ordinary workflow you can run, schedule, use in "Run a module" or
   "For each", and open in the builder.

In the builder, **Record steps here** (in the add-step list) records straight into the workflow
you are editing.

### Record from chat

Tell Jizza you want to show it a task. It starts a recording in your browser agent and puts a card
on the screen with **Stop** and **Open review**. Do the task in Chrome and press **Stop**; **Open
review** takes you to the review of that recording.

If a saved recording already does what you ask, such as "download last month's invoices", Jizza
offers to run it and runs it only after you say yes. Clicking and typing in your browser from chat
always asks for your approval.

## When a page changes

Sites change. When a step can no longer find its element, Chrome looks for elements that look like
it (same kind, similar text and position) and suggests them. Jizza never picks one for you:

1. The run stops at that step, and you get a notification such as "Step 4 of Supplier invoices
   could not find Submit order. Review a fix".
2. **Review fix** opens the workflow on that step. The run history shows the same link, with the
   error in words and a screenshot of the page.
3. Choose the element that is the right one and press **Apply fix**, or **Dismiss** if none is.

Applying saves a new version of the workflow. The step tries the element you chose first and keeps
its old ways of finding the element as fallbacks, so it still works if the page changes back. A
workflow that was reviewed goes back to review with the change. There is at most one open fix per
step; if the step fails again before you decide, the suggestions are refreshed.

Two options are off unless your server turns them on:

- `jizza.webauto.ai-fix-ranking`: the small model orders the suggestions by comparing labels and
  nearby text. It only changes the order you see.
- `jizza.webauto.fast-replay`: a module made only of web steps is sent to Chrome as one batch,
  which is quicker. Steps that need your approval, or that use an earlier step's output, still go
  one by one, and so does everything after a step that fails. It needs a browser agent that offers
  `browser.runSteps` (extension milestone B9).

## Good to know

- A saved recording asks before each run and does not run on a schedule until you allow it.
- Steps that change a site (clicks that submit, typing) go through your guardrails like any other.
- Drafts you leave for 7 days are removed.
- A failed web step shows in the run history with what went wrong in words and a screenshot of the
  page, when the browser took one.
- Passwords come from saved logins and are never stored in a recording or a workflow.

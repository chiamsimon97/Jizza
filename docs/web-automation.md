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

Tell Jizza you want to show it a task. It starts a recording in your browser agent and puts a card on the screen with **Stop** and **Open review**. Do the task in Chrome, press **Stop**, then open the review. If a saved recording already does the job, Jizza offers to run it and asks you first. Clicking and typing in your browser from chat always asks for your approval.

## Good to know

- A saved recording asks before each run and does not run on a schedule until you allow it.
- Steps that change a site (clicks that submit, typing) go through your guardrails like any other.
- Drafts you leave for 7 days are removed.

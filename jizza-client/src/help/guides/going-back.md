---
title: Going back to choose again
summary: Let a step's notification offer Back beside Approve, so you can return to an earlier choice (another train or coach) when the next page shows nothing available.
section: Web automation
order: 21
updated: 2026-11-20
---

## When to use it

Some runs make a choice, then show you what that choice leads to. For example: choose a train, then a coach, then the next step shows that coach has no seats. **Allow going back** puts a **Back** button on that step's notification, beside Approve, so you can go back and choose a different train or coach without stopping the run.

## Turn it on

1. Open the workflow and click the step whose notification should offer Back (the step after the choices, such as **Check availability**).
2. Tick **Allow going back**.
3. Choose where Back goes:
   - **The steps that asked me** (the default): the card offers every earlier step of this run that stopped to ask you, the latest first. You pick one on the card.
   - **A specific step**: Back always goes to that step.
4. For a web step, choose what the browser does first:
   - **Stay on this page** when the list you chose from is still on the page (most single-page sites, such as a train list that opens availability inline).
   - **Go back one, two or three pages** when your choice opened a new page and the list is on an earlier one.
5. Save the workflow.

## On the notification

The step's card shows **Back to Choose coach** (or whichever step is first). With more than one step to go back to, pick it in the list next to the button.

When you press Back:

- The step you went back to runs again. It reads the list from the page as it is now and **always asks you**, even if it would normally choose by itself, with your earlier choice filled in.
- Every step after it runs again in order, so the steps that follow your new choice ask you again where they normally do.
- The notification you answered is filed as "Go back to …".

Back works on a step's approval card and on its "could not complete" card. It is offered only while the run is waiting on that step.

## Good to know

- Going back does not undo anything the site already did. Use it before steps that pay, book or submit, not after them.
- Steps that run on their own (no question) are simply run again.

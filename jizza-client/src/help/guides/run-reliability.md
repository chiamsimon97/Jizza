---
title: Waiting for the page, and steps that can't be undone
summary: Wait for whatever the page shows next (results or a "nothing found" message), let runs wait out loading spinners, and keep Pay or Place order from ever happening twice.
section: Web automation
order: 19
updated: 2026-11-16
---

## Wait for what happens

After a step like **Search**, **Next** or **Apply coupon**, a page can go more than one way: a list of results, "No trains found", "Seats not available", an error. A **Wait for what happens** step waits until the page shows one of them and notes which one it was.

While recording:

1. Do the step that loads something (for example, click **Search**).
2. In the Jizza side panel, choose **Wait for what happens…**, type a name for what you expect (for example `Results`) and press **Point at it**.
3. Click the thing on the page that shows it is ready, such as the first result.

In the recording's review you can then:

- **Add what else can happen**: a name and a piece of text the page shows, such as `Sold out` and `Not available`.
- Choose what the run does for each one: **Carry on**, or **Stop the run** with a message such as "No trains that day". A stopped run shows that message on its card, where you can try again, skip or stop.

The step waits up to 30 seconds. If nothing it knows about appears, it fails and names what it was waiting for.

## Loading spinners

Before each step that clicks or types, the browser waits for the page to finish loading and for any full-page "Please wait" overlay or spinning loader to go. It waits at most 10 seconds (and never more than half of the step's own time), then goes on with the page as it is. You don't need to add fixed waits for these.

## Steps that can't be undone

Paying, placing an order, confirming a booking or sending money must never happen twice. Jizza marks a recorded click on buttons like **Pay now**, **Place order**, **Confirm booking** or **Buy now** as **Can't be undone**. You can mark or unmark any step yourself in the recording's review with the shield button.

For a step marked this way:

- A run never sends it a second time on its own, even if the browser lost touch half-way.
- If it fails, its card asks you to check the site first (your orders or bookings). It offers **I checked, it did not go through: try again** instead of **Try again**, and doesn't take a written note.
- If nobody answers the card, the run stops rather than trying again.

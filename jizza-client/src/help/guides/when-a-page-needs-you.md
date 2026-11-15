---
title: When a page needs you
summary: Answer a CAPTCHA or a one-time code from a card in Jizza, ask yourself a question mid-run, and hand payments over to yourself.
section: Web automation
order: 18
updated: 2026-11-15
---

## What it is

Some things in a web task only you should do: read a CAPTCHA, type the code a bank texts you, or pay. A run doesn't fail on them and doesn't try to get around them. It stops, asks you, and carries on with your answer.

You can answer in two places, and whichever comes first is used:

- **On a card in Jizza** (the inbox, or wherever you follow the run). It shows a picture of the CAPTCHA, a box for your answer, and how much time is left before the page gives up.
- **In the browser**, in the Jizza side panel, after doing it on the page yourself: **Done, continue**.

## CAPTCHAs and one-time codes

When a step finds a CAPTCHA or a code box on the page, the run asks you:

1. The card shows the picture the page shows, cut out of the page.
2. Type what you see and press **Send**. Jizza types it into the page's box and the step goes on.
3. If you'd rather do it in the browser, do it there and press **I did it in the browser**.
4. **Stop** ends the step, and the run handles it like any failed step.

A click on a form's **Sign in** or **Submit** while the form's CAPTCHA box is still empty asks you first, so the site never sees the form without it.

Jizza never solves a CAPTCHA itself: it doesn't read the picture or send it to a solving service. What you type is typed into the page and kept nowhere: not in the run, not in the inbox, not in the logs.

## Ask me

**Ask me** is a step you place anywhere in a workflow (in the builder, from **Web Automation › Ask me**):

- **What to say**: the question on the card ("Type the code sent to your phone").
- **Picture to show**: optional, any part of the page (a CAPTCHA, a seat map, a total).
- **Box for the answer**: optional. With it, your answer is typed there. Without it, the answer is the step's output for later steps, unless it's secret.
- **Secret answer**: on by default. A secret answer is never shown again or stored.
- **Ask for an answer**: off makes the card ask only for **Done**.

## Hand over to me

**Hand over to me** is the step to put before a payment page. It brings the tab to the front and waits while you pay. Jizza types and clicks nothing meanwhile, and records nothing.

The run carries on by itself when the page shows the result:

- **Done when the address contains** (for example `/booking/confirmed`, `*` matches anything) or **Done when the page shows** a text such as "Booking confirmed".
- **Failed when…**: the same, for a failure page.
- Or press **Done** on the card or in the side panel.

Its output says which happened (`success`, `failure` or `done`), so a **Paths** step can branch on it.

Jizza never fills payment details. A step that would type into a card number, security code, expiry, or UPI or ATM PIN box stops with "Jizza does not fill in payment details".

## Safety

- Every message the browser sends to Jizza is signed with that browser's own key, and Jizza's answers to it are signed by the server.
- A recorded step that types a value only does so on the site it was recorded on. If the tab shows another site, the step stops without typing. You can change this in the step's **Only on this site** setting.

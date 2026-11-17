---
title: Results, uploads and downloads
summary: Read a price or date from a page as a real value, give a run its one-line answer, upload a photo or document, and keep the files a site gives you.
section: Web automation
order: 20
updated: 2026-11-16
---

## Read text as a value

A **Read text** step keeps the words it read. To use them in a later check (for example "if the price is 500 or less") or in the run's answer, tell Jizza what they are. In the recording's review, next to a Read text step, choose **Read as**:

- **Number**: "1,234.50" or "1.234,50" becomes 1234.50.
- **Amount of money**: the same, and the currency is noted (₹, $, €, S$ and others).
- **Date**: "20-Dec-2025" or "Sat, 20 Dec 2025" becomes a date. If the site writes dates its own way, type its format, for example `dd-MMM-yyyy`.
- **Part of it (pattern)**: picks one part out, for example `PNR:\s*(\d{10})` takes the ten digits after "PNR:".

If a run reads something that doesn't fit, the step still finishes. The value is left out and the run log says why.

## The run's answer

When a workflow has outputs, its **End** step can have a **Result message**, such as:

```
Booked. PNR {{ result.pnr }}, fare {{ result.fare }}
```

When you run it from chat, this message is the answer you get, with the outputs listed under it and links to any files the run downloaded.

## Upload a file

When you pick a file on a page while recording (a photo, an ID scan, a CV), Jizza records an **Upload** step. It doesn't keep the file you picked. Instead the workflow gets an input of type **File**, and you add the file each time you run it: on the run form, or on the start card in chat.

The site's file box can be hidden behind a styled button or be a "drop files here" area. Jizza finds the real box from the button or label you clicked. If the site only takes some file types, a file of another type is refused with a clear message.

If the site has rules for pictures, such as "JPEG, under 50 KB, 200 × 230 pixels", set them on the Upload step in the review under **If it is a picture, fit it to**. Your browser makes the picture smaller to fit before uploading it. It never makes a picture larger, and it keeps the shape of the picture. Files over 10 MB are not uploaded.

## Downloads

If a click starts a download while you are recording (for example **Download ticket** or **Export**), the step becomes a **Download** step. When the workflow runs, the file is kept with Jizza and becomes one of the workflow's results. The run's answer in chat links to it.

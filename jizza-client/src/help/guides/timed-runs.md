---
title: Runs at an exact time, and looking again until something shows
summary: Make a step wait for the moment a booking or sale opens, by the site's own clock, or keep checking a page until a slot or a price appears.
section: Web automation
order: 21
updated: 2026-11-16
---

## Wait until an exact time

Some things open at a set moment, such as a booking window at 10:00 or a sale at noon. A **Wait until** step holds the run until then. Put it right before the step that has to happen on time, for example just before **Book now**. Do all the slow steps before it: signing in, filling in the journey and the passengers.

In the workflow builder, add a **Wait until** step and set:

- **at**: the date and time, for example `2026-12-01T10:00`.
- **timeZone**: the time zone that time is in, for example `Asia/Kolkata`. You can leave it out if the time already includes its offset, for example `+05:30`.
- **clock**: `site` (the default) goes by the site's own clock, which can be a few seconds different from your computer's. `device` goes by your computer's clock.
- **leadMs**: how many milliseconds early to go on, to allow for the time the next click takes to reach the site. Leave it at 0 unless you know you need it.

Until about two minutes before the time, the run waits on Jizza's side. Then your browser gets the step, reads the site's clock, and waits out the rest itself, so a slow connection doesn't make it late. The run log shows how far the site's clock was from yours and how late, in milliseconds, the step went on.

If the time has already passed, the run stops and tells you. Your browser has to be open with Jizza signed in at that time. If it isn't, the run stops and asks you to open it.

## Keep looking until something shows

A **Wait for what happens** step normally looks once and waits up to its time limit. For things that appear at no fixed time, such as an appointment slot, stock coming back, or a price dropping, you can make it keep looking:

1. Open the recording's review.
2. On the **Wait for what happens** step, tick **If none shows, keep looking**.
3. Set how many seconds to wait between looks and how many minutes to keep going.

Each time it looks again, it loads the page fresh first. Jizza never checks a site more often than every 15 seconds. When one of the outcomes shows, the run carries on. If none shows before the time runs out, the run stops and tells you how many times it looked.

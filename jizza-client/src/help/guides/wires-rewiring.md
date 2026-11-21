---
title: Wiring steps
summary: Drag from an exit dot, or drag the end of a wire, to connect steps in the workflow builder, or take a wire off.
section: Automation
order: 26
updated: 2026-11-21
---

## Wire a step

Each step has a small dot on its bottom edge for every way out of it (one dot, or one each for **approved** and **rejected**, **yes** and **no**, and so on).

1. Press on an exit dot and drag. A wire follows the pointer.
2. Let go on the step that should come next. The wire snaps onto a step it can go to, and the steps it can't go to fade.

Let go on empty space instead and Jizza asks which new step to add there (or lets you record it). If that exit already had a wire, the new step goes on that wire, between the two steps.

You can still click an exit dot, then click the step, if you prefer.

## Move a wire to another step

- **Drag the end:** hover a wire. A round handle shows at its arrow. Drag the handle to another step and let go.
- **Drag the exit:** drag the exit dot a wire leaves from to another step. Each exit has one wire, so the old one is replaced.
- **Re-wire button:** hover the wire, click **Re-wire** on its toolbar, then click the new step. Esc cancels.

## Take a wire off

Drag a wire's end handle and let go on empty space, or hover the wire and click the scissors. To take every wire off a step at once, right-click it and choose **Disconnect**.

Hovering a wire also lets you put a step on it (**+**) or give it a label that shows when you hover it.

## What can't be wired

- Nothing can wire into **Start**.
- A wire back to a step that leads here would make a loop, which a workflow can't run. Jizza says so and leaves the wire as it was.
- In a locked step group, the wires between its steps stay as they are. Wires into and out of the group can still be moved. Unlock the group to change the wires inside it (see [Step groups](/help/step-groups)).
- Wires that meet a folded group can't be moved until you unfold it.

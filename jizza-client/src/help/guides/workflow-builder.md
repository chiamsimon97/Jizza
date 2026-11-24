---
title: The workflow builder
summary: Build a workflow by dropping steps onto steps and wires. Undo, copy and paste, full screen, and the keys for each.
section: Automation
order: 24
updated: 2026-11-24
---

## One view

A workflow opens straight into the builder. Steps run top to bottom from **Start**, joined by wires. Every workflow, simple or with branches, paths and service actions, opens here. There is no separate step list or canvas to switch to.

Workflows saved before this change open as they were. Their steps, wires, groups, notes and wire labels are all kept.

## Add steps

- **Drag** a step from **Add a step** on the left onto a step (it goes after it) or onto a wire (it goes between the two steps).
- **Click** a step on the left to add it after the selected step.
- Press **Tab**, click **+** at the top, or right-click empty space and choose **Add a step** to pick one from a searchable list. Choosing an agent adds an agent step that already knows who does it.
- Choose **Record steps here** in that list to record web steps in the browser; they go straight into this workflow.
- Drag from a step's exit dot and let go on empty space to add the step that comes next.

To wire and re-wire steps by hand, see [Wiring steps](/help/wires-rewiring).

## Edit steps

Double-click a step, or hover it and click the pencil, to open its settings. Right-click a step for more:

- **Configure using Jizza**, to have Jizza fill in the step with you.
- **Add note** or **Edit note**. A step with a note shows a small pencil.
- **Clone**, **Copy**, **Delete** and **Disconnect** (takes every wire in and out of the step off).
- **Branch by button clicked**, on a web step that clicks one of several buttons: adds a path for each button.
- **Pick steps from here**, on a step that splits the flow: picks it and every step after it.
- **Group this step**, **Remove from group** and **Lock group** (see [Step groups](/help/step-groups)).

A step with something to fix has a red dot and a red edge; hover it to see why. That includes an agent step whose agent is paused, archived, still a draft or gone.

## Pick several steps

Drag a box on empty space, Shift+click steps, or press **Ctrl+A** (**⌘+A** on a Mac). Picked steps can be grouped, copied, cloned or deleted together.

## Undo, copy and paste

- **Undo** and **Redo** are at the top, and on **Ctrl+Z** and **Ctrl+Shift+Z** (or **Ctrl+Y**).
- **Ctrl+C** copies the picked steps, and **Ctrl+V** pastes them, in this workflow or in another one.
- **Ctrl+D** clones them, and **Delete** or **Backspace** deletes them. Where it can, the steps either side of a deleted step stay joined.

The keys work once you have clicked in the builder, and not while you are typing in a box.

## See the whole workflow

- **Full screen** (top right) gives the builder the whole window. Esc goes back.
- Steps stay where they are when you wire, re-wire, add or delete steps. Only dragging a step or **Auto align** moves them. A new step goes in the nearest free spot.
- **Auto align** lines every step up top to bottom again after you have moved some by hand.
- **Preview path** walks the wires from Start without running anything.
- After a run, the steps it went through are marked **Done**, **Running**, **Waiting for you**, **Failed** or **Skipped**, and arrows follow its path. A folded group shows how its steps did. **Hide on wires** turns this off.
- The keyboard button at the top lists every shortcut.

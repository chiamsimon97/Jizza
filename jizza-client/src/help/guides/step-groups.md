---
title: Step groups
summary: Put steps that belong together in a titled group in the workflow builder, fold it into one card, and lock it so its steps and wiring can't be changed by mistake.
section: Automation
order: 25
updated: 2026-11-21
---

## When to use it

Some steps only make sense together and in one order. In a web workflow, for example, the date can only be chosen after the train and coach are chosen. A **step group** draws a box with a title around those steps in the workflow builder. You can fold it to tidy the workflow, and lock it so a stray drag or a wrong wire can't break the steps inside.

A group is only how the workflow looks in the builder. The steps run exactly as they would without it.

## Make a group

1. Pick the steps: drag a box around them, starting anywhere on empty space and dragging in any direction (hold Shift to add more), or Shift+click each step.
2. Press **Ctrl+G** (**⌘+G** on a Mac), click **Group** at the top, or right-click a picked step and choose **Group steps**.
3. The title opens for typing straight away. Type a title, such as *Choose coach and date*, and press Enter.

To rename a group later, click the pencil on its title bar, double-click the title, or right-click the title bar and choose **Rename**.

Start can't be in a group, and a step is in one group at most. Grouping a step that is already in another group moves it to the new one.

## Lock a group

Click the padlock on the group's title bar. While a group is locked:

- Its steps can't be deleted, and their settings open read-only. The settings show which group locked them, with an **Unlock** link.
- The exit dots and wires between its steps can't be deleted, moved or added to, and no new wire can join two of its steps.
- Dragging any of its steps, or the title bar, moves the whole group.
- The wires into and out of the group stay editable, so you can still connect the group to the steps before and after it (see [Wiring steps](/help/wires-rewiring)).

When a change would touch a locked group, the builder leaves the workflow as it was and says why at the top. Click the padlock again to unlock.

## Fold a group

Click the arrow at the left of the title bar. The group becomes one card showing its title and how many steps it has; wires that went into or out of its steps now meet the card. A red mark on the card means one of its steps has something to fix, and after a run the card shows how its steps did. Click the arrow again to show the steps.

Whether a group is folded and locked is saved with the workflow.

## Other options

Right-click the title bar for:

- **Rename**.
- **Pick its steps**, to copy, clone or delete them.
- **Lock** or **Unlock**, and **Fold** or **Unfold**.
- **Ungroup**, which removes the box and leaves the steps where they are. Unlock the group first. The ungroup button on the title bar does the same.

Right-click a step in an unlocked group and choose **Remove from group** to take just that step out. Drag the title bar to move the whole group.

## Right-click menus

Right-click empty space for quick actions: **Go to Start**, **Preview path**, **Auto align**, **Undo** and **Redo**, **Group steps** for the steps you picked, **Pick all steps**, **Paste**, **Add a step** and **Search steps**. The keys for each are shown beside it.

Right-click a step for its own options, including **Group**, **Remove from group** and **Lock group** (see [The workflow builder](/help/workflow-builder)).

## Good to know

- Undo (Ctrl+Z) undoes grouping, locking and folding like any other change.
- Copying or cloning grouped steps makes plain copies outside the group.

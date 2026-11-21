---
title: Step groups
summary: Put steps that belong together in a titled group on the canvas, fold it into one card, and lock it so its steps and wiring can't be changed by mistake.
section: Automation
order: 25
updated: 2026-11-21
---

## When to use it

Some steps only make sense together and in one order. In a web workflow, for example, the date can only be chosen after the train and coach are chosen. A **step group** draws a box with a title around those steps on the workflow canvas. You can fold it to tidy the canvas, and lock it so a stray drag or a wrong wire can't break the steps inside.

A group is only how the workflow looks in the editor. The steps run exactly as they would without it.

## Make a group

1. Open the workflow in the **Canvas** view (for the **Wires** view, see below).
2. Select the steps: Shift+click each one, or Shift+drag a box around them.
3. Press **Ctrl+G** (**⌘+G** on a Mac), click the group button in the toolbar at the top right, or right-click a selected step and choose **Group steps**.
4. The title opens for typing straight away. Type a title, such as *Choose coach and date*, and press Enter.

To rename a group later, click the pencil on its title bar, double-click the title, or choose **Rename** from its **⋮** menu.

Start can't be in a group, and a step is in one group at most. Grouping a step that is already in another group moves it to the new one.

## Lock a group

Click the padlock on the group's title bar. While a group is locked:

- Its steps can't be deleted, and their settings open read-only. The settings show which group locked them, with an **Unlock** link.
- The wires between its steps can't be deleted, moved or added to, and no new wire can join two of its steps.
- Its steps keep their arrangement. Dragging one step, or the title bar, moves the whole group. **Tidy up** moves the group as one block.
- The wires into and out of the group stay editable, so you can still connect the group to the steps before and after it.

When a change would touch a locked group, the canvas leaves the workflow as it was and says why at the bottom. Click the padlock again to unlock.

## Fold a group

Click the arrow at the left of the title bar. The group becomes one card showing its title and how many steps it has; wires that went into or out of its steps now meet the card. A red mark on the card means one of its steps has something to fix. Click the arrow again to show the steps.

Whether a group is folded and locked is saved with the workflow.

## Other options

The **⋮** on the title bar has:

- **Rename**.
- **Select its steps**, to move, copy or clone them.
- **Lock** or **Unlock**, and **Fold** or **Unfold**.
- **Ungroup**, which removes the box and leaves the steps where they are. Unlock the group first.

Right-click a step in an unlocked group and choose **Remove from group** to take just that step out.

## In the Wires view

Groups show in the **Wires** view too, with the same title bar. They are the same groups as on the Canvas, so a group made, locked or folded in one view looks the same in the other.

- **Make a group:** pick the steps by dragging a box around them, starting anywhere on empty space and dragging in any direction (hold Shift to add more), or Shift+click each step. Then press **Ctrl+G** (**⌘+G** on a Mac) or click **Group** at the top, and type its title.
- **Rename:** click the pencil on the title bar, or double-click the title.
- **Lock, fold, ungroup:** use the padlock, the arrow, and the ungroup button on the title bar.
- **Move:** drag the title bar to move the whole group. In a locked group, dragging any of its steps moves the whole group too.
- In a locked group, the exit dots and wires inside it can't be changed, and its steps have no Delete button.
- Wires into and out of a locked group can still be re-wired: drag an exit dot or a wire's end to another step (see [Re-wiring steps in the Wires view](/help/wires-rewiring)).

## Right-click menus

Right-click empty space in either view for quick actions: **Go to Start**, zoom and **Fit to screen** (Canvas), **Preview path** (Wires), **Tidy up** or **Auto align**, **Group steps** for the steps you picked, **Select all**, and **Search steps**. The keys for each are shown beside it.

Right-click a step for its own options, including **Group**, **Remove from group** and **Lock group**.

## Good to know

- Undo (Ctrl+Z) undoes grouping, locking and folding like any other change.
- Copying or cloning grouped steps makes plain copies outside the group.

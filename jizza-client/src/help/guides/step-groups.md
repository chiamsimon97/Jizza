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
4. Double-click the group's title bar and type a title, such as *Choose coach and date*. Press Enter.

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

- **Select its steps**, to move, copy or clone them.
- **Lock** or **Unlock**, and **Fold** or **Unfold**.
- **Ungroup**, which removes the box and leaves the steps where they are. Unlock the group first.

Right-click a step in an unlocked group and choose **Remove from group** to take just that step out.

## In the Wires view

Groups show in the **Wires** view too, with the same title bar. They are the same groups as on the Canvas, so a group made, locked or folded in one view looks the same in the other.

- **Make a group:** Shift+click each step, then press **Ctrl+G** (**⌘+G** on a Mac) or click **Group** at the top.
- **Rename:** double-click the title bar.
- **Lock, fold, ungroup:** use the padlock, the arrow, and the ungroup button on the title bar.
- **Move:** drag the title bar to move the whole group. In a locked group, dragging any of its steps moves the whole group too.
- In a locked group, the exit dots and wires inside it can't be changed, and its steps have no Delete button.

## Good to know

- Undo (Ctrl+Z) undoes grouping, locking and folding like any other change.
- Copying or cloning grouped steps makes plain copies outside the group.

---
title: Record a task on Windows
summary: Press a hotkey on your PC, do a task once in any Windows app, and save it as a workflow that Jizza repeats.
section: Web automation
order: 20
updated: 2026-11-25
---

## What it is

The Jizza Agent for Windows can record what you do in your own apps, the way the browser extension records web tasks. Press **Ctrl+Alt+R** in any app, do the task once, and press **Ctrl+Alt+R** again. Jizza gets each click, the text you typed into each field, and the shortcuts you pressed as steps. You review them on the [Build › Recordings](/recordings) page and save them as an ordinary workflow.

It works with apps that describe their buttons and fields to Windows, which is how screen readers work. Most apps do: Office, Notepad, File Explorer, and apps built on Win32, WinForms, WPF, Store apps, Qt and Electron. Apps that draw their own controls (some games, older accounting tools, remote desktop windows) show little to Windows and record poorly.

## How to record

1. Make sure the Jizza Agent is running and paired (its icon is in the system tray).
2. Open the app you want to automate and press **Ctrl+Alt+R**. A small bar at the bottom of the screen shows **Recording** and counts the steps.
3. Do the task: click buttons and menus, type into fields, press Enter, Tab or shortcuts such as Ctrl+S. You can move between apps; each switch adds an **Open app** step.
4. Press **Ctrl+Alt+R** again, or **Stop** on the bar. A notification says the recording was sent; click it to open the review.

You can also start and stop from the tray menu (**Record a task**), or stop a recording from the Recordings page.

## Review and save

The review works like a browser recording: rename, reorder or delete steps, and turn a typed value into an input so each run can use a different one.

- **Passwords are never recorded.** A step that types into a password box needs the name of a secret saved on the PC (Jizza Agent › Settings › Secrets) before you can save.
- Clicking into a field just before typing in it is folded into the typing step.
- Clicks on areas with no name, like a blank part of a window, are left out; the notification tells you how many.

Saved recordings run on the same PC. Each step finds its control by name each time, so a run still works when windows move or resize.

## Change the hotkey

Open Jizza Agent › Settings › Apps and change **Record a task**. Use Ctrl, Alt or Win with one key, such as Ctrl+Shift+F9. Leave it empty to turn the hotkey off.

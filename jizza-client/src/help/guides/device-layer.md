---
title: Device layer: a whole form as one step
summary: Fill a whole area of a page, such as a Create task form, in one step that waits for dropdowns that load after another, and takes a different path for each button.
section: Web automation
order: 17
updated: 2026-11-17
---

## What it is

Many pages are one form you fill in and send: a **Create task** page with a Stage, a Project, a Milestone that only loads once a project is chosen, a few boxes and checkboxes, and **Submit** and **Cancel** at the bottom. A **Device layer** step treats that whole area as one step instead of one step per field.

- The fields are filled in a set order: the **fixed values** first, then the **values for each run**, group by group.
- A dropdown that **follows** another one (Milestone follows Project) is filled only after the one it follows is chosen and its choices have loaded.
- Each button of the area is **its own path** on the canvas. The run goes on along the button the step clicked.

## Record it

While recording in the browser, press **Map as a layer** in the side panel and click the button that sends the area (such as **Submit**). Jizza finds the box around that button and records one Device layer step with:

- every field in it, in page order, with the value it has now;
- the choices of each dropdown, also a site's own dropdown that shows its list only while open (Jizza opens it, reads the list and closes it again);
- which dropdown follows which (a dropdown that is greyed out, or opens empty, until another is chosen);
- its buttons, with Submit-like and Cancel-like buttons told apart.

Then click the button on the page yourself to carry on recording. That click is part of the layer step, so it is not recorded twice. Fill the form before you map it if you want its current values kept, or switch the recorder to **Manual** first so your typing is not also recorded as separate steps.

### Edit form and New form layer

Each Device layer step in the side panel's step list has an **Edit form** button (▤ while recording, **Edit form** in the step's menu while editing a recording). It opens the form as one card:

- **Add field** or **Add button**, then click it on the page.
- Remove a field or button with ×, and move fields with ↑ and ↓.
- Change a field's kind (Text box, Dropdown, Type and choose, Date, Checkbox, and so on), its name and its value.
- Tick **Fixed value** for a value that is the same every run, or leave it as a value for each run and tick **Mandatory** to be asked when it comes out empty. A dropdown can **Follow** a field above it.
- Choose the button the step clicks under **Then click**, or **Ask each run**.

**Save form** changes the step in Jizza. Values you made into workflow inputs stay with their fields.

When Map as a layer can't find the form by itself, use **New form layer**: the same card starts empty, you pick each field and button on the page, and **Add form step** records the step.

## Build it on the canvas

Add **Device layer** from the Web Automation steps, then open it.

- **Area of the page**: pick the box that holds the form (optional), and press **Scan this area**. The fields, groups and buttons are filled in for you. Scanning again keeps the buttons you already had, so their paths stay connected.
- **Fixed values**: filled first, the same on every run. Use **Make it a fixed value** on a field to move it here.
- **Values for each run**: filled after the fixed values, group by group. Map each one from earlier steps with a data chip, or type it. Tick **Mandatory** to have the run ask you when it comes out empty. Set **Follows** on a dropdown that loads its choices after another field, and press **Load items from the page** to see what it offers now.
- **Buttons**: one row per button: its name, its kind and where it is on the page. **Add button** adds one, and with it a new path out of the step.
- **Button to click**: the button the step clicks at the end. Map it from earlier steps to choose at run time, or leave it empty to be asked each run.

## Removing a button

Removing a button that already has steps after it never deletes them. Jizza asks what to do with them:

- **Move its steps to** another button, then **Move**; or
- **Disconnect them**, which leaves them on the canvas, unconnected, for you to reconnect or delete; or
- **Keep the button**.

## When it runs

Before you approve the run, the card shows the values for each run under their group headings, the fixed values in a collapsed **Fixed values** list, and a **Then click** choice of the buttons.

Next to a value that the page can list, such as a dropdown or a station box that suggests as you type, the card shows **Refresh**. For a dropdown it lists what the page offers right now. A dropdown the card has no choices for is read from the page by itself as the card opens, so you pick from it instead of typing; one that follows another field waits for your Refresh. For a box that suggests as you type, type a few letters on the card first: Refresh types them into that box on the page, reads the suggestions, and shows them under the field to choose from. The browser must still be on that page.

If a value is not one the page offers (a milestone that no longer exists), or a mandatory value is empty, the step stops at that field and asks you, listing the choices the page shows right now. Pick one and the step carries on from that field: the ones before it are not filled again.

When the clicked button sends the form and the site shows what is wrong (such as "Title is required"), the run stops and the card shows each message next to its value, just like **Fill form**.

A button with no steps after it ends the run there.

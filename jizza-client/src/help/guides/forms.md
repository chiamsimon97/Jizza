---
title: Forms
summary: Build a form from fields or from a workflow's steps, publish it at a link, and start workflows with what people send.
section: Automation
order: 20
updated: 2026-11-08
---

## What it is

A form is a page anyone can fill in at its link, without a Jizza account. Every response is kept under the form's **Submissions**, and it can start one or more of your workflows with the answers as their input.

Open [Forms](/forms) and choose **New form**:

- **Blank form**: drag fields in from the left panel.
- **From a workflow**: pick one of your workflows. Its steps are listed on the left, and dropping a step makes the fields it needs for you.

## Building a form

The left panel has three tabs:

- **Basic**: Name, Email, Address, Phone, Single Line, Multi Line, Number, Date, Time, Drop Down, Radio, Multi Select and Checkbox.
- **Advanced**: Decision box, Rich Text, Url, Image, Percent, Currency, Decimal, Date-Time, SubForm, Lookup, Signature, File upload, Audio, Video, Auto Number, Formula and QR/Barcode, plus Section and Add Notes to lay the form out.
- **Workflow**: each of your workflows with its steps.

Drag a field onto the form, or click it to add it at the end. Drag a field by its handle to move it. Select a field to change its label, link name, choices and whether it is required.

The **link name** is the name the answer is saved under. A workflow reads it as `input.<link name>`, for example `input.email`.

A **Formula** field works out a value when the form is sent, from the other answers: `input.price * input.quantity`. An **Auto Number** numbers each response, from the number you choose. Files, images, audio, video and signatures can be up to 3 MB each.

## Fields made from a workflow step

On the **Workflow** tab, drag a step onto the form. Jizza makes one field for each setting of that step that takes data, such as a notification's message, a webhook's URL, a service action's arguments or the start step's inputs. They appear together in a step layout, marked as suggestions.

- **Accept all** (at the top of the layout) keeps every suggested field.
- **Accept** or **Remove** on a field keeps or drops just that one.

Suggested fields are not on the published form until you accept them. When the form starts that workflow, each accepted field fills its step setting with what was typed. The workflow itself is not changed: the answer is used for that run only, and what people type is always treated as text, never as a formula.

Adding a step from a workflow also makes the form start that workflow, unless another form already starts it.

## Publishing

**Publish** gives the form a link; **Copy link** copies it. **Unpublish** takes the form down; publishing again brings back the same link. Use **Preview** to see the form as people will.

## Starting workflows

A form starts a workflow only while the workflow is **on**. Under **When someone sends this form** (in the right panel when no field is selected), tick the workflows to start and switch each one on.

You can also do this from [Workflows](/workflows): each workflow has an **On/Off** switch and a **Starts when** picker for its form.

- Turning a workflow on holds for its current steps. Saving changes in the builder keeps it on.
- A workflow that is off can still be run by hand to test it.
- Each response shows the runs it started, with a link to the run log, or why a workflow didn't start.

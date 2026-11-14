---
title: Browser recording
summary: Record a task once in Chrome, review the steps, and run it as a module whenever you need it.
section: Web automation
order: 10
updated: 2026-11-10
---

## What it is

Browser recording turns a task you do once in Chrome into a module that Jizza repeats the same way every time. While you do the task, the Jizza Browser Agent extension writes down each click, typed value and page change. Then you review the steps and save them as an ordinary workflow.

With a saved recording you can:

- **Run it on demand**, from the [Recordings](/recordings) page or by asking Jizza in chat ("download last month's invoices").
- **Use it inside other workflows**, through "Run a module" or "For each", for example once per row of a spreadsheet.
- **Change values per run**: any value you typed (an invoice number, a date, a search term) can become an input.
- **Read data off pages**: pull text, check that something is on the page, or extract a whole table, and pass it to later steps.
- **Schedule it**, once you allow that in the workflow's reuse settings.
- **Edit it later** in the workflow builder, step by step, like any other workflow.

Nothing is decided on the fly. You can see and change every step, and a run stops and asks you when a page no longer matches what was recorded.

### How it fits together

1. **Record in Chrome**: do the task once while the extension writes down the steps.
2. **Review the steps**: rename, reorder, delete, and turn typed values into inputs.
3. **Save as module**: it becomes a workflow with the Recorded origin. It ends with a **Close browser** step; remove that step if you want the page left open after a run.
4. **Run the module** as often as you need.

A run pauses when a page needs a person and goes on after **Done, continue**. When a step can't find its element, the run waits for you to apply a fix, and applying it saves a new version.

## Before you start

The Record screen shows a **Before you record** checklist until these are done, and ticks each one off as it finds it done.


You set up three things once: the extension, a paired browser, and the sites it may work on.

1. **Install the extension.** Load Jizza Browser Agent in Chrome or another Chromium browser (such as Ulaa), version 120 or newer.
2. **Pair the browser.** In Jizza, open [Settings › Devices](/settings/devices), press **Pair a device**, open the **Browser** tab, and follow the steps. Paste the key into the extension's popup. The browser then shows as **Online**, and **Test** should answer within a few seconds.
3. **Allow the sites.** In the extension's **Options**, add each site the recorder may work on, as `example.com`, or `*.example.com` to include its subdomains. Chrome asks you to grant access to each one. No site is allowed by default.
4. **Save your logins (optional).** Also in Options, under **Saved logins**, add a name, the site, the username and the password for each site that needs a sign-in. They stay encrypted in the browser. Jizza only ever sees the login's name.

Keep the browser open while you record or run, because the module runs in that browser, not on the server.

## Step by step: record a task and test it

This is the full procedure, checked end to end against a running Jizza server and the extension on 2 Nov 2026. It records a short task, saves it as a module and replays it.

### A. Check the browser is ready

1. Open the browser that has the Jizza Browser Agent loaded, and keep it open the whole time.
2. In Jizza, open [Settings › Devices](/settings/devices). Your browser should show **Online**. Press **Test**: it should answer within a few seconds. If it says it is not connected, reload the extension on the browser's extensions page (`chrome://extensions`) and press **Test** again.
3. Click the Jizza icon in the toolbar, open **Options**, and check that the site you want to record is in **Allowed sites** (for example `portal.example.com`). If you add it now, accept Chrome's prompt to grant access.

### B. Record

4. In Jizza, open [Recordings](/recordings) and press **Record**.
5. Choose your browser under **Browser agent**, type the full **Start page** address starting with `https://` (or `http://`), give the recording a **Name**, and press **Record**.
6. The start page opens in its own window beside Jizza: Jizza moves to the left half of the screen and the site takes the right half, so you can see both. (If Jizza is open in a different browser, or you turned this off in the extension's Options, it opens as a new tab instead.) Jizza shows "Recording in Chrome on …" with a red dot. Wait for the page to finish loading before you start.
7. Do the task at a normal pace. Click into a field, type, then click the next thing or press Tab: the typed value is saved as a step when you leave the field. Click buttons and links, tick boxes, choose from lists.
8. Watch the **Captured steps** list in Jizza (or the extension's side panel, from the browser's side panel button). Each action appears within a second or two. If nothing appears after your first click, see the last part of this section.
9. When the task is done, press **Stop** in Jizza.

Tip: press **Float controls over the site** while it records. A small Jizza window with the captured steps and **Stop recording** stays on top of every other window, so you can keep the site full screen. Close it any time; it also closes when the recording stops. It needs Chrome, Edge or another Chromium browser, version 116 or newer.

### C. Review and save

10. The review opens. Check every step reads the way you expect. Delete stray clicks, rename steps, and use **Make this an input** on any typed value you want to change on each run.
11. Check the **Module name**, add a short **Description**, and press **Save as module**.

### D. Test the module

12. The module now shows under saved recordings on the [Recordings](/recordings) page. Keep the browser open and press **Run now**.
13. The browser opens the start page again and Jizza replays the steps in order. Before each step the extension brings the run's tab to the front, because some sites (for example a booking site's "Select language and format" dialog) only draw part of the page while their tab is visible. You can watch the run in Jizza; the browser tab comes back to the front at the next step.
14. Each step that changes the page (typing, clicking, ticking) goes through your guardrails, so the first run usually stops before such a step and asks you. Open [Notifications](/notifications) (or [Run history](/runs) and the run) and approve the step. The run carries on to the next step. To stop being asked for a step, open the workflow's **Guardrails** on the [Workflows](/workflows) page and set that step to **Auto** for the agent that leads it (see "Who runs it and which steps ask" below).
15. Open [Run history](/runs). The run should end as **Done**, with every step ticked. A failed step shows what went wrong in words, and a screenshot when the browser took one.

### If recording does not start or captures nothing

- **"Nothing was captured. Try again, and check Chrome is open on the page" straight after you press Record.** The browser refused the recording. Check the browser is **Online** and **Test** answers. Before the extension fix of 2 Nov 2026 every recording failed this way, so update the extension and reload it.
- **A tab opens but no steps appear.** The site is not in **Allowed sites**, or Chrome was not given access to it. The side panel says "Not recording on this site".
- **Steps appear only for clicks.** Typed text is saved when you leave the field, so click elsewhere or press Tab after typing.
- **The run waits and nothing happens.** It is waiting for you to approve a step. See step 14.

## Record a task

A recording runs from **Record** to **Stop**, and gives you a list of steps to review.

1. In Jizza, open [Recordings](/recordings) and press **Record**.
2. Choose the **browser agent**, a **start page** (for example `https://portal.example.com/invoices`) and a **name** for the recording.
3. Chrome opens the start page in its own tab group, in a window placed beside Jizza, and the extension's side panel lists each step as you go. Later recordings and picks reuse that window while it stays open.
4. Do the task the way you normally would: click, type, pick from lists, tick boxes, switch tabs.
5. To capture something the page shows rather than something you do, use the side panel:
   - **Add check**: pick an element to check that it is there, or to read its text.
   - **Add table**: pick a table to extract its rows.
   - **Mark hover**: pick an element the run should hover over, such as a menu.
   - **Add wait**: add a step that waits a few seconds, for a page that needs time before the next step.
6. If you make a stray click, delete it in the side panel (×). You can also drag steps into a different order, rename a step (✎), and turn a typed value into an input (⇄) there, without waiting for the review. A step that became an input shows "input: name". Choosing a saved login for a password still happens in Jizza's review.
7. To do something on the page without recording it, press **Pause** at the top of the side panel, then **Resume**. Come back to the page you paused on first: a run does not follow where you went while paused.
8. Press **Stop**, at the top of the side panel or in Jizza. Closing the last tab of the recording also stops it. **Discard recording** at the bottom of the side panel stops it and keeps nothing.

You can also start a recording in two other ways:

- **From the workflow builder**: choose **Record steps here** in the add-step list. The steps go straight into the workflow you are editing.
- **From chat**: tell Jizza you want to show it a task. It starts the recording and shows a card with **Stop** and **Open review**.

## What gets recorded

Each thing you do becomes one named step. Steps inside frames (embedded pages) are recorded with their frame, so they replay in the same place.

| What you do | Step you get |
| --- | --- |
| Type an address or open a bookmark | Open URL |
| Click a button or link (also double-click, Ctrl/Shift-click) | Click, on the control itself |
| Click one card of a list you mapped (Scan) or marked (Mark a list): a movie, a date in a date strip, a showtime | Choose from a list, with the item you clicked |
| Click one of the buttons you grouped (Group buttons) | Click one of these buttons, with the button you clicked |
| Type in a field | Set text, one step per field when you leave it |
| Type a password, one-time code or card number | Set text marked as a password; the value is never read |
| Press a shortcut, Escape, or an arrow key outside a field | Send hotkeys, for example `Control+K` |
| Tick or untick a box | Set checkbox |
| Choose from a drop-down or a custom list | Select item |
| Choose a radio button | Select radio |
| Scroll more than a screen and pause | Scroll |
| Back, Forward, Reload | Go back, Go forward, Refresh |
| A popup opens, or you move to another tab | Switch to |
| Close the last tab | Close browser |
| Add check, Add table, Mark hover (side panel) | Check if element exists or Get text, Extract table data, Hover |

Each step stores several ways to find its element (its label, text, role and position), plus a small picture of it, so you can tell steps apart in review.

### Lists made of cards

Many sites show lists that are not real lists: a row of movie cards, a strip of dates, the showtimes of each theatre. The recorder notices when the thing you click has look-alike neighbours, and records **Choose from a list** instead of a plain click. The step finds the list by its shape and chooses the item by its name, so it still works when the cards move around, and it can choose a different item on the next run.

When the list sits inside a card of another list (the showtimes of one theatre), the step also remembers that card, so it chooses "01:15 PM" at "The Vijay Park Multiplex" and not at the first theatre.

### Record manually: add each field yourself

For long forms such as a train booking (stations, date, class, passengers, payment), switch the side panel to **Manual** (browser agent 0.7.0 or later). Your clicks and typing are then not recorded; you add each step yourself, so nothing extra slips in.

1. Open the extension's side panel (right-click the Jizza icon, **Open side panel**) and press **Manual**.
2. Fill a field on the page as you normally would, for example type the passenger's name or choose the class.
3. Press **Add field** and click that field. The side panel shows what kind of field it is, its name and the value it holds now:

| Kind | For | The step |
| --- | --- | --- |
| Text box | names, numbers, addresses | types the value |
| Dropdown | a class or quota list | chooses the option |
| Type and choose | station boxes that show suggestions | types and picks the matching suggestion |
| Date box | a date input | sets the date |
| Date from a calendar | a box that opens a calendar | opens it and clicks the day |
| Checkbox | insurance, terms | ticks or unticks |
| Option buttons | General / Tatkal, payment method | chooses one |
| Button or link | Search, Continue, Pay | clicks it |

4. Change the kind, name or value if the guess is wrong. Tick **Different each run** to make the value an input of the module (a different passenger or date each time), then press **Add step**.
5. For a button that opens the next page (Search trains, Continue), press **Add field** and click the button first, then click it yourself so the site moves on. The click you make while picking doesn't reach the site.
6. For a choice in a list of cards (a train in the results), use **Mark a list**: in Manual, clicks in a marked list are still recorded.

You can switch between **Auto** and **Manual** at any time while recording, and **Add field** works in Auto too. Password and card fields are never read: choose a saved login for them when you review the recording.

### Group fields into one form step

A form such as passenger details (first name, last name, age, berth) can be one step instead of one step per field (browser agent 0.8.0 or later):

1. Press **Start form step** in the side panel, give it a name such as "Passenger details", and press **Start**.
2. Fill the fields on the page as you normally would. Each value you type or choose goes into the form step, in Auto and in Manual. Typing in a field again changes its value, not adds another.
3. Or press **Add field to form** and click a field, to check its kind and value or tick **Different each run** for it. A button added this way (such as Continue) becomes the form's send button, pressed after the fields are filled.
4. Press **Finish form step**. The side panel shows one **Fill form** step with every field.

While a form step is open, clicks are not recorded, so finish it before you click a button that moves the page on. Remove a field with × in the form card. Stopping the recording finishes an open form step. Password fields are kept as their own steps.

In review, **Make this an input** lists each field of the form step by its name. In the workflow builder, the step shows every field under **Fill form**.

### Scan and map the page first

From browser agent 0.9.0, a recording starts with **Scan and map** in the extension's side panel. Nothing you do on the page is recorded yet, so you can look around, open the right page and map its fields first.

1. Press **Full page scan** to find every text box, dropdown, date, checkbox and option group on the page, and every list you choose from: cards, `ul`/`li` lists and rows of look-alike boxes such as a date strip. Menus, the header and the footer are left out.
2. Or press **Partial page scan** to scan only parts of the page. Tick parts in the page's tree (hover a row to see it outlined on the page), type a **CSS selector** and press **Add** (for example `div[id^="2026"]` for date cells whose ids are dates, or a class every item has), or press **Pick on page** and click one. The **Selected** list shows what will be scanned. Press **Scan**.
3. Or press **Add from page** and click one field, or one item of a list, to add just that.
4. Each field and list gets a card. Give it a **Title**: type it, or press **Pick title on page** and click the text that names it (usually above or to the left of it). **Static** keeps the title as it is. **Dynamic** reads it from the page again each time a page loads; when the page doesn't show it, the last title read is kept and the card warns you.
5. A list becomes one field: a dropdown whose choices are its items' names. A list with no title on the page (such as the theatres) asks you to name it. If each item is listed by all its text, press **Pick the name inside one item** and click the name, such as a theatre's name in its card. If the list found is not quite right, change its **Item pattern (CSS)** and press **Apply**: the card shows how many items the pattern finds.
6. Lists that follow other lists: set **Depends on**. A list found inside the cards of another list (the showtimes in each theatre) is set to follow it already. For a list the page loads again when another changes (the theatres after the date), choose that list; **Suggest the order from the page** sets each list to follow the one above it. Use **Preview** to choose a date: it is clicked on the page (not recorded) and every list below it is read again.
7. Untick **Different each run** for a field that should keep its value. The others become module inputs with the names shown.
8. Press **Done mapping, start recording**, then do the task. A step on a mapped field or list is named by your title, and its value becomes the input you named. **Skip mapping** records as before. While recording, **Scan this page** pauses recording to map the next page.

In review, a list that follows another list shows "after Date". In the workflow builder, each list is a single dropdown of its names, with **Map from earlier steps or type…** to map it instead; the showtimes follow the theatre chosen, and a list that follows another one says so.

### Mark a list yourself

Sometimes the recorder guesses wrong. A plain **Click** may appear where you expected **Choose from a list**, or the whole theatre may be chosen instead of its showtime. Tell it what the list is (browser agent 0.5.0 or later):

1. Before you click the item, press **Mark a list** in the extension's side panel.
2. Click one item of the list on the page, such as a showtime. The click doesn't reach the site. Use the Up and Down arrow keys to widen the highlight to the whole item or narrow it.
3. Click the card it sits in, such as the theatre: point at the theatre's name and press Up until the whole theatre is outlined, then press Enter. Press Esc if the list is not inside a card.
4. The page briefly outlines every item found, and the side panel says how many, for example "is one of 4 items inside Rakki RGB Laser 4K (one of 12 cards)".
5. **Name this list** opens (browser agent 0.8.0 or later). Type what each card is (such as "Theatre") and what each item is (such as "Showtime"); the page's own heading is filled in when it has one. Below each name the panel lists the names it found, such as every theatre and the showtimes of the one you marked. If the names read wrong (a whole card's text instead of its name), press **Names wrong? Point at the name in one card** and click the name inside one card: every card's name is then read from the same place. Press **Save names**, or **Skip**.
6. Now click the item as usual. It is recorded as **Choose from a list**, and so is every later click in that list.

The names you give become the field names in review and in the workflow builder ("Theatre" and "Showtime" instead of "Chosen in" and "Item chosen"), and the input names (`theatre`, `showtime`) that **Make reusable** offers. In the builder, the step also lists the theatres and their showtimes found while recording as drop-downs, so you can pick another one without opening the page.

The step saves a pattern for the items and for the cards. Runs read the list with these patterns first, so they keep working when the recorded theatre or showtime is gone the next day. In review, such steps show **marked by you**. In the workflow builder, the patterns appear as **Item pattern (CSS)** and **Card pattern (CSS)** under the step.

### Lists are only what you define

From browser agent 0.13.0, a click becomes **Choose from a list** only in a list you mapped with Scan or marked with **Mark a list**. Two buttons side by side, or cards that happen to look alike, are recorded as plain clicks. To get the old behaviour back, where any click on one of several look-alike items was guessed to be a list, tick **Guess lists** under Auto and Manual in the side panel.

### Group buttons that are not a list

Some pages ask you to choose between a few different buttons that aren't a list, such as हिंदी and English on IRCTC, or Accept and Decline. Group them so either one is one step:

1. Press **Group buttons** in the side panel.
2. Click each button of the group on the page. The clicks don't reach the site. Press Esc when you have clicked them all.
3. **Name this group** opens and lists the buttons. Type what the choice is, such as "Language", and press **Save group**.
4. Click the button you want, as usual. It is recorded as **Click one of these buttons**, named "Choose Language "English"".

In review the step shows "one of 2 buttons: Language", and its choice becomes the module input `language`, with the button you clicked as its default. In the workflow builder, **Language** is a drop-down of the buttons: pick one, map it from earlier steps, or leave it on **Choose…** so each run clicks whichever of the buttons the page shows. When a run asks for a button that isn't one of them, Jizza asks you which to click.

#### Run different steps for each button

After the recording is saved, you can make the workflow go a different way depending on which button was clicked:

1. Open the workflow in the builder and select the **Click one of these buttons** step (or right-click it).
2. Press **Branch by button clicked**. A **Paths** step, "Which Language was clicked", goes right after it, with one path per button and an **otherwise** path.
3. Every path starts out connected to the step that came next, so nothing changes yet. To give a button its own steps, hover its path's connection and press **+** to add a step, or **Record** to record them in the browser. The path joins the rest of the workflow again after those steps.
4. A button that needs nothing different keeps its straight connection, or remove its path in the Paths step: it then carries on with **otherwise**. **Add a path for** brings a button's path back.

The Paths step reads the button from the step's output, `nodes.<step>.clicked`, so it works whether the button was chosen at run time, mapped, or picked by the page. A workflow with paths can no longer be opened with **Edit recording**; change it in the builder.

### Choose the theatre and showtime from the page

In the workflow builder, a **Choose from a list** step has **Load cards from the page** and **Load items from the page**. With the site open in the chosen browser, these read what the page shows now and offer a drop-down: pick a theatre, then load and pick one of its showtimes. When a run reaches the step and the chosen theatre or showtime isn't there, Jizza asks you, listing what the page shows then: theatres first, then the showtimes of the theatre you pick.

When Jizza asks you to approve such a step, the approval card shows the theatre as a drop-down of every theatre on the page, and the showtimes of the theatre selected. Pick another theatre and its showtimes appear straight away (this needs browser extension 0.6.0 or later; with an older one, type the showtime).

## Review and save

After you press **Stop**, the recording waits for you in review. Nothing runs until you save it.

- **See on page**: click a step's picture to see the whole page with the element outlined, so you can tell which of two similar buttons it used. (Recordings made with extension versions before 5 Nov 2026 have the small picture only.)
- **Rename** a step so it reads like an instruction, such as "Open the invoice search".
- **Move up / Move down** to change the order, and **Delete** steps you don't need.
- **Make this an input** on a typed value. Give it a name and a type. The value you typed becomes the default, and each run can pass a different one.
- **Make reusable** turns every list choice (the movie, the date, the format, the showtime and its theatre) into an input in one go, with names such as `movie`, `date` and `time`. The recorded choices stay as the defaults, so the workflow still does what you recorded until a run, a form or a schedule passes other values. You can also make one choice an input with **Make this an input** (**Item chosen**, or **Chosen in** for the theatre).
- **Saved login** on each password step: choose the saved login to use from the list of logins saved in that browser. A recording with a password step can't be saved without one. If the list is empty, add the login in the extension's Options first.
- **Username from** on a username box (any box you typed into that is not a password): choose a saved login to type its username instead of what you typed while recording. Choose **What was typed** to go back.

Then give the module a name and a short description, and press **Save as module**. The description helps Jizza find the module when you ask for it in chat.

Drafts you leave in review for 7 days are removed.

## Use a saved recording

A saved recording is a workflow with the **Recorded** origin, so it works anywhere a workflow does.

| Where | How | Good for |
| --- | --- | --- |
| Recordings page | **Run now** on the saved recording; the run opens so you can follow its steps | A one-off run while you watch |
| Chat | Ask for the task in your own words; Jizza offers the matching recording and runs it after you say yes | Doing the task without opening anything |
| Another workflow | **Run a module** step, passing its inputs | One step of a bigger process |
| Many items | **For each** over a list, running the module once per item | The same task for every invoice, order or row |
| Schedule | First turn on scheduled runs in the workflow's reuse settings | Daily or weekly jobs |
| Workflow builder | **Open in builder** | Adding steps, conditions or data mapping |

In the builder, the **Web Automation** group has the same steps the recorder makes, so you can also add steps by hand. Each step that needs an element has **Pick on page** (click the element in Chrome) and **Test step** (run that one step). Pick on page opens the page beside Jizza too, and afterwards the step shows the element's picture: click it to see the element outlined on the page.

To keep tabs in the same window instead of a window beside Jizza, open the extension's **Options** and turn off **Open recording and picking next to Jizza**.

What a step reads, such as a table's rows or an element's text, is available to later steps through data mapping, like any other step output.

By default, a saved recording asks before Jizza runs it on its own and does not run on a schedule. When you ask Jizza in chat to run it, your request is the yes, so it runs straight away. You can change that in the workflow's reuse settings.

### Conditions while recording

While you record for the first time, **Only if shown…** in the side panel starts a condition: click what must be on the page (a pop-up's title), and the steps you record next go inside it. **Otherwise** starts the steps for when it is not there, and **End condition** goes back to steps that always run.

### Edit a recording

When a site changes, or a page only sometimes appears (BookMyShow shows "Select language and format" only for films with more than one format), edit the saved recording instead of recording it again. Edit needs the Jizza Browser Agent 0.10.0 or later.

1. On the Recordings page, press **Edit** next to the saved recording.
2. Choose the browser and check the **Test values**. Each input starts with its default. The steps run with these while you edit; saving keeps the inputs' own defaults.
3. The start page opens and the extension's side panel shows the steps with a marker after the one that ran last. Test runs while editing skip guardrails (the allowed sites still apply), and **Close browser** is never run.

In the side panel:

| Button | What it does |
| --- | --- |
| **▶ Next** | Runs the next step and moves the marker past it |
| **⏩ Run to end** | Runs steps until one fails or the end; **⏸ Pause** stops after the current step |
| **⏭ Skip** | Moves the marker past a step without running it |
| **● New action** | Records what you do on the page as new steps where the marker is; **■ Stop adding** ends it |
| **Run these only if shown…** | Tick the steps that only sometimes apply, press it, then click the element that tells (such as the pop-up's title). They run only when it is on the page |
| **Add wait** | Waits a number of seconds at the marker |
| **⋯** on a step | Run from here, Shown/Missing for a condition, Rename, Turn off, Make optional, Pick element again, change the test value, move or delete |

**Only if shown…** with no steps ticked puts the next step to run in the condition, so the steps after it run either way. That is how to skip one pop-up step: move the marker to that step, press **Only if shown…**, and click the pop-up's title (click the title text itself, not the whole pop-up). In a condition's **⋯** menu:

- **Add Otherwise** adds steps that run only when the element is not there. Press **● New action** with the marker under Otherwise to record them.
- **Take in next step** and **Leave out last step** move the condition's end, to put more steps inside it or take one out.

A condition with nothing inside does nothing, so saving leaves it out. The side panel marks one in red with **Take in next step** beside it, and the review in Jizza says so too; drag the step it is for between the condition and its end there.

A condition waits up to 3 seconds for its element. **Missing** runs its steps only when the element is not there. An **optional** step is skipped when its element is not on the page, instead of failing the run. A step that is **turned off** stays in the recording but does not run.

Press **■ Finish editing** at the top of the side panel (or **Finish editing** in Jizza), then review and save. **✕ Cancel editing** stops and keeps nothing; the saved recording stays as it was.

When you save:

- **Save changes** saves a new version of the same recording. Workflows that run it as a module run the new version from now on. If its inputs changed, Jizza lists those workflows so you can update their steps: new inputs are added empty and inputs it no longer has are removed.
- **Save as new recording** keeps the old one as it is. A recording that is past draft can only be saved this way, because its changes go through review.

A recording that was changed in the builder with branches that the recorder does not make (Paths, Parallel) can only be changed in the builder.

### Delete a recording

On the Recordings page, **Delete** next to a saved recording removes it after you confirm. The same button is on each workflow on the Workflows page. Deleting stops any run of it still in progress, and its schedule or form no longer starts it. Past runs stay in the run history, shown as **Deleted workflow**. A workflow that runs it as a module fails at that step, so update those first. A draft that is still being recorded or reviewed has **Discard** instead.

## Who runs it and which steps ask

Each workflow has a **Guardrails** panel on the [Workflows](/workflows) page.

- **Maintained by**: pick one or more agents, the same way you pick agents for a connector. The first one you pick **leads**: its guardrails decide every run of this workflow. With none picked, Jizza (or the agent that made the workflow) leads.
- **Each step, per agent**: every step that acts on your apps or your browser is listed, with what each maintaining agent's guardrails will do: **Auto**, **Ask me**, **Let Jizza decide** or **Never**.
- **When Jizza leads**: Jizza never approves its own actions, so for Jizza **Let Jizza decide** is offered only on steps that choose from a list the page shows (such as a movie or a date). There it clicks the item the run names when the page lists it, and asks you when it doesn't. To let another agent decide on other steps, pick it under **Maintained by**.
- **Skip the approval for a step**: set that step to **Auto** for the lead agent. The run then goes on without asking you at that step. It covers this version of the workflow only; after you edit the steps, they are asked about again.

Changing who maintains a workflow, or a step's mode, asks you to confirm it's you.

## When the browser closes

The browser closes only because of a **Close browser** step. A new recording ends with one. Remove it in the workflow builder if you want the page left open after a run, for example to pay for tickets yourself. A run that fails or is cancelled still closes the tabs it opened, because its steps never reached the end.

Recordings saved before 2 Nov 2026 have no Close browser step, so their pages now stay open after a run. Add the step from **Web Automation** in the builder if you want them closed.

## Sign-ins and when Jizza needs you

Passwords never go into a recording. A password step types from a saved login, and only into a password field on that login's own site, over a secure (https) connection.

Some pages need a person: a CAPTCHA, a one-time code, a "confirm it's you" check, or a sign-in form when no saved login fits. When a step can't go on because of one of these, the run pauses and:

1. You get a notification, "Jizza needs you". Clicking it brings up the tab.
2. The side panel shows a card that says what the page needs.
3. You finish that part yourself in the tab, then press **Done, continue**.
4. The step runs again from the start and the run carries on.

If nobody responds before the step's time limit, the run stops as timed out. Cancelling the run also ends the wait.

## Safety and privacy

The recorder only watches what you allow, and you can stop it at any time.

- **Only allowed sites.** Steps on a site that isn't in your allowed list are not recorded, and the side panel says "Not recording on this site". Browser settings pages, extension pages and the Chrome Web Store are never recorded or automated.
- **Only its own tabs.** The recorder watches only the tab group it opened, not your other tabs.
- **Only real actions.** Only the clicks and keys you actually make are recorded, never ones a page fakes.
- **No secrets stored.** Password, one-time-code and card fields are never read, also on pages that only name them that way (a box called "CVV", "OTP" or "Card number"), and a password stays unread after the page's "show password" turns it into plain text. They are blurred in every screenshot and step picture, and if the server ever receives one of their values, it removes it.
- **Your approval.** Steps that change a site (submitting, typing) go through your [guardrails](/guardrails) like any other action. In chat, clicking or typing in your browser always asks first.
- **Stop everything.** **Pause automation** in the extension popup refuses all commands until you resume. **Stop all** (Alt+Shift+S) ends whatever is running now.

## Advanced web steps and options

Besides recording, you can add web steps by hand from **Web Automation** in the step list. A few
do more than a recording can:

| Step or option | What it does |
| --- | --- |
| **Handle pop-up dialogs** | Answers the page's OK/Cancel and "type a value" pop-ups for you until the page changes. Put it before the step that makes the pop-up appear, otherwise that step waits for a person. |
| **Drag and drop** | Drags one element onto another (**Drop onto**). Turn on **Trusted input** for sites that only react to a real mouse. |
| **Wait** for text or an address | Waits until some text shows (or goes away), or until the page address contains a value, for example after a payment page redirects. |
| **Advanced options › Highlight the element** | Outlines the element for a moment before acting, so you can watch what the run does. |
| **Advanced options › Tries before giving up** | How many times the step runs before it counts as failed (default 3), and how long to wait between tries. |
| **Advanced options › When it still fails** | **Ask me what to do** (the default) or **Stop the run**. |
| **Edit selectors › Inside frames / shadow roots** | For elements inside an iframe or a web component: the iframes or component hosts that lead to it, as CSS selectors. Picking on the page fills these in for you. |

These need the browser extension from 5 Nov 2026 or later.

## Limits and troubleshooting

### Limits

| Limit | Value |
| --- | --- |
| Longest recording | 30 minutes |
| Steps per recording | 500 |
| Typed text per step | 4 KB (longer text is cut and flagged) |
| Drafts left in review | removed after 7 days |

### When a page changes

Sites change, and a step may no longer find its element. When that happens, the run stops at that step and Chrome looks for similar elements (same kind, similar text and position). You get a notification such as "Step 4 of Supplier invoices could not find Submit order. Review a fix". **Review fix** opens the workflow on that step. Choose the right element and press **Apply fix**, or press **Dismiss**. Jizza never picks one for you. If none of the suggestions is right, press **Pick on page** under "None of these?" and click the right element in the browser (the run's page is usually still open). Check the picture of what you picked, then press **Use this element**. Applying saves a new version, and the old ways of finding the element stay as fallbacks.

### Common problems

| What you see | What to do |
| --- | --- |
| "Not recording on this site" in the side panel | Add the site in the extension's Options, then grant access when Chrome asks |
| Test says "It is not connected right now" | Keep the browser open, reload the extension on its extensions page, and check the server address it was paired with |
| A step is missing after a scroll | This is on purpose: scrolls shorter than a screen, or followed by another step within 2 seconds, are left out |
| "Choose a saved login for each password step" | Add the login in Options › Saved logins, then choose it on that step in review |
| A run stops at a sign-in, CAPTCHA or code | Finish it in the tab and press **Done, continue** in the side panel |
| A run waits on a step until you open its tab | Update the extension: since 2 Nov 2026 it brings the run's tab to the front before each step. Also keep the browser window open, not minimised |
| The run history shows a failed web step | Open it. It says what went wrong and, when the browser took one, shows a screenshot of the page |

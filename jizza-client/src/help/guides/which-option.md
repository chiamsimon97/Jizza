---
title: Which option do I need?
summary: Find the recording option or step for what the page does, from a list of common situations.
section: Web automation
order: 11
updated: 2026-11-24
---

## How to use this page

Find the situation that matches what the page does, then use the option next to it. Most of these are recorded by themselves when you do the task while recording; the side panel tile or builder step is there for when you add a step by hand. Each option is explained in [Browser recording](/help/browser-recording).

## Filling in fields

| What the page does | Option to use | Where you find it |
| --- | --- | --- |
| A box you type words or numbers into | **Text box** | Recorded by itself; a kind in **Fill or click one thing** |
| A list that opens and you pick one choice | **Dropdown** | Recorded by itself |
| You type a few letters, then pick a suggestion (city, station, airport) | **Search and pick** | Kind **Search and pick**; step **Type and pick a suggestion** |
| A date typed as text in the site's format | **Date typed in a box** | Recorded by itself |
| The site opens its own calendar and you click a day | **Date from a calendar** | Step **Pick a date in a calendar** |
| Check-in and check-out, or from and to, in one calendar | **From and to dates** | Recorded by itself; step **Pick a date range** |
| Pick a time, or a slot in a grid of days and times | **Day and time slot** | Step **Pick a time slot** |
| A count with − and + buttons, a slider, or a price range | **Number with + and −** | Recorded by itself; step **Set a number with + and −** |
| Pick several from a list the site built | **Pick several in a list** | Recorded by itself |
| Type a word, press Enter, and it becomes a chip (skills, labels) | **Tags typed one by one** | Recorded by itself; step **Add tags** |
| An editor with bold, lists, links or @mentions | **Formatted text** | Recorded by itself; step **Write formatted text** |
| A survey grid: one answer per row | **Grid of choices** | Recorded by itself; step **Answer a grid of choices** |
| The site fills a field itself (city from a postcode, an address search) | **Let the site fill this** | **Who fills it** on a form field; step **Let the site fill this** |
| A password or card detail | **Password or card detail** | Never recorded; filled from a saved login |

## Pressing and moving things

| What the page does | Option to use | Where you find it |
| --- | --- | --- |
| A button that switches on and off (Follow, Add to wishlist) | **Make sure it is on / off** | Recorded by itself; step **Make sure it is on, off or open** |
| A section or tab that opens and closes (an accordion, "More options") | **Make sure it is open** | Recorded by itself; same step |
| Drag a card to another column, a file into a box, an item to a new place | **Drag this onto that** | Recorded by itself; side panel tile |
| Press Edit, View or Cancel in the row of a table where a column says something | **Act on a table row** | Recorded by itself; step **Act on a table row** |
| Right-click or double-click | **Right-click** or **Double-click** | Recorded by itself; side panel tiles |
| Press Enter, Escape, Tab or a shortcut | **Press a key** | Recorded while typing; side panel tile |
| Pick one card from a list of cards by its name | **Choose from a list of cards** | **Mark a list** while recording; step **Choose from a list** |
| One row for each person or item, added with the page's Add button | **Repeating rows** | **Fill a row for each passenger** |

## Things that get in the way

| What the page does | Option to use | Where you find it |
| --- | --- | --- |
| Cookie banners, chat bubbles, "Rate us" or "Still there?" pop-ups that come at any time | **Always close this if it shows up** | Side panel tile; step of the same name |
| A pop-up that comes in one place only | **Only if it pops up…** | Side panel, while recording |
| A CAPTCHA, a one-time code, or a question only you can answer | **Ask me** | Step **Ask me**; see [When a page needs you](/help/when-a-page-needs-you) |
| Paying | **Hand over to me** | Step **Hand over to me** |
| A seat map or another picture to tap | **Tap on a picture** | See [When a page needs you](/help/when-a-page-needs-you) |
| Waiting for one of several outcomes (booked, sold out, error) | **Wait until the page answers** | Step of the same name |

## Signing in

| What the page does | Option to use | Where you find it |
| --- | --- | --- |
| A user name and password form | **Saved login** | Choose a saved login when you review the recording |
| The site emails you a sign-in link | **Open the sign-in link from my email** | Recorded when you paste the link; map the link with `extractLink` from an email connector, or paste it when asked |
| "Sign in with Google", "Continue with Apple", or your company's sign-on in a window | **Sign in through the window that opens** | Recorded by itself after the click that opens the window |

## Not covered yet

These are known situations that recording does not fully handle yet. Plan for them by hand, or ask Jizza for help in chat.

- Forms inside another site's frame, such as some payment and embedded forms.
- Fields that only show for some answers (a GST number only for a business).
- Reading a value and comparing it, such as stopping when a price is over a limit.
- Paging through results and endless scrolling, beyond **Load more** on lists.
- Several accounts on one site at the same time.

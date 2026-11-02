# Help guides

The **Help** page in the app (`/help`) lists every Markdown file in `guides/`. Each file is one
guide, opened at `/help/<file name>`.

## Add a guide

1. Create `guides/<short-name>.md`. The file name becomes the address, so keep it short and
   lowercase with hyphens, for example `workflow-builder.md`.
2. Start it with this header:

   ```
   ---
   title: Workflow builder
   summary: One sentence for the Help list.
   section: Workflows
   order: 10
   updated: 2026-11-01
   ---
   ```

   Guides are grouped by `section` and sorted by `order` (lower first) inside it. Set `updated` to
   the day you last checked the guide against the app.
3. Write the guide in Markdown. Each `##` heading shows in the guide's "On this page" list.

Nothing else needs to change: the page picks the file up when the app is built.

## What the renderer supports

`##`, `###` and `####` headings, paragraphs, bullet and numbered lists (one level of nesting),
tables, code blocks, and inline `code`, **bold**, *italics* and links. A link that starts with `/`
(such as `[Recordings](/recordings)`) opens inside the app; only `http(s)` links open a new tab.
Raw HTML is shown as text, never run.

## Keep guides current

When a change alters what a guide describes (a button's name, a limit, a new step), update the
guide in the same pull request and set its `updated` date.

## Guides

| File | Section | What it covers |
| --- | --- | --- |
| `browser-recording.md` | Web automation | Recording a task in Chrome, review, saving as a module, running it, fixes, safety and limits |

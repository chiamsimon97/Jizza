# 14. Site rules, bot detection and compliance

These are decisions more than code. They shape plans 06, 07, 09 and 11.

## Gaps

| # | Gap |
|---|-----|
| G1 | No per-site **rules file**: allowed actions, min delay between actions, max runs/hour, concurrency, keep-alive allowed, known spinners, session timeout, payment hosts. |
| G2 | No stated **policy on captchas**: Jizza must never solve or bypass a captcha automatically; the person solves it (plan 06). This should be written down and enforced (no third-party solver integrations). |
| G3 | **Terms of service**: some sites (IRCTC especially for Tatkal and agents, many banks) restrict automated access. Jizza runs in the person's own browser on their own account, which is closer to "assistive" use, but the product should show a notice and let the person decide. |
| G4 | **Bot detection**: sites may flag fast, perfectly regular input. Today actions use the page's DOM events or CDP; typing speed/jitter isn't configurable. |
| G5 | **Payments**: Jizza does not fill card details or UPI PINs; hand-off only (plan 06 D). Needs to be explicit in the product and the privacy notice (`jizza-agent-browser/PRIVACY.md`). |
| G6 | **Audit** of what was done on which site on the person's behalf (for disputes: "Jizza booked two tickets"). |

## Proposed design

1. **Site rules** (G1): JSON per origin, shipped defaults for common sites (IRCTC, BookMyShow,
   Amazon.in, Flipkart, banks as a category) + user overrides on the Recordings page; read by
   the engine (lanes, polling, keep-alive) and the extension (delays, spinners, payment hosts).
2. **Captcha policy** (G2): written in the help guide and `PRIVACY.md`; code review rule: no
   automated captcha solving, no OCR of captcha images; the captcha crop goes only to the person.
3. **Notice** (G3): first time a recording runs on a site with a known restriction, a one-time
   card: "This site's terms may limit automated use. Jizza acts in your browser, on your
   account, and asks you for captchas and payments. Continue?" Stored per site.
4. **Human-like pacing** (G4): per-site typing delay range and action jitter (off by default,
   on for sites that need it); never used to defeat explicit anti-bot measures.
5. **Payments** (G5): enforce in the extension: refuse `setText` into fields detected as card
   number / CVV / UPI PIN even if a workflow asks (these are already detected as sensitive).
6. **Audit** (G6): the run timeline (plan 13) plus irreversible-step records (plan 07) give a
   clear trail; exportable per run.

## Questions for Chiam

1. OK to commit to "never solve captchas automatically" as product policy?
2. Should Tatkal timed runs (plan 09) ship at all, given IRCTC's rules? I suggest building the
   generic timed-run feature, documenting it with non-IRCTC examples, and leaving the IRCTC use
   to the person's own judgement with the notice in G3.

# 15. Mobile notes (plan only, not built)

Per Chiam's 2026-11-14 direction, mobile app development is paused; these notes only record what
the phone apps (Android, iPhone, via `jizza-agent-core`) would need so the web/extension plans
don't block them later.

| Plan | What the phone would need |
|------|---------------------------|
| 02 inputs | Render the input catalogue's new types (enum with synonyms, rows with typed columns, secret, file) on the run form and cards. Core `StepForm.kt` parse changes. |
| 05 security | Card `secret` answers encrypted to the target browser device's key (option B) in the app before sending; never cached on the phone. |
| 06 person in the loop | Captcha/OTP cards with `image` + `answer` blocks as push notifications with a quick-reply field; countdown; seat-map `tapImage`. The phone becomes the natural place to answer when the person is away from the computer. |
| 08 results | Show workflow results (PNR, files) in the run detail; open downloaded PDFs. |
| 09 timed runs | Readiness alerts as high-priority push. |
| 10 chat | Start card and inline cards in mobile chat. |

The server endpoints and payloads in the plans are designed to be platform-neutral, so the apps
can catch up without server changes.

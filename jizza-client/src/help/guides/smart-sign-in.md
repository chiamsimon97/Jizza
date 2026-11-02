---
title: Smart sign-in with a QR code
summary: Sign in on a computer by scanning a QR code with a phone that is already signed in to Jizza.
section: Account and security
order: 10
updated: 2026-11-02
---

## What it is

Smart sign-in lets you skip the password on a computer. The login page shows a QR code, you scan it with a phone that is already signed in to Jizza, you tap **Approve**, and the computer signs in by itself.

## Sign in with the QR code

1. On the computer, open the Jizza login page and click the blurred QR code under **Smart sign-in with the Jizza app**.
2. On your phone, either:
   - open the camera and scan the code, then open the link. Jizza opens in the phone's browser. If the phone is not signed in yet, sign in there first and the approve screen comes back; or
   - scan it from the **Jizza Agent** app, if your phone is paired under [Devices](/settings/devices).
3. Check the browser and IP address shown on the phone, then tap **Approve**.
4. The computer signs in within a couple of seconds.

Each code lasts 2 minutes and works once. If it runs out, click **Get a new code**.

## Keeping it safe

- **Only approve a sign-in on a screen in front of you.** Someone could send you a QR code from their own login page and ask you to scan it. Approving that would sign them in to your account.
- If your account uses two-step sign-in, the phone that approves must have passed it too. A paired Jizza Agent phone counts as your second factor, and the app asks for your fingerprint or PIN first.
- Only the computer that showed the code can use the approval. A photo of the QR code is no use to anyone else.
- Every approval and denial is in your [Activity](/activity) log, and you get the usual email when a new device signs in.
- You can sign the computer out later under [Security](/settings/security), in **Sessions**.

## When it does not work

- **The phone can't open the link.** The phone must reach the same Jizza address as the computer. A computer on `localhost` can't be reached from a phone. Use the server's network address or its public domain instead.
- **"Two-step sign-in needed".** The phone's session hasn't passed two-step sign-in. Sign out on the phone, sign back in with your authenticator code, and scan again.
- **"Code already used".** Someone else scanned the code first, or it was already answered. Get a new code.

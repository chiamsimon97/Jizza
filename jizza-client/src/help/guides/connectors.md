---
title: Connect your apps, servers and devices
summary: See every connector in one place, choose which agents may use each, check what its tools do, and sign in again when a connection drops.
section: Connectors
order: 10
updated: 2026-11-22
---

## One place for everything your agents can reach

[Connectors](/connectors) has four tabs:

| Tab | What's there |
| --- | --- |
| Connected | The services and MCP servers you've added |
| Catalog | Ready-made MCP servers to install with one click |
| Build your own | A service for any app with an API, or an MCP server by its URL |
| Devices | Computers, phones and browsers running the Jizza Agent app |

Every card shows the same things: whether it's **Ready**, **Needs sign-in** or **Failing**, how many tools it brings and what they do (read, change things, move money), which agents may use it, and when it was last used. Cards that need you come first.

## Choosing who can use a connector

On a card, press **Change** next to the agents and tick the ones that may use it, or **Every agent**. You'll be asked for your password if you haven't entered it in the last 15 minutes, because this widens what an agent can do.

- For a **service**, giving it to an agent gives every action. To give single actions, open the service and use its Actions tab. The card then says "some actions only".
- **Devices** are Jizza's to begin with. You can now give a device to a specialist too.

The same list is on each agent's page: open the agent in [Agents](/agents), then **Tools**, and look under **Connectors**.

## Checking what a new server's tools do

When you add an MCP server, Jizza guesses what each tool does from its name and shows them grouped: the ones that look like reads, the ones that change things and the ones that move money. Press **Accept all**, or change any first. Each agent's guardrails go by these types, so a lookup runs under its rule for looking things up and a payment under its rule for money.

A tool with no type is treated as moving money until you choose. You can come back to the review any time with **Review tool types** on the server's page.

## Testing a connection

Press **Test** on any card. Jizza reaches the service, server or device once and tells you whether it answered.

## When a connection needs you to sign in again

If a sign-in runs out and Jizza can't renew it, you'll see:

- a card in chat, with a button that opens the connector's page, and
- a note in your inbox with **Sign in again**.

Some servers, such as Zerodha Kite, sign you in inside their own session. That session ends whenever Jizza restarts, so the card says **Needs sign-in** afterwards. Open the server and press **Sign in**, then open the link it gives you.

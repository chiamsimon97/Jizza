---
title: Connect your apps, servers and devices
summary: See every connector in one place, build one by chatting, start workflows the moment a service calls, add older and local MCP servers, and sign in again when a connection drops.
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

## Building a connector by chatting

In [Chat](/chat), tell Jizza which app you want to connect and paste a link to its API docs, or the docs themselves. For example: *"Make a connector for our booking system. The docs are at …"*. Jizza then:

1. drafts the service and its sign-in type,
2. adds the requests it needs from the docs (an OpenAPI file brings them all at once),
3. asks you to add the API key or sign-in on the service's **Authentication** tab, with a button that opens it,
4. tries each action with you. Reads run straight away; anything that changes something asks you first,
5. publishes it when you say so, after asking you once more.

You can open the draft in [Connectors](/connectors) at any point and change it yourself.

## Starting workflows the moment something happens

A service's **Triggers** tab offers two kinds:

- **Checks a list every few minutes**: Jizza asks the service for new items on a schedule.
- **Instant (a webhook)**: the service calls Jizza when something happens. Copy the **webhook address** into the app's webhook settings. If the app signs its calls, add its **signing secret**, the **signature header** it uses and any **prefix** (such as `sha256=`), and Jizza refuses calls that aren't signed with it.

To start a workflow from either kind, open the workflow and pick the service event in the **or when** list next to its trigger. The trigger's card then says how many workflows it starts.

## Older and local MCP servers

When you add an MCP server, **How Jizza reaches it** has three choices:

| Choice | Use it for |
| --- | --- |
| Over the web (streamable HTTP) | Most servers today |
| Over the web, older server (SSE) | Servers whose address ends in `/sse`. Jizza picks this for you |
| On my computer, through the Jizza app | A server that runs on your own computer (a "stdio" server) |

For a server on your computer, first set it up in the Jizza app on that computer: **Settings › MCP servers › Add…** gives it a name and the command from the server's instructions, such as `npx -y @modelcontextprotocol/server-filesystem C:\Notes`. Then add it in Jizza, choose the computer and pick the server by that name. Jizza only ever sends the name, so what runs on your computer is decided there and nowhere else. The server signs in on the computer too: keep any key in the app's **Secrets** and write `secret:NAME` in its environment. The computer has to be on, with the app running, when an agent uses the server.

## Testing a connection

Press **Test** on any card. Jizza reaches the service, server or device once and tells you whether it answered.

## When a connection needs you to sign in again

If a sign-in runs out and Jizza can't renew it, you'll see:

- a card in chat, with a button that opens the connector's page, and
- a note in your inbox with **Sign in again**.

Some servers, such as Zerodha Kite, sign you in inside their own session. That session ends whenever Jizza restarts, so the card says **Needs sign-in** afterwards. Open the server and press **Sign in**, then open the link it gives you.

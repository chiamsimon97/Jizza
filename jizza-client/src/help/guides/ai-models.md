---
title: Pick the AI model for a chat
summary: Connect OpenAI, Gemini, OpenRouter, Ollama or your own Anthropic key, pick a model and Quick, Normal or Deep in the chat box, ask another model and keep the better answer, and give an agent its own model.
section: Chat
order: 20
updated: 2026-11-25
---

## Connecting a provider with your own key

Open [Settings › AI models](/settings/ai-models) (your name at the bottom of the left menu, then **Settings**). Each provider has a card:

| Provider | What it needs |
| --- | --- |
| OpenAI | An API key from your OpenAI account |
| Google Gemini | An API key from Google AI Studio |
| OpenRouter | An API key: one key reaches hundreds of models |
| Ollama | The address where Ollama runs, as the Jizza server sees it. No key |
| Anthropic | Your own Anthropic key, billed to you instead of Jizza |
| Your own server | The address of any server that speaks the OpenAI format, and its key if it needs one |

Press **Connect**, paste the key and press **Check and connect**. Jizza tries the key straight away. A key that doesn't work is not saved. You'll be asked for your password if you haven't entered it in the last 15 minutes, because a key can spend money.

The key is stored encrypted and is never shown again. The card shows only its last four characters, such as "Key ••••abcd". To change it, press **Change key**. Leave the box empty to keep the saved key and change only the address.

Use **Models** to see what the provider offers and to hide any you don't want in the picker. **Refresh** asks the provider again for its list. Use the switch to turn a provider off without removing it, or **Remove** to delete it and its key.

## Picking a model in the chat box

Under the message box, the model button says **Auto** until you pick one. With Auto, Jizza picks a model for each message. Open it to see every model you can use, grouped by provider, each with a short note and its price per million tokens.

Your pick sticks to the chat. Open the chat again later and it is still set. A new chat starts with the model you last picked.

Some models can't see photos or read PDFs. While one is attached, those models are greyed out. Models marked "talk only, no tools" can answer, but they can't use connectors, search the web or start workflows.

## Quick, Normal and Deep

Next to the model, choose how hard the answer should think:

- **Quick**: a fast, cheap answer. Jizza skips planning and uses its fastest model.
- **Normal**: the usual.
- **Deep**: thinks harder, which is slower and costs more. With Auto, Jizza uses its strongest model. With a picked model, that model is asked to reason at length where it can.

## Asking another model

Under the latest reply, press the two-column button (**Ask another model**) and pick a model. The same question goes to that model. The first answer is kept, so you can page between them with the arrows. Each answer says which model wrote it.

Press **Compare** to see two answers side by side. **Keep this one** on the earlier answer makes the chat carry on from it. The other answer stays as an earlier version.

## Who worked on a reply

When Jizza asked specialists for help, a line under the reply says how many worked on it and what it cost. Open it to see what each one was asked, which model they used and its cost. Use on your own key shows "your key" and doesn't count towards Jizza's spending limit.

## Giving an agent its own model

Open the agent in [Agents](/agents). Under **Model**, pick the model it should answer with when the chat box is on Auto. A model picked in the chat box always wins. Choose **Auto: Jizza picks** to go back.

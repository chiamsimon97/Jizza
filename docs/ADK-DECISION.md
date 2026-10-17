# Decision: Google ADK for Java as Jizza's agent runtime

*Status: decided, October 2026. Revisit when a trigger below fires.*

## The question

Google's Agent Development Kit for Java (`com.google.adk:google-adk`, 1.12.0 on 2026-10-14) is an
open-source agent framework: an LLM agent loop, tools, plugins, sessions, memory, MCP, A2A and a dev
UI. Jizza wrote its own agent loop. Should Jizza run on ADK instead?

What ADK could replace is narrow. Most of Jizza is the platform around the agents: OAuth
connectors and the credential vault, the guardrails and kill switch, the audit log, the Postgres
saga engine, learned procedures, the scheduler, notifications and accounts. ADK offers none of
that. The real candidate is the agent loop (`BaseAgent`, about 300 lines) and the model client
behind it.

## What was done first, independent of the answer

The comparison showed gaps in Jizza that matter whatever the runtime. They are closed:

| Gap | Now |
|---|---|
| A rate-limited or overloaded provider failed the user's turn | Retries with backoff, honouring `retry-after`; a stream is retried only before any text reached the user (`AnthropicLlmClient`) |
| No prompt caching | Tool definitions and each agent's instructions are cached; inside a tool loop the conversation is too. Memory moved to its own system block so the cached prefix stays stable. Cache reads and writes are priced at their own rates and stored on `llm_usage` (V15) |
| Thinking blocks were dropped between tool calls | The model's turn is kept exactly as returned, signatures included, and replayed unchanged (`LlmResponse.providerContent`, `Msg.assistantTurn`) |
| No way to see where a turn spent its time or money | Spans for every agent run, model call and tool call; OTLP export when an endpoint is set |
| No MCP | `engine-mcp`: tools from configured MCP servers, through the Capability Registry, with the owner deciding what each tool does |
| Nothing to catch an agent quietly getting worse | `jizza-evals`: 13 scenarios across Jizza, Vita, Kai and Cato, eight of them safety-critical |

History summarising was on the original list and is not needed: `ConversationUpkeep` already folds
the oldest messages into a running summary.

## The spike

`jizza-spikes/adk-spike` (build with `-Pspikes`) runs Kai on ADK's `LlmAgent` and `InMemoryRunner`:

* `JizzaModel`: Jizza's own Claude client as an ADK `BaseLlm`, translating to and from Gemini's
  `Content`/`Part` types.
* `CapabilityTool`: each tool hands its call to the Capability Registry, so the kill switch,
  schema check, guardrails and audit happen exactly where they do today.
* `JizzaRunPlugin`: cancellation, cost accounting and the run budget as ADK plugin hooks.
* `AdkAgent`: implements Jizza's `Agent` interface, so it could stand in for `BaseAgent`.

`AdkSpikeTest` runs the eval scenarios through both runtimes, with the real registry and
guardrails and a scripted model.

### Pass criteria

| Criterion | Result |
|---|---|
| The eval scenarios grade the same as on `BaseAgent` | **Pass.** Same failures, same guardrail decisions |
| Streaming and the stop button still work | **Pass**, because ADK ran on the calling thread. It would need re-checking if ADK's tool execution moved to other threads, since Jizza's activity context is thread-local |
| The saga engine can still checkpoint | **Pass by design.** The saga calls `Agent.run` per step; an ADK session lives only for one run, so no Postgres session service is needed |
| Thinking survives a tool round | **Pass, through a workaround.** Claude thinking blocks have no home in Gemini's types; they ride in thought parts whose signature bytes encode the block. This depends on ADK keeping signed thought parts in history, an implementation detail rather than a promise |
| Less code than `BaseAgent` | **Fail.** 485 lines of adapter against 318. Jizza's checks stay Jizza's, and a translation layer is added |

### What else the spike found

* **Weight.** The agent module on ADK has 276 runtime jars (226 MB). The entire Jizza application
  today has 184 (90 MB). ADK brings Google Cloud clients (BigQuery, Storage, Speech, AI Platform),
  gRPC, Netty, Kotlin, docker-java, a second MCP SDK on Jackson 3 and a second Anthropic SDK. For
  a local-first, single-user product that is the wrong direction.
* **ADK's own Claude model is not usable for Jizza.** It does not stream (`// TODO: Switch to
  streaming API`), turns off parallel tool calls, and drops thinking blocks and cache markers.
* **ADK changes the prompt.** It appends `You are an agent. Your internal name is "<key>".` to
  every agent's instructions. That is harmless to caching (it is stable), but it is text nobody wrote.
* **Instruction templating is a trap.** A plain-string instruction has `{placeholders}` filled from
  session state, and Jizza's agent instructions are users' own words. The spike passes an
  `Instruction.Provider` to opt out.
* **Two threading models.** ADK is RxJava throughout; Jizza is blocking Spring MVC with
  thread-local job context.

## Decision

**Keep Jizza's own agent loop. Do not adopt ADK as the runtime now.**

ADK does not make Jizza smaller or safer. The behaviour that matters (registry, guardrails,
budgets, accounting, replaying thinking, streaming) had to be rebuilt around it, it adds a
translation layer at the most delicate point (the model's own turns), and it more than doubles
what Jizza ships. What made ADK attractive, MCP and tracing, Jizza now has without it.

The interfaces that made the spike possible (`Agent`, `LlmClient`, `ToolProvider`) stay, so the
question can be reopened cheaply.

## Revisit when

* ADK for Java ships a Claude model that streams and keeps thinking blocks, **and** its core
  dependencies stop pulling in Google Cloud clients (or can be excluded cleanly).
* ADK's graph workflows (`Workflow`, experimental in 1.12.0) become stable **and** gain durable
  checkpoints, which would make them a candidate to compare with Jizza's saga engine.
* ADK's evaluation framework reaches Java.
* Jizza needs to talk to agents outside itself (A2A), where ADK's A2A support could be used at the
  edge without replacing the loop.

To re-run the comparison against a newer ADK, bump `google-adk.version` in
`jizza-spikes/adk-spike/pom.xml` and run:

```bash
mvn -Pspikes -pl jizza-spikes/adk-spike -am install
```

package com.jizza.engine.api.cosmos;

/** What a body in the Memory Cosmos stands for. Memory items themselves are {@link MemoryNode}s. */
public enum BodyType {
  /** Jizza, the orchestrator. */
  SUN,
  /** A specialist agent. */
  AGENT,
  /** A person, account, plan... the user's memory is about. */
  SUBJECT,
  /** An agent's own notes. */
  NOTES,
  /** A file the user gave an agent. */
  FILE,
  /** A Connector an agent can use. */
  CONNECTOR,
  /** A workflow an agent owns; learned procedures included. */
  WORKFLOW,
  /** A skill granted to an agent. */
  SKILL
}

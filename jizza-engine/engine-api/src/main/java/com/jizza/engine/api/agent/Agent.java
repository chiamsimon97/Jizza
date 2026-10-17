package com.jizza.engine.api.agent;

/** The Base Agent runtime (R15): system prompt + capability-filtered tools + a bounded tool-use loop. */
public interface Agent {
  AgentResult run(AgentRequest req);
}

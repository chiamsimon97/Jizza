package com.jizza.engine.api.orchestrator;

/**
 * An agent as the platform defines it: who it is, what it is for, and the prompt it works from.
 *
 * <p>{@code key} is what code routes on and never changes; {@code name} is what the user sees and
 * is theirs to edit. Which agents exist at all, and what any of them are told, is a product
 * decision -- the engine only runs whatever it is given.
 */
public record AgentSpec(String key, String name, String domain, boolean orchestrator,
                        String systemPrompt) {}

package com.jizza.engine.api.enums;

/**
 * memories.status. Only {@code ACTIVE} memories are ever handed to an agent.
 *
 * <ul>
 *   <li>{@code PENDING}: conflicts with a confirmed fact and waits for the user to confirm it;</li>
 *   <li>{@code SUPERSEDED}: replaced by a newer fact with the same key;</li>
 *   <li>{@code RETIRED}: an agent marked it no longer true. Reversible; only the user deletes.</li>
 * </ul>
 */
public enum MemoryStatus { ACTIVE, PENDING, SUPERSEDED, RETIRED }

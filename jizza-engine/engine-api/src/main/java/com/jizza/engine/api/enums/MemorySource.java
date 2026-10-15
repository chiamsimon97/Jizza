package com.jizza.engine.api.enums;

/**
 * memories.source -- who put a fact into memory. {@code USER} facts are confirmed by definition;
 * {@code TOOL} facts were found in data a connected service returned and stay unconfirmed until the
 * user says otherwise.
 */
public enum MemorySource { USER, AGENT, CONVERSATION, IMPORT, TOOL }

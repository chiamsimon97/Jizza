package com.jizza.engine.api.chat;

import java.util.UUID;

/** What the client needs to follow a turn it just started. Ids are null for an incognito turn's messages. */
public record TurnRef(UUID jobId, UUID conversationId, UUID userMessageId, UUID assistantMessageId,
                      boolean incognito) {}

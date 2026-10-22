package com.jizza.engine.persistence.repository;

import com.jizza.engine.api.enums.RunStatus;
import java.time.Instant;
import java.util.UUID;

/** A run without its context, which is all the history list needs and can be large. */
public record WorkflowRunRow(UUID id, UUID workflowId, int version, String triggerSource,
                             RunStatus status, String currentNode, String error,
                             Instant createdAt, Instant updatedAt) {}

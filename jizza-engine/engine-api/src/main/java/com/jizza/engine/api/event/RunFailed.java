package com.jizza.engine.api.event;

import java.util.UUID;

/**
 * A run the person started (not one a parent run started as a step) has failed. The phone app
 * turns it into a push alert, so a failure is not something they find out about hours later.
 *
 * @param error what went wrong, as the run history shows it; may be null
 */
public record RunFailed(UUID userId, UUID runId, UUID workflowId, String workflowName,
                        String error) {}

package com.jizza.common.event;

import java.util.UUID;

/**
 * Published when a task the user marked repetitive finishes without a bound procedure. The
 * procedures module listens and asks the owning agent to capture what it just did (R34); the
 * scheduler stays unaware that learning exists.
 */
public record RepetitiveTaskCompleted(UUID userId, UUID agentId, UUID taskId, String title,
                                      String outcome) {}

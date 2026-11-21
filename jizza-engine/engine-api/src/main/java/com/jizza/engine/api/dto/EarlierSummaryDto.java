package com.jizza.engine.api.dto;

import java.time.Instant;

/**
 * What Jizza now remembers of a long conversation's older messages: they were folded into this
 * prose to keep each turn small, so the model sees the summary in their place.
 *
 * @param through the summary covers the messages sent before this moment
 */
public record EarlierSummaryDto(String summary, Instant through) {}

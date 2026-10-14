package com.jizza.engine.api.cosmos;

/**
 * A line between two bodies that is not orbit: a subject relation ({@code held_by},
 * {@code plan_for}) or an account reached through a Connector ({@code connector}).
 */
public record BodyLink(String fromBodyId, String toBodyId, String kind) {}

package com.jizza.engine.api.cosmos;

import java.time.Instant;
import java.util.List;

/** Every body and its counts. Memory items are never included: they load per body, lazily. */
public record CosmosSnapshot(List<CelestialBody> bodies, List<BodyLink> links, Instant generatedAt) {}

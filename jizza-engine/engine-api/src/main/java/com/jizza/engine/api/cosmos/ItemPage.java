package com.jizza.engine.api.cosmos;

import java.util.List;

/** A page of the items orbiting one body; {@code nextCursor} is null on the last page. */
public record ItemPage(List<MemoryNode> items, String nextCursor) {}

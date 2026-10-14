package com.jizza.engine.api.cosmos;

import java.util.List;

/**
 * @param bodyPath sun to the item's body, for highlighting and flying the camera there
 * @param snippet  matching text; null for a restricted item
 */
public record MemorySearchHit(MemoryNode item, List<String> bodyPath, String snippet, double score) {}

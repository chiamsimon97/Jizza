package com.jizza.engine.api.memory;

import com.jizza.engine.api.enums.MemoryCategory;
import com.jizza.engine.api.enums.MemoryStatus;
import java.util.UUID;

/** Narrowing for listing memories; every field is optional. */
public record MemoryFilter(MemoryCategory category, MemoryStatus status, UUID subjectId, int limit) {

  public static MemoryFilter all(int limit) { return new MemoryFilter(null, null, null, limit); }
}

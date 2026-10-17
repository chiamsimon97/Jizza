package com.jizza.engine.api.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.engine.api.enums.DataClassification;
import com.jizza.engine.api.enums.MemoryCategory;
import com.jizza.engine.api.enums.MemorySource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Something to remember, or the corrected version of something remembered. Null fields take the
 * store's defaults on create and are left unchanged on correct.
 *
 * @param domains null to derive from the category; an empty list to share with every agent
 */
public record MemoryDraft(MemoryCategory category, String content, String factKey, JsonNode data,
                          DataClassification classification, Double confidence, Double importance,
                          MemorySource source, String sourceRef, Boolean confirmed,
                          List<UUID> subjectIds, List<String> domains, Instant expiresAt) {

  public static MemoryDraft of(MemoryCategory category, String content) {
    return new MemoryDraft(category, content, null, null, null, null, null, null, null, null,
        List.of(), null, null);
  }

  public MemoryDraft about(List<UUID> subjects) {
    return new MemoryDraft(category, content, factKey, data, classification, confidence,
        importance, source, sourceRef, confirmed, subjects, domains, expiresAt);
  }

  public MemoryDraft keyed(String key) {
    return new MemoryDraft(category, content, key, data, classification, confidence,
        importance, source, sourceRef, confirmed, subjectIds, domains, expiresAt);
  }

  public MemoryDraft from(MemorySource s, String ref, boolean isConfirmed, double conf) {
    return new MemoryDraft(category, content, factKey, data, classification, conf,
        importance, s, ref, isConfirmed, subjectIds, domains, expiresAt);
  }

  public MemoryDraft withImportance(double value) {
    return new MemoryDraft(category, content, factKey, data, classification, confidence,
        value, source, sourceRef, confirmed, subjectIds, domains, expiresAt);
  }

  public MemoryDraft sharedWith(List<String> agentDomains) {
    return new MemoryDraft(category, content, factKey, data, classification, confidence,
        importance, source, sourceRef, confirmed, subjectIds, agentDomains, expiresAt);
  }
}

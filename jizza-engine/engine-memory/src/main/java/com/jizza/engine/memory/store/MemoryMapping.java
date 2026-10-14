package com.jizza.engine.memory.store;

import com.jizza.engine.api.memory.Memory;
import com.jizza.engine.api.memory.MemoryChange;
import com.jizza.engine.api.memory.MemorySubject;
import com.jizza.engine.api.util.Hashing;
import com.jizza.engine.persistence.entity.MemoryEntryEntity;
import com.jizza.engine.persistence.entity.MemoryHistoryEntity;
import com.jizza.engine.persistence.entity.MemorySubjectEntity;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Entity to record conversions and the normalisation rules shared by the write and read paths. */
public final class MemoryMapping {

  private MemoryMapping() {}

  public static Memory toMemory(MemoryEntryEntity e) {
    return new Memory(e.getId(), e.getUserId(), e.getCategory(), List.of(e.getDomains()),
        e.getClassification(), e.getContent(), e.getFactKey(), e.getData(), e.getConfidence(),
        e.getImportance(), e.getSource(), e.getSourceRef(), e.isConfirmed(), e.getStatus(),
        e.getSupersededBy(), List.copyOf(e.getSubjectIds()), e.getExpiresAt(), e.getCreatedAt(),
        e.getUpdatedAt(), e.getLastAccessedAt(), e.isRestricted(), e.getAccessCount(), null);
  }

  public static MemorySubject toSubject(MemorySubjectEntity e, List<MemorySubject.Relation> relations) {
    return new MemorySubject(e.getId(), e.getUserId(), e.getKind(), e.getName(), e.getRelation(),
        List.of(e.getAliases()), e.getAttributes(), e.getConnectionId(), List.of(e.getDomains()),
        relations, e.getCreatedAt(), e.getUpdatedAt());
  }

  public static MemoryChange toChange(MemoryHistoryEntity h) {
    return new MemoryChange(h.getId(), h.getMemoryId(), h.getChange(), h.getPreviousContent(),
        h.getPreviousData(), h.getActorType(), h.getActorId(), h.getNote(), h.getCreatedAt());
  }

  /** {@code "Risk Tolerance"} and {@code "risk-tolerance"} name the same slot. */
  public static String normaliseKey(String key) {
    if (key == null) return null;
    String k = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    if (k.isEmpty()) return null;
    return k.length() > 80 ? k.substring(0, 80) : k;
  }

  public static String normaliseContent(String content) {
    return content == null ? "" : content.strip().replaceAll("\\s+", " ");
  }

  /** Same words about the same subjects is the same fact, whatever the case or spacing. */
  public static String contentHash(String content, Collection<UUID> subjectIds) {
    String subjects = subjectIds.stream().map(UUID::toString).sorted().reduce("", (a, b) -> a + "," + b);
    return Hashing.sha256Hex(normaliseContent(content).toLowerCase(Locale.ROOT) + "|" + subjects);
  }

  public static String[] normaliseDomains(Collection<String> domains) {
    if (domains == null) return new String[0];
    return domains.stream()
        .filter(d -> d != null && !d.isBlank())
        .map(d -> d.strip().toLowerCase(Locale.ROOT))
        .distinct().sorted().toArray(String[]::new);
  }

  public static String[] normaliseAliases(Collection<String> aliases) {
    if (aliases == null) return new String[0];
    return aliases.stream()
        .filter(a -> a != null && !a.isBlank())
        .map(String::strip)
        .distinct().toArray(String[]::new);
  }

  public static boolean sameSubjects(Collection<UUID> a, Collection<UUID> b) {
    return a.size() == b.size() && a.containsAll(b);
  }
}

package com.jizza.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One agent reading one memory item in one run: a stamp in the item's passport. Written by
 * {@code MemoryAccessRecorder} with {@code on conflict do nothing}, so a run that reads the same
 * item repeatedly leaves one stamp. Read-only from JPA.
 */
@Entity
@Table(name = "memory_access")
public class MemoryAccessEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private UUID userId;

  /** {@code fact} (a row in {@code memories}) or {@code note} (a row in {@code knowledge_entries}). */
  @Column(nullable = false)
  private String itemKind;

  @Column(nullable = false)
  private UUID itemId;

  private UUID agentId;

  private String contextKind;

  private String contextRef;

  @Column(nullable = false)
  private Instant occurredAt;

  public Long getId() { return id; }
  public UUID getUserId() { return userId; }
  public String getItemKind() { return itemKind; }
  public UUID getItemId() { return itemId; }
  public UUID getAgentId() { return agentId; }
  public String getContextKind() { return contextKind; }
  public String getContextRef() { return contextRef; }
  public Instant getOccurredAt() { return occurredAt; }
}

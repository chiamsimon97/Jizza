package com.jizza.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One line of a job's timeline. Append-only; ordered by {@code seq}. */
@Entity
@Table(name = "job_events")
@IdClass(JobEventEntity.Key.class)
public class JobEventEntity {

  public record Key(UUID jobId, int seq) implements Serializable {
    public Key() { this(null, 0); }
  }

  @Id
  private UUID jobId;

  @Id
  private int seq;

  @Column(nullable = false)
  private String type;

  private UUID agentId;
  private String agentName;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private JsonNode payload;

  @Column(nullable = false)
  private Instant at;

  public UUID getJobId() { return jobId; }
  public void setJobId(UUID jobId) { this.jobId = jobId; }
  public int getSeq() { return seq; }
  public void setSeq(int seq) { this.seq = seq; }
  public String getType() { return type; }
  public void setType(String type) { this.type = type; }
  public UUID getAgentId() { return agentId; }
  public void setAgentId(UUID agentId) { this.agentId = agentId; }
  public String getAgentName() { return agentName; }
  public void setAgentName(String agentName) { this.agentName = agentName; }
  public JsonNode getPayload() { return payload; }
  public void setPayload(JsonNode payload) { this.payload = payload; }
  public Instant getAt() { return at; }
  public void setAt(Instant at) { this.at = at; }
}

package com.jizza.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One submission of a form; see {@code V41__forms.sql}. */
@Entity
@Table(name = "form_submissions")
public class FormSubmissionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID formId;

  /** The form's owner. */
  @Column(nullable = false)
  private UUID userId;

  private int number;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private JsonNode data;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private JsonNode runs;

  private Instant submittedAt;

  public UUID getId() { return id; }
  public UUID getFormId() { return formId; }
  public void setFormId(UUID v) { this.formId = v; }
  public UUID getUserId() { return userId; }
  public void setUserId(UUID v) { this.userId = v; }
  public int getNumber() { return number; }
  public void setNumber(int v) { this.number = v; }
  public JsonNode getData() { return data; }
  public void setData(JsonNode v) { this.data = v; }
  public JsonNode getRuns() { return runs; }
  public void setRuns(JsonNode v) { this.runs = v; }
  public Instant getSubmittedAt() { return submittedAt; }

  @PrePersist
  void onCreate() {
    if (submittedAt == null) submittedAt = Instant.now();
  }
}

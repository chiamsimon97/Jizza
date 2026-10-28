package com.jizza.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;

/** One captured step; the step JSON is encrypted because it can hold typed values. */
@Entity
@Table(name = "web_recording_steps")
@IdClass(WebRecordingStepEntity.Key.class)
public class WebRecordingStepEntity {

  public record Key(UUID recordingId, int seq) implements Serializable {
    public Key() { this(null, 0); }
  }

  @Id
  private UUID recordingId;

  @Id
  private int seq;

  @Column(nullable = false)
  private int position;

  @Column(nullable = false)
  private byte[] step;

  private boolean deleted;

  public UUID getRecordingId() { return recordingId; }
  public void setRecordingId(UUID recordingId) { this.recordingId = recordingId; }
  public int getSeq() { return seq; }
  public void setSeq(int seq) { this.seq = seq; }
  public int getPosition() { return position; }
  public void setPosition(int position) { this.position = position; }
  public byte[] getStep() { return step; }
  public void setStep(byte[] step) { this.step = step; }
  public boolean isDeleted() { return deleted; }
  public void setDeleted(boolean deleted) { this.deleted = deleted; }
}

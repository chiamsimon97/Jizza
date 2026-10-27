package com.jizza.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A diagnostic line a device sent. */
@Entity
@Table(name = "device_logs")
public class DeviceLogEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private UUID deviceId;

  @Column(nullable = false)
  private Instant ts;

  @Column(nullable = false)
  private String level;

  @Column(nullable = false, columnDefinition = "text")
  private String message;

  private UUID commandId;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }

  public UUID getDeviceId() { return deviceId; }
  public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }

  public Instant getTs() { return ts; }
  public void setTs(Instant ts) { this.ts = ts; }

  public String getLevel() { return level; }
  public void setLevel(String level) { this.level = level; }

  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }

  public UUID getCommandId() { return commandId; }
  public void setCommandId(UUID commandId) { this.commandId = commandId; }
}

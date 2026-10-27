package com.jizza.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A file a device uploaded with a command result. */
@Entity
@Table(name = "device_files")
public class DeviceFileEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID deviceId;

  private UUID commandId;

  @Column(nullable = false)
  private String name;

  private String contentType;

  private long size;

  @Column(nullable = false, columnDefinition = "text")
  private String storageRef;

  @Column(nullable = false)
  private Instant createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getDeviceId() { return deviceId; }
  public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }

  public UUID getCommandId() { return commandId; }
  public void setCommandId(UUID commandId) { this.commandId = commandId; }

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }

  public String getContentType() { return contentType; }
  public void setContentType(String contentType) { this.contentType = contentType; }

  public long getSize() { return size; }
  public void setSize(long size) { this.size = size; }

  public String getStorageRef() { return storageRef; }
  public void setStorageRef(String storageRef) { this.storageRef = storageRef; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

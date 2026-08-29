package com.jizza.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "files")
public class FileEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false, columnDefinition = "text")
  private String storageRef;

  private String filename;

  private String contentType;

  private Long sizeBytes;

  private String scanStatus;

  private String sha256;

  private Instant createdAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public String getStorageRef() { return storageRef; }
  public void setStorageRef(String storageRef) { this.storageRef = storageRef; }

  public String getFilename() { return filename; }
  public void setFilename(String filename) { this.filename = filename; }

  public String getContentType() { return contentType; }
  public void setContentType(String contentType) { this.contentType = contentType; }

  public Long getSizeBytes() { return sizeBytes; }
  public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

  public String getScanStatus() { return scanStatus; }
  public void setScanStatus(String scanStatus) { this.scanStatus = scanStatus; }

  public String getSha256() { return sha256; }
  public void setSha256(String sha256) { this.sha256 = sha256; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
  }
}

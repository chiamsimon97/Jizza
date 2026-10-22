package com.jizza.engine.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A remote MCP server the user added from the app; see {@code V19__mcp_servers.sql}. */
@Entity
@Table(name = "mcp_servers")
public class McpServerEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private UUID workspaceId;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String slug;

  @Column(nullable = false, columnDefinition = "text")
  private String url;

  @Column(nullable = false)
  private String authType;

  private String headerName;

  private byte[] secret;

  private boolean enabled = true;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode agentKeys;

  private boolean redactArguments;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode tools;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode toolSettings;

  private Instant toolsRefreshedAt;

  @Column(nullable = false)
  private String status;

  @Column(columnDefinition = "text")
  private String lastError;

  private String serverName;

  private String serverVersion;

  @Column(columnDefinition = "text")
  private String oauthClientId;

  private byte[] oauthClientSecret;

  @Column(columnDefinition = "text")
  private String oauthAuthorizationUrl;

  @Column(columnDefinition = "text")
  private String oauthTokenUrl;

  @Column(columnDefinition = "text")
  private String oauthScopes;

  @Column(columnDefinition = "text")
  private String oauthResource;

  private byte[] accessToken;

  private byte[] refreshToken;

  private Instant tokenExpiresAt;

  private String oauthState;

  private byte[] oauthVerifier;

  private UUID oauthSessionId;

  private Instant oauthStateExpiresAt;

  private Instant createdAt;

  private Instant updatedAt;

  public UUID getId() { return id; }
  public void setId(UUID v) { this.id = v; }
  public UUID getUserId() { return userId; }
  public void setUserId(UUID v) { this.userId = v; }
  public UUID getWorkspaceId() { return workspaceId; }
  public void setWorkspaceId(UUID v) { this.workspaceId = v; }
  public String getName() { return name; }
  public void setName(String v) { this.name = v; }
  public String getSlug() { return slug; }
  public void setSlug(String v) { this.slug = v; }
  public String getUrl() { return url; }
  public void setUrl(String v) { this.url = v; }
  public String getAuthType() { return authType; }
  public void setAuthType(String v) { this.authType = v; }
  public String getHeaderName() { return headerName; }
  public void setHeaderName(String v) { this.headerName = v; }
  public byte[] getSecret() { return secret; }
  public void setSecret(byte[] v) { this.secret = v; }
  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean v) { this.enabled = v; }
  public JsonNode getAgentKeys() { return agentKeys; }
  public void setAgentKeys(JsonNode v) { this.agentKeys = v; }
  public boolean isRedactArguments() { return redactArguments; }
  public void setRedactArguments(boolean v) { this.redactArguments = v; }
  public JsonNode getTools() { return tools; }
  public void setTools(JsonNode v) { this.tools = v; }
  public JsonNode getToolSettings() { return toolSettings; }
  public void setToolSettings(JsonNode v) { this.toolSettings = v; }
  public Instant getToolsRefreshedAt() { return toolsRefreshedAt; }
  public void setToolsRefreshedAt(Instant v) { this.toolsRefreshedAt = v; }
  public String getStatus() { return status; }
  public void setStatus(String v) { this.status = v; }
  public String getLastError() { return lastError; }
  public void setLastError(String v) { this.lastError = v; }
  public String getServerName() { return serverName; }
  public void setServerName(String v) { this.serverName = v; }
  public String getServerVersion() { return serverVersion; }
  public void setServerVersion(String v) { this.serverVersion = v; }
  public String getOauthClientId() { return oauthClientId; }
  public void setOauthClientId(String v) { this.oauthClientId = v; }
  public byte[] getOauthClientSecret() { return oauthClientSecret; }
  public void setOauthClientSecret(byte[] v) { this.oauthClientSecret = v; }
  public String getOauthAuthorizationUrl() { return oauthAuthorizationUrl; }
  public void setOauthAuthorizationUrl(String v) { this.oauthAuthorizationUrl = v; }
  public String getOauthTokenUrl() { return oauthTokenUrl; }
  public void setOauthTokenUrl(String v) { this.oauthTokenUrl = v; }
  public String getOauthScopes() { return oauthScopes; }
  public void setOauthScopes(String v) { this.oauthScopes = v; }
  public String getOauthResource() { return oauthResource; }
  public void setOauthResource(String v) { this.oauthResource = v; }
  public byte[] getAccessToken() { return accessToken; }
  public void setAccessToken(byte[] v) { this.accessToken = v; }
  public byte[] getRefreshToken() { return refreshToken; }
  public void setRefreshToken(byte[] v) { this.refreshToken = v; }
  public Instant getTokenExpiresAt() { return tokenExpiresAt; }
  public void setTokenExpiresAt(Instant v) { this.tokenExpiresAt = v; }
  public String getOauthState() { return oauthState; }
  public void setOauthState(String v) { this.oauthState = v; }
  public byte[] getOauthVerifier() { return oauthVerifier; }
  public void setOauthVerifier(byte[] v) { this.oauthVerifier = v; }
  public UUID getOauthSessionId() { return oauthSessionId; }
  public void setOauthSessionId(UUID v) { this.oauthSessionId = v; }
  public Instant getOauthStateExpiresAt() { return oauthStateExpiresAt; }
  public void setOauthStateExpiresAt(Instant v) { this.oauthStateExpiresAt = v; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant v) { this.createdAt = v; }
  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant v) { this.updatedAt = v; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
  }

  @PreUpdate
  void onUpdate() { updatedAt = Instant.now(); }
}

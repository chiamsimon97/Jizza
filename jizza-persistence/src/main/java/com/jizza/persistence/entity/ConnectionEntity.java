package com.jizza.persistence.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jizza.common.enums.AuthScheme;
import com.jizza.common.enums.ConnectionStatus;
import com.jizza.common.enums.TokenPlacement;
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

@Entity
@Table(name = "connections")
public class ConnectionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private String serviceName;

  @Column(nullable = false)
  private AuthScheme authScheme;

  @Column(columnDefinition = "text")
  private String clientId;

  private byte[] clientSecret;

  @Column(columnDefinition = "text")
  private String authorizationUrl;

  @Column(columnDefinition = "text")
  private String accessTokenUrl;

  @Column(columnDefinition = "text")
  private String refreshTokenUrl;

  @Column(columnDefinition = "text")
  private String scopes;

  private String scopeSeparator;

  private boolean pkce;

  private boolean unifiedCallback;

  private TokenPlacement tokenPlacement;

  private String grantType;

  private byte[] accessToken;

  private byte[] refreshToken;

  private Instant tokenExpiresAt;

  private boolean autoRefresh;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode testApi;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode revokeApi;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode profileApi;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode webhookVerifyApi;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode extraFields;

  @Column(nullable = false)
  private ConnectionStatus status;

  @Column(columnDefinition = "text")
  private String baseUrl;

  private Instant createdAt;

  private Instant updatedAt;

  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getUserId() { return userId; }
  public void setUserId(UUID userId) { this.userId = userId; }

  public String getServiceName() { return serviceName; }
  public void setServiceName(String serviceName) { this.serviceName = serviceName; }

  public AuthScheme getAuthScheme() { return authScheme; }
  public void setAuthScheme(AuthScheme authScheme) { this.authScheme = authScheme; }

  public String getClientId() { return clientId; }
  public void setClientId(String clientId) { this.clientId = clientId; }

  public byte[] getClientSecret() { return clientSecret; }
  public void setClientSecret(byte[] clientSecret) { this.clientSecret = clientSecret; }

  public String getAuthorizationUrl() { return authorizationUrl; }
  public void setAuthorizationUrl(String authorizationUrl) { this.authorizationUrl = authorizationUrl; }

  public String getAccessTokenUrl() { return accessTokenUrl; }
  public void setAccessTokenUrl(String accessTokenUrl) { this.accessTokenUrl = accessTokenUrl; }

  public String getRefreshTokenUrl() { return refreshTokenUrl; }
  public void setRefreshTokenUrl(String refreshTokenUrl) { this.refreshTokenUrl = refreshTokenUrl; }

  public String getScopes() { return scopes; }
  public void setScopes(String scopes) { this.scopes = scopes; }

  public String getScopeSeparator() { return scopeSeparator; }
  public void setScopeSeparator(String scopeSeparator) { this.scopeSeparator = scopeSeparator; }

  public boolean getPkce() { return pkce; }
  public void setPkce(boolean pkce) { this.pkce = pkce; }

  public boolean getUnifiedCallback() { return unifiedCallback; }
  public void setUnifiedCallback(boolean unifiedCallback) { this.unifiedCallback = unifiedCallback; }

  public TokenPlacement getTokenPlacement() { return tokenPlacement; }
  public void setTokenPlacement(TokenPlacement tokenPlacement) { this.tokenPlacement = tokenPlacement; }

  public String getGrantType() { return grantType; }
  public void setGrantType(String grantType) { this.grantType = grantType; }

  public byte[] getAccessToken() { return accessToken; }
  public void setAccessToken(byte[] accessToken) { this.accessToken = accessToken; }

  public byte[] getRefreshToken() { return refreshToken; }
  public void setRefreshToken(byte[] refreshToken) { this.refreshToken = refreshToken; }

  public Instant getTokenExpiresAt() { return tokenExpiresAt; }
  public void setTokenExpiresAt(Instant tokenExpiresAt) { this.tokenExpiresAt = tokenExpiresAt; }

  public boolean getAutoRefresh() { return autoRefresh; }
  public void setAutoRefresh(boolean autoRefresh) { this.autoRefresh = autoRefresh; }

  public JsonNode getTestApi() { return testApi; }
  public void setTestApi(JsonNode testApi) { this.testApi = testApi; }

  public JsonNode getRevokeApi() { return revokeApi; }
  public void setRevokeApi(JsonNode revokeApi) { this.revokeApi = revokeApi; }

  public JsonNode getProfileApi() { return profileApi; }
  public void setProfileApi(JsonNode profileApi) { this.profileApi = profileApi; }

  public JsonNode getWebhookVerifyApi() { return webhookVerifyApi; }
  public void setWebhookVerifyApi(JsonNode webhookVerifyApi) { this.webhookVerifyApi = webhookVerifyApi; }

  public JsonNode getExtraFields() { return extraFields; }
  public void setExtraFields(JsonNode extraFields) { this.extraFields = extraFields; }

  public ConnectionStatus getStatus() { return status; }
  public void setStatus(ConnectionStatus status) { this.status = status; }

  public String getBaseUrl() { return baseUrl; }
  public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
  }

  @PreUpdate
  void onUpdate() { updatedAt = Instant.now(); }
}
